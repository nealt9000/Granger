package app.granger.core.books

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Splits a PDF's text layer into search-sized chunks keyed by page: `href = "page/<n>"` (1-based), [TextChunk.ordinal]
 * within the page, and [TextChunk.progression] within the page's text. A chunk never spans pages.
 *
 * Output is deterministic, and a page's chunks depend only on that page and the two pages either side (for running
 * heads), so the index can re-extract one chunk's text at query time without reading the whole book.
 */
object PdfText {
    /** Empty for a missing, password-locked or corrupt file, or an href that names no page. */
    fun chunks(file: File, href: String): List<TextChunk> = open(file)?.use { it.chunks(href) }.orEmpty()

    /** Every page's chunks, in order. */
    fun all(file: File): Sequence<TextChunk> = sequence {
        val reader = open(file) ?: return@sequence
        reader.use { for (n in 1..it.pageCount) yieldAll(it.chunks(href(n))) }
    }

    /** True when the first 20 pages hold 200 or more characters of text; false for a scan with no text layer. */
    fun hasTextLayer(file: File): Boolean = open(file)?.use { it.hasTextLayer() } ?: false

    /** An owner-password PDF opens like any other (see [PdfInspector]); one that needs a password to open doesn't. */
    fun open(file: File): Reader? = try {
        Reader(loadPdf(file))
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    fun href(page: Int) = "page/$page"

    /** The 1-based page [href] names, or null. */
    fun pageOf(href: String): Int? = href.removePrefix("page/").takeIf { it != href }?.toIntOrNull()

    /** One open PDF; caches page text, so several chunks from one book read each page once. */
    class Reader internal constructor(private val doc: PDDocument) : Closeable {
        val pageCount: Int get() = doc.numberOfPages
        private val lines = HashMap<Int, List<String>>()

        fun chunks(href: String): List<TextChunk> {
            val page = pageOf(href)?.takeIf { it in 1..pageCount } ?: return emptyList()
            return try {
                chunked(href, windows(paragraphs(withoutRunningHeads(page)).asSequence()))
            } catch (_: IOException) {
                emptyList()
            } catch (_: RuntimeException) {
                emptyList()
            }
        }

        fun hasTextLayer(): Boolean {
            var chars = 0
            for (n in 1..minOf(pageCount, TEXT_LAYER_PAGES)) {
                chars += pageLines(n).sumOf { line -> line.count { !it.isWhitespace() } }
                if (chars >= TEXT_LAYER_CHARS) return true
            }
            return false
        }

        override fun close() = doc.close()

        /** [page]'s lines, less any of its first or last two that also sit at an edge of a page within two pages. */
        private fun withoutRunningHeads(page: Int): List<String> {
            val own = pageLines(page)
            val nearby = ((page - NEIGHBOURS)..(page + NEIGHBOURS))
                .filter { it != page && it in 1..pageCount }
                .flatMap { edges(pageLines(it)) }
                .toSet()
            if (nearby.isEmpty()) return own
            val edgeIndexes = edgeIndexes(own)
            return own.filterIndexed { i, line -> i !in edgeIndexes || normalise(line) !in nearby }
        }

        private fun pageLines(page: Int): List<String> = lines.getOrPut(page) {
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                startPage = page
                endPage = page
                lineSeparator = "\n"
                // PdfBox's paragraph guess marks nearly every line; blank lines in the page's own text split instead.
                paragraphStart = ""
                paragraphEnd = ""
            }
            stripper.getText(doc).split('\n').map { it.trim() }
        }

        private fun edges(lines: List<String>): List<String> = edgeIndexes(lines).map { normalise(lines[it]) }

        private fun edgeIndexes(lines: List<String>): Set<Int> {
            val text = lines.indices.filter { lines[it].isNotEmpty() }
            return (text.take(EDGE_LINES) + text.takeLast(EDGE_LINES)).toSet()
        }
    }

    /**
     * Lines into paragraphs (split at blank lines), with a hyphen at a line end joined to a lowercase next line.
     * archive.org's OCR writes that hyphen as `¬`, and some PDFs use a soft or Unicode hyphen.
     */
    private fun paragraphs(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) out += current.toString()
            current.clear()
        }
        for (line in lines) {
            if (line.isEmpty()) {
                flush()
                continue
            }
            val hyphenated = current.length > 1 && current.last() in HYPHENS && current[current.length - 2].isLetter() &&
                line.first().isLowerCase()
            when {
                current.isEmpty() -> current.append(line)
                hyphenated -> current.deleteCharAt(current.length - 1).append(line)
                else -> current.append(' ').append(line)
            }
        }
        flush()
        return out
    }

    /** Digits become `#`, so "Page 12" and "Page 13" compare equal. */
    private fun normalise(line: String) = line.replace(DIGITS, "#").lowercase()

    private val DIGITS = Regex("\\d+")
    private val HYPHENS = setOf('-', '\u00ac', '\u00ad', '\u2010')
    private const val EDGE_LINES = 2
    private const val NEIGHBOURS = 2
    private const val TEXT_LAYER_PAGES = 20
    private const val TEXT_LAYER_CHARS = 200
}
