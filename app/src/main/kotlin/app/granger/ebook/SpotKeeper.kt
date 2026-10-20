package app.granger.ebook

import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the book on the text being read when its page is resized (the toolbar shown or hidden). Readium keeps only
 * the page number across a resize, and the re-paginated page holds different text. So the spot on screen before
 * the first size change is held, and once the size has stayed put for [settleMs] (Readium has laid the chapter out
 * again) [restore] goes back to it. Changes in quick succession restore once, to the spot before the first.
 */
class SpotKeeper<T : Any>(
    private val scope: CoroutineScope,
    private val settleMs: Long = SETTLE_MS,
    private val restore: (T) -> Unit,
) {
    private var size: IntSize? = null
    private var held: T? = null
    private var settling: Job? = null

    /** The page was laid out at [size]; [spot] is where the reader is now (read only when the size changed). */
    fun onSize(size: IntSize, spot: () -> T?) {
        val before = this.size
        this.size = size
        if (before == null || before == size) return
        if (held == null) held = spot() ?: return
        settling?.cancel()
        settling = scope.launch {
            delay(settleMs)
            held?.let(restore)
            held = null
        }
    }

    companion object {
        /** Long enough for Readium to re-paginate after the bars' one layout change. */
        const val SETTLE_MS = 300L
    }
}
