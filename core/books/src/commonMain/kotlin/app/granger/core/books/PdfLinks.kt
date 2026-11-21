package app.granger.core.books

import kotlinx.io.IOException
import kotlin.math.abs

/**
 * A link on a PDF page to another page of the same PDF: it sits on [page] in the box [left], [bottom], [right], [top]
 * (PDF points, the page's own space) and goes to [target]. Both pages count from 0.
 */
data class PdfLink(val page: Int, val left: Float, val bottom: Float, val right: Float, val top: Float, val target: Int)

/**
 * A PDF's links within itself, read with the phone's PDF engine (PdfBox on Android). PDFium finds a tapped link and
 * its box, but can come back without its page for some PDFs; PdfBox follows a destination given directly, by name or
 * through a GoTo action, so the reader asks it then ([target]).
 */
object PdfLinks {
    /** Every link in the PDF at [path] to one of its own pages, page by page; empty when there are none or it can't be read. */
    fun read(path: String): List<PdfLink> = try {
        BookEngines.pdf.open(path).use { doc -> if (doc.isEncrypted) emptyList() else doc.links(MAX_LINKS) }
    } catch (e: IOException) {
        emptyList()
    } catch (e: RuntimeException) {
        emptyList()
    }

    /**
     * Where the link whose box is [left], [top], [right], [bottom] (as PDFium gives it, either way up) goes: the
     * matching link nearest [nearPage], since the same box can repeat from page to page (a running "Contents" link).
     * Null when none matches to within [SLOP] points.
     */
    fun target(links: List<PdfLink>, left: Float, top: Float, right: Float, bottom: Float, nearPage: Int): Int? {
        val lo = minOf(top, bottom)
        val hi = maxOf(top, bottom)
        return links
            .filter { abs(it.left - minOf(left, right)) <= SLOP && abs(it.right - maxOf(left, right)) <= SLOP && abs(it.bottom - lo) <= SLOP && abs(it.top - hi) <= SLOP }
            .minByOrNull { abs(it.page - nearPage) }
            ?.target
    }

    private const val SLOP = 1f

    /** At most this many are kept: a PDF that is nothing but an index can hold far more, and its first are enough. */
    private const val MAX_LINKS = 20_000
}
