package app.granger.core.books

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDestinationNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
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

    /**
     * Three pages with an outline, an info dictionary, a running head and footer, a hyphen at a line end on page 2,
     * and archive.org OCR's `¬` line-end hyphen on page 3.
     */
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
                "Store treated water in clean, covered con\u00ac",
                "tainers away from sunlight.",
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

    /**
     * Four pages, as a contents page links to its chapters, each link made a different way: page 1 is "Contents",
     * with links (at [LINK_RECTS]) to page 2 by a direct destination, to page 3 by a GoTo action, and to page 4 by a
     * named destination ("storage", in the catalog's name tree). The outline nests the chapters under "Water" and
     * reaches them the same three ways.
     */
    fun linked(dir: File, name: String = "linked.pdf"): File = write(dir, name) { doc ->
        val pages = listOf("Contents", "Boiling", "Filters", "Storage").mapIndexed { i, head -> page(doc, listOf(head), number = i + 1) }
        val names = PDDestinationNameTreeNode().apply { names = mapOf("storage" to fit(pages[3])) }
        doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply { dests = names }
        fun direct() = fit(pages[1])
        fun action() = PDActionGoTo().apply { destination = fit(pages[2]) }
        fun named() = PDNamedDestination("storage")
        pages[0].annotations = LINK_RECTS.mapIndexed { i, r ->
            PDAnnotationLink().apply {
                rectangle = PDRectangle(r[0], r[1], r[2] - r[0], r[3] - r[1])
                when (i) {
                    0 -> destination = direct()
                    1 -> action = action()
                    else -> destination = named()
                }
            }
        }
        val water = PDOutlineItem().apply { title = "Water"; setDestination(fit(pages[0])) }
        water.addLast(PDOutlineItem().apply { title = "Boiling"; destination = direct() })
        water.addLast(PDOutlineItem().apply { title = "Filters"; action = action() })
        water.addLast(PDOutlineItem().apply { title = "Storage"; destination = named() })
        doc.documentCatalog.documentOutline = PDDocumentOutline().apply { addLast(water) }
    }

    /** [linked]'s three links on page 1, each `[left, bottom, right, top]` in PDF points. */
    val LINK_RECTS = listOf(floatArrayOf(72f, 600f, 300f, 620f), floatArrayOf(72f, 570f, 300f, 590f), floatArrayOf(72f, 540f, 300f, 560f))

    private fun fit(page: PDPage) = PDPageFitDestination().apply { this.page = page }

    /** One blank page whose info dictionary's Title is [title], as junk as its maker left it. */
    fun titled(dir: File, title: String, name: String = "titled.pdf"): File = write(dir, name) { doc ->
        doc.addPage(PDPage(PDRectangle.LETTER))
        doc.documentInformation.title = title
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

    /** The first 300 bytes of [text]: the header and little else (PdfBox recovers a file cut much later). */
    fun truncated(dir: File, name: String = "broken.pdf"): File {
        val whole = text(dir, "whole-$name")
        val bytes = whole.readBytes()
        whole.delete()
        return File(dir, name).apply { writeBytes(bytes.copyOf(minOf(300, bytes.size))) }
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
