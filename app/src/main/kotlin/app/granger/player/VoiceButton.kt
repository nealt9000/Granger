package app.granger.player

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import app.granger.R
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerUi

/**
 * What a reader's Listen button does for its own page or book (Plan V2): start the voice, pause it, or resume it.
 * Stop is its own button beside it while the voice is loaded here (Plan U2 Decision 6), so a tap never throws the
 * place away (V1's button was stop, and was pressed as pause).
 */
enum class VoiceButton {
    IDLE, READING, PAUSED;

    companion object {
        /** The button for [sourceKey] (`pack:…`, `book:…`): IDLE unless the voice is loaded on that source and hasn't run out. */
        fun of(ui: PlayerUi, sourceKey: String?): VoiceButton {
            val voice = ui.now as? NowPlaying.Voice ?: return IDLE
            if (sourceKey == null || voice.sourceKey != sourceKey || voice.ended) return IDLE
            return if (ui.playing) READING else PAUSED
        }
    }
}

object ListenTags {
    const val LISTEN = "listen_button"
    const val STOP = "listen_stop"
}

/** The Listen button's glyph and what TalkBack reads: headphones "Listen", then "Pause" or "Play". */
internal fun VoiceButton.iconAndLabel(): Pair<Int, String> = when (this) {
    VoiceButton.IDLE -> R.drawable.ic_headphones to "Listen"
    VoiceButton.READING -> R.drawable.ic_pause to "Pause"
    VoiceButton.PAUSED -> R.drawable.ic_play to "Play"
}

/**
 * The one Listen control (Plan U2 Decision 6), for the wiki reader's bar, the e-book bar and Ask's answers: headphones
 * to start [onListen]; while [voice] is this text's, pause or play in the accent ([onPauseResume]) and a separate Stop
 * ([onStop]). Two buttons side by side in the caller's row, no long press.
 */
@Composable
fun ListenButtons(voice: VoiceButton, onListen: () -> Unit, onPauseResume: () -> Unit, onStop: () -> Unit) {
    val (icon, label) = voice.iconAndLabel()
    IconButton(
        onClick = { if (voice == VoiceButton.IDLE) onListen() else onPauseResume() },
        modifier = Modifier.testTag(ListenTags.LISTEN),
    ) {
        if (voice == VoiceButton.IDLE) Icon(painterResource(icon), contentDescription = label)
        else Icon(painterResource(icon), contentDescription = label, tint = MaterialTheme.colorScheme.primary)
    }
    if (voice != VoiceButton.IDLE) {
        IconButton(onClick = onStop, modifier = Modifier.testTag(ListenTags.STOP)) {
            Icon(painterResource(R.drawable.ic_stop), contentDescription = "Stop")
        }
    }
}
