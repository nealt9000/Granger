package app.granger.core.books

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import java.io.File
import java.io.IOException
import kotlin.math.abs

/**
 * A link on a PDF page to another page of the same PDF: it sits on [page] in the box [left], [bottom], [right], [top]
 * (PDF points, the page's own space) and goes to [target]. Both pages count from 0.
 */
data class PdfLink(val page: Int, val left: Float, val bottom: Float, val right: Float, val top: Float, val target: Int)

/**
 * A PDF's links within itself, read with PdfBox. PDFium finds a tapped link and its box, but can come back without
 * its page for some PDFs; PdfBox follows a destination given directly, by name or through a GoTo action, so the
 * reader asks it then ([target]).
 */
object PdfLinks {
    /** Every link in [file] to one of its own pages, page by page; empty when there are none or it can't be read. */
    fun read(file: File): List<PdfLink> = try {
        loadPdf(file).use { doc -> if (doc.isEncrypted) emptyList() else read(doc) }
    } catch (e: IOException) {
        emptyList()
    } catch (e: RuntimeException) {
        emptyList()
    }

    private fun read(doc: PDDocument): List<PdfLink> {
        val out = ArrayList<PdfLink>()
        doc.pages.forEachIndexed { index, page ->
            val annotations = try { page.annotations } catch (_: IOException) { return@forEachIndexed }
            for (link in annotations.filterIsInstance<PDAnnotationLink>()) {
                if (out.size >= MAX_LINKS) return out
                val target = try { targetOf(doc, link) } catch (_: IOException) { null } ?: continue
                val box = link.rectangle ?: continue
                out += PdfLink(index, box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY, target)
            }
        }
        return out
    }

    /** The page [link] goes to: its own destination, else its GoTo action's; null for a web link or a page not here. */
    private fun targetOf(doc: PDDocument, link: PDAnnotationLink): Int? {
        val destination: PDDestination? = link.destination ?: (link.action as? PDActionGoTo)?.destination
        val page = when (destination) {
            is PDPageDestination -> destination
            is PDNamedDestination -> doc.documentCatalog.findNamedDestinationPage(destination)
            else -> null
        } ?: return null
        val index = page.page?.let { doc.pages.indexOf(it) } ?: page.pageNumber
        return index.takeIf { it in 0 until doc.numberOfPages }
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
