package app.granger.core.books

import android.graphics.Path
import android.graphics.PointF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import java.io.File
import java.io.IOException
import kotlin.math.abs

/**
 * Whether a PDF was made on a computer or scanned (Plan IA Decision 6), which decides whether it becomes an ebook.
 * A scan's text is an OCR layer: drawn invisibly (text render mode 3) or in archive.org's and Tesseract's
 * `GlyphLessFont`, over a picture of the whole page. A born-digital PDF draws its text in real fonts, visibly, with at
 * most a figure here and there. A picture book, a comic or a scan without OCR is pictures with little or no text.
 */
enum class PdfOrigin {
    BORN_DIGITAL, SCAN, IMAGES, UNKNOWN;

    companion object {
        /** Pages looked at, spread through the document. */
        private const val SAMPLES = 12

        /** A page with fewer drawn characters than this has no text to speak of. */
        private const val TEXT_PAGE_CHARS = 80

        /** An image covering this share of the page is a picture of the whole page. */
        private const val PAGE_IMAGE = 0.6f

        fun of(file: File): PdfOrigin = try {
            loadPdf(file).use(::of)
        } catch (_: IOException) {
            UNKNOWN
        } catch (_: RuntimeException) {
            UNKNOWN
        }

        fun of(doc: PDDocument): PdfOrigin {
            val count = doc.numberOfPages
            if (count == 0) return UNKNOWN
            val n = minOf(SAMPLES, count)
            val pages = (0 until n).map { it * count / n }.distinct().map { looked(doc.getPage(it)) }
            return classify(pages)
        }

        /** What one page draws: its characters by kind, and whether a picture covers it. */
        internal data class PageLook(val visible: Int, val hidden: Int, val glyphless: Int, val pageImage: Boolean)

        /**
         * BORN_DIGITAL when most pages with text draw it visibly in real fonts, over no page-sized picture; SCAN when
         * most pages with text have it hidden, in GlyphLessFont, or over a picture of the page; IMAGES when few pages
         * have text at all.
         */
        internal fun classify(pages: List<PageLook>): PdfOrigin {
            if (pages.isEmpty()) return UNKNOWN
            val withText = pages.filter { it.visible + it.hidden + it.glyphless >= TEXT_PAGE_CHARS }
            if (withText.size * 3 < pages.size) return if (pages.count { it.pageImage } * 2 >= pages.size) IMAGES else UNKNOWN
            val scanned = withText.count { p ->
                val ocr = p.hidden + p.glyphless
                ocr * 2 > p.visible + ocr || (p.pageImage && p.visible > 0)
            }
            return if (scanned * 2 > withText.size) SCAN else BORN_DIGITAL
        }

        private fun looked(page: PDPage): PageLook {
            val engine = Looker(page)
            try {
                engine.processPage(page)
            } catch (_: IOException) {
                // A page PdfBox can't fully read counts with what it saw.
            }
            return PageLook(engine.visible, engine.hidden, engine.glyphless, engine.pageImage)
        }
    }

    /** Counts a page's glyphs by how they're drawn, and notices a picture of the whole page. */
    private class Looker(page: PDPage) : PDFGraphicsStreamEngine(page) {
        var visible = 0
        var hidden = 0
        var glyphless = 0
        var pageImage = false
        private val pageArea = page.mediaBox.let { abs(it.width * it.height) }.coerceAtLeast(1f)

        override fun showGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
            when {
                font.name?.contains("GlyphLess", ignoreCase = true) == true -> glyphless++
                graphicsState.textState.renderingMode.let { it == RenderingMode.NEITHER || it == RenderingMode.NEITHER_CLIP } -> hidden++
                else -> visible++
            }
        }

        override fun drawImage(pdImage: PDImage) {
            val m = graphicsState.currentTransformationMatrix
            if (abs(m.scaleX * m.scaleY) >= pageArea * PAGE_IMAGE) pageImage = true
        }

        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) = Unit
        override fun clip(windingRule: Path.FillType) = Unit
        override fun moveTo(x: Float, y: Float) = Unit
        override fun lineTo(x: Float, y: Float) = Unit
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = Unit
        override fun getCurrentPoint(): PointF = PointF(0f, 0f)
        override fun closePath() = Unit
        override fun endPath() = Unit
        override fun strokePath() = Unit
        override fun fillPath(windingRule: Path.FillType) = Unit
        override fun fillAndStrokePath(windingRule: Path.FillType) = Unit
        override fun shadingFill(shadingName: COSName) = Unit
    }
}
