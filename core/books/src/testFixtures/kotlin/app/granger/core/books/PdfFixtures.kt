package app.granger.core.books

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File

/**
 * Builds small PDFs in a folder for tests, with PdfBox, so no PDF binaries live in git. Call [init] once per test
 * class (PdfBox-Android reads its font metrics from the app's assets).
 */
object PdfFixtures {
    const val TITLE = "The Water Book"
    const val HEAD = "THE WATER BOOK"
    const val PAGE_TWO_PHRASE = "slow sand filter removes ninety percent"

    fun init(context: Context) = PDFBoxResourceLoader.init(context)

    /** Three pages with an outline, an info dictionary, a running head and footer, and a hyphen at a line end on page 2. */
    fun text(dir: File, name: String = "water.pdf"): File = write(dir, name) { doc ->
        doc.documentInformation.apply {
            title = TITLE
            author = "A. Field"
            subject = "Water"
        }
        val pages = listOf(
            listOf(
                "Boiling",
                "Bring water to a rolling boil for one minute before drinking it.",
                "At high altitude boil it for three minutes, because water boils cooler there.",
            ),
            listOf(
                "Filters",
                "A $PAGE_TWO_PHRASE of the bacteria in the water. For exam-",
                "ple, a barrel of sand and gravel serves a family.",
            ),
            listOf(
                "Storage",
                "Store treated water in clean, covered containers away from sunlight.",
            ),
        )
        val outline = PDDocumentOutline()
        pages.forEachIndexed { i, lines ->
            val page = page(doc, lines, number = i + 1)
            outline.addLast(PDOutlineItem().apply { title = lines.first(); setDestination(page) })
        }
        doc.documentCatalog.documentOutline = outline
    }

    /** Two pages with a drawn rectangle each and no text: a scan with no text layer. */
    fun imageOnly(dir: File, name: String = "scan.pdf"): File = write(dir, name) { doc ->
        repeat(2) {
            val page = PDPage(PDRectangle.LETTER)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { s ->
                s.setNonStrokingColor(0.6f, 0.6f, 0.6f)
                s.addRect(72f, 72f, 400f, 600f)
                s.fill()
            }
        }
    }

    /** [text] with an owner password only: it opens without a password, but it is encrypted. */
    fun encrypted(dir: File, name: String = "locked.pdf"): File {
        val plain = text(dir, "plain-$name")
        val out = File(dir, name)
        PDDocument.load(plain).use { doc ->
            doc.protect(StandardProtectionPolicy("owner", "", AccessPermission()).apply { encryptionKeyLength = 128 })
            doc.save(out)
        }
        plain.delete()
        return out
    }

    /** The first 60% of [text]'s bytes. */
    fun truncated(dir: File, name: String = "broken.pdf"): File {
        val whole = text(dir, "whole-$name")
        val bytes = whole.readBytes()
        whole.delete()
        return File(dir, name).apply { writeBytes(bytes.copyOf(bytes.size * 6 / 10)) }
    }

    /** [pages] pages of distinct text, several paragraphs each, for chunking tests. */
    fun long(dir: File, pages: Int, name: String = "long.pdf"): File = write(dir, name) { doc ->
        for (n in 1..pages) {
            val lines = (1..12).map { line ->
                "Page $n line $line tells of wells, cisterns and springs, and how to keep them clean in a hard year."
            }
            page(doc, listOf("Chapter $n") + lines, number = n)
        }
    }

    private fun write(dir: File, name: String, build: (PDDocument) -> Unit): File {
        dir.mkdirs()
        val file = File(dir, name)
        PDDocument().use { doc ->
            build(doc)
            doc.save(file)
        }
        return file
    }

    /** A Letter page: [HEAD] at the top, then [lines] (the first in bold), then "Page n" at the foot. */
    private fun page(doc: PDDocument, lines: List<String>, number: Int): PDPage {
        val page = PDPage(PDRectangle.LETTER)
        doc.addPage(page)
        PDPageContentStream(doc, page).use { s ->
            fun line(text: String, font: PDType1Font, size: Float, x: Float, y: Float) {
                s.beginText()
                s.setFont(font, size)
                s.newLineAtOffset(x, y)
                s.showText(text)
                s.endText()
            }
            line(HEAD, PDType1Font.HELVETICA, 9f, 72f, 750f)
            var y = 700f
            lines.forEachIndexed { i, text ->
                line(text, if (i == 0) PDType1Font.HELVETICA_BOLD else PDType1Font.TIMES_ROMAN, if (i == 0) 16f else 11f, 72f, y)
                y -= if (i == 0) 28f else 16f
            }
            line("Page $number", PDType1Font.HELVETICA, 9f, 290f, 40f)
        }
        return page
    }
}
