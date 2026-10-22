package app.granger.player

import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerUi

/**
 * What a reader's voice button does for its own page or book (Plan V2): start the voice, pause it, or resume it.
 * Stop is a long press or a menu entry, so that a tap never throws the place away (V1's button was stop, and was
 * pressed as pause).
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
