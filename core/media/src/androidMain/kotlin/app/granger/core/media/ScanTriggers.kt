package app.granger.core.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * When phone scans run: once at app start, and [debounceMs] after the last of a burst of media-change
 * notifications (a copy of an album fires one per file). [scan] handles its own errors.
 */
class ScanTriggers(
    private val scope: CoroutineScope,
    private val debounceMs: Long = 2_000L,
    private val scan: suspend () -> Unit,
) {
    private var pending: Job? = null

    fun appStarted() {
        scope.launch { scan() }
    }

    @Synchronized
    fun mediaChanged() {
        pending?.cancel()
        // The scan runs outside the debounce job, so a later change never cancels a scan already under way.
        pending = scope.launch {
            delay(debounceMs)
            scope.launch { scan() }
        }
    }
}
