package app.granger.core.books

import android.graphics.Bitmap
import android.graphics.Color
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File

/**
 * PDFs laid out line by line, for the PDF-to-ebook stages (Plan IA): each [Line] at its place, size and font, drawn or
 * hidden (a scan's OCR layer), and pictures at their place. Pictures need Robolectric's native graphics
 * (`@GraphicsMode(NATIVE)`). Call [PdfFixtures.init] first.
 */
object TextPdfFixtures {
    const val WIDTH = 612f
    const val HEIGHT = 792f
    // Read on use: PdfBox's standard fonts need PdfFixtures.init first.
    val REGULAR: PDFont get() = PDType1Font.TIMES_ROMAN
    val BOLD: PDFont get() = PDType1Font.TIMES_BOLD
    val ITALIC: PDFont get() = PDType1Font.TIMES_ITALIC

    /** [y] is the baseline from the page's top, as a reader sees it. */
    data class Line(
        val text: String, val y: Float, val x: Float = 72f, val size: Float = 11f, val font: PDFont = REGULAR,
        val hidden: Boolean = false,
    )

    /** A picture from ([x], [y]) at the top left, [w] by [h] points. */
    data class Picture(val x: Float, val y: Float, val w: Float, val h: Float)

    data class Page(val lines: List<Line>, val pictures: List<Picture> = emptyList())

    /** Lines of body text from [top], [leading] apart. */
    fun body(top: Float, vararg texts: String, leading: Float = 14f, x: Float = 72f, size: Float = 11f): List<Line> =
        texts.mapIndexed { i, t -> Line(t, top + i * leading, x, size) }

    fun build(dir: File, name: String, pages: List<Page>, outline: List<Pair<String, Int>> = emptyList(), title: String = ""): File {
        val file = File(dir, name)
        PDDocument().use { doc ->
            if (title.isNotEmpty()) doc.documentInformation.title = title
            for (spec in pages) {
                val page = PDPage(PDRectangle(WIDTH, HEIGHT))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { out ->
                    for (p in spec.pictures) {
                        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 90, 160)) }
                        out.drawImage(LosslessFactory.createFromImage(doc, bitmap), p.x, HEIGHT - p.y - p.h, p.w, p.h)
                    }
                    for (line in spec.lines) {
                        out.beginText()
                        out.setFont(line.font, line.size)
                        out.setRenderingMode(if (line.hidden) RenderingMode.NEITHER else RenderingMode.FILL)
                        out.newLineAtOffset(line.x, HEIGHT - line.y)
                        out.showText(line.text)
                        out.endText()
                    }
                }
            }
            if (outline.isNotEmpty()) {
                val root = PDDocumentOutline()
                for ((label, pageNumber) in outline) {
                    root.addLast(PDOutlineItem().apply {
                        this.title = label
                        destination = PDPageFitDestination().apply { page = doc.getPage(pageNumber - 1) }
                    })
                }
                doc.documentCatalog.documentOutline = root
            }
            doc.save(file)
        }
        return file
    }
}
