package app.granger.widget

import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Re-renders the widget when the model changes, ignoring position-only changes: the item, play state, chapter or
 * saved state; never while the saved state is still unread with nothing loaded. Progress is drawn as of the last render, so the widget never wakes the phone every second (Review
 * Focus 6). A failed render is logged and the next change tries again.
 */
class WidgetUpdater(hub: PlayerHub, scope: CoroutineScope, render: suspend (WidgetModel) -> Unit) {
    init {
        scope.launch {
            // Nothing loaded and the saved state not read yet (a cold start): that's not "Nothing playing", so wait.
            combine(hub.ui, hub.saved) { ui, saved -> if (ui.now == NowPlaying.Idle && !saved.loaded) null else WidgetModel.of(ui, saved) }
                .filterNotNull()
                .distinctUntilChanged { old, new -> old.sameButProgress(new) }
                .collect { model ->
                    try {
                        render(model)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("Granger", "Widget update failed", e)
                    }
                }
        }
    }
}
