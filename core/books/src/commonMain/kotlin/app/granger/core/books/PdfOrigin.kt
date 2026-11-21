package app.granger.core.books

import kotlinx.io.IOException

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

        /** The PDF at [path], read with the phone's PDF engine. */
        fun of(path: String): PdfOrigin = try {
            BookEngines.pdf.open(path).use(::of)
        } catch (_: IOException) {
            UNKNOWN
        } catch (_: RuntimeException) {
            UNKNOWN
        }

        fun of(doc: PdfFile): PdfOrigin {
            val count = doc.pageCount
            if (count == 0) return UNKNOWN
            val n = minOf(SAMPLES, count)
            val pages = (0 until n).map { it * count / n }.distinct().map { doc.look(it) }
            return classify(pages)
        }

        /**
         * What one page draws: its characters by kind ([visible]ly in a real font, [hidden] (text render mode 3, or 7),
         * or in a [glyphless] OCR font), and whether a picture covers 60% of it or more ([pageImage]). A page the
         * engine can't fully read counts with what it saw.
         */
        data class PageLook(val visible: Int, val hidden: Int, val glyphless: Int, val pageImage: Boolean)

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
    }
}
