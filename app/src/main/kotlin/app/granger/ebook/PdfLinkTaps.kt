package app.granger.ebook

import android.os.Handler
import android.os.Looper
import com.github.barteksc.pdfviewer.link.LinkHandler
import com.github.barteksc.pdfviewer.model.LinkTapEvent
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.KeyEvent
import org.readium.r2.navigator.input.TapEvent

/**
 * A link tapped on a PDF page, as PDFium found it: the 0-based [page] it goes to (null, or out of range, when PDFium
 * couldn't follow its destination), its web [uri] if it is one, and its box on the page in PDF points, which
 * PdfBox can match to find the page when PDFium couldn't ([app.granger.core.books.PdfLinks.target]).
 */
data class PdfLinkTap(val page: Int?, val uri: String?, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * The PDF viewer's link handler, in place of its default: every link tap goes to [onLink], which the reader sets, so
 * a link within the PDF jumps through the navigator like a Contents row and a web link opens in the browser.
 * [count] goes up with each one, so [LinkAwareTaps] can tell that a tap was a link's.
 */
class PdfLinkTaps : LinkHandler {
    /** Link taps so far; read and written on the main thread, where the viewer handles taps. */
    var count: Int = 0
        private set

    /** Where each link tap goes; null drops them (the reader isn't showing this PDF). */
    @Volatile var onLink: ((PdfLinkTap) -> Unit)? = null

    override fun handleLinkEvent(event: LinkTapEvent) {
        count++
        val link = event.link
        val box = link.bounds
        onLink?.invoke(PdfLinkTap(link.destPageIdx, link.uri, box.left, box.top, box.right, box.bottom))
    }
}

/**
 * The PDF reader's tap handling ([listeners]: the edge page turns and the middle tap's chrome), held back until the
 * viewer has looked for a link under the tap. The viewer hands a tap to Readium first and checks for a link after,
 * in the same call, so without this a tap on a contents line near the edge would also turn the page (whose animation
 * then carries the page away from the link's), and one in the middle would toggle the bars. Posted to the main
 * thread, the tap runs after that check, and only when no link took it. Drags and keys pass straight through.
 */
class LinkAwareTaps(
    private val listeners: List<InputListener>,
    private val links: PdfLinkTaps,
    private val post: (Runnable) -> Unit = Handler(Looper.getMainLooper())::post,
) : InputListener {
    override fun onTap(event: TapEvent): Boolean {
        val before = links.count
        post { if (links.count == before) listeners.any { it.onTap(event) } }
        return true
    }

    override fun onDrag(event: DragEvent): Boolean = listeners.any { it.onDrag(event) }

    override fun onKey(event: KeyEvent): Boolean = listeners.any { it.onKey(event) }
}
