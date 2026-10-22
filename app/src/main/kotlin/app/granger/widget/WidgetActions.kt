package app.granger.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import app.granger.GrangerApp
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The widget's play/pause: toggles the loaded player; with nothing loaded, resumes the saved one. */
class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // Widget actions run in the app's own process: the hub is the container's (made on the main thread).
        val container = (context.applicationContext as GrangerApp).container
        withContext(Dispatchers.Main) {
            playFromWidget(container.playerHub, resumeVoice = { key, title -> container.playResumer.resume(key, title) })
        }
    }
}

/** The widget's second button: next track for music, skip back (the skip setting) for books. */
class SecondaryAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val hub = (context.applicationContext as GrangerApp).container.playerHub
        withContext(Dispatchers.Main) { secondaryFromWidget(hub) }
    }
}

/**
 * Play/pause from the widget. Nothing loaded: the voice it was reading, through [resumeVoice] (the play resumer's
 * `voice:<source key>`, Plan V2), else [PlayerHub.activate] the player [resumeMode] picks (which loads and plays
 * it), once the saved state has been read; a process the tap just started may not have read it yet.
 */
internal suspend fun playFromWidget(
    hub: PlayerHub,
    waitMs: Long = 3_000L,
    resumeVoice: suspend (key: String, title: String) -> Boolean = { _, _ -> false },
) {
    if (hub.ui.value.now != NowPlaying.Idle) {
        hub.playPause()
        return
    }
    val saved = withTimeoutOrNull(waitMs) { hub.saved.first { it.loaded } } ?: return
    val voice = saved.voice
    if (saved.lastWasVoice && voice != null && resumeVoice("voice:${voice.sourceKey}", voice.title)) return
    saved.resumeMode()?.let(hub::activate)
}

internal fun secondaryFromWidget(hub: PlayerHub) {
    when (hub.ui.value.now) {
        is NowPlaying.Music -> hub.next()
        is NowPlaying.Book -> hub.skipBack()
        is NowPlaying.Voice -> hub.previousSentence()
        NowPlaying.Idle -> Unit
    }
}
