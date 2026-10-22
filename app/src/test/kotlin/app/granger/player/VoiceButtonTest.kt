package app.granger.player

import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerUi
import app.granger.core.speech.VoiceId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoiceButtonTest {
    private fun voice(key: String, playing: Boolean, ended: Boolean = false) = PlayerUi(
        NowPlaying.Voice(key, "Hamlet", "Act I", "", 0, 1f, VoiceId.Granger("v"), ended = ended),
        playing = playing, positionMs = 0L, durationMs = 0L, sleepRemainingMs = null,
    )

    @Test fun theButtonIsIdleUnlessTheVoiceIsLoadedOnThisSource() {
        assertThat(VoiceButton.of(PlayerUi.IDLE, "book:b")).isEqualTo(VoiceButton.IDLE)
        assertThat(VoiceButton.of(voice("book:other", playing = true), "book:b")).isEqualTo(VoiceButton.IDLE)
        assertThat(VoiceButton.of(voice("book:b", playing = true), null)).isEqualTo(VoiceButton.IDLE)
        assertThat(VoiceButton.of(voice("book:b", playing = true), "book:b")).isEqualTo(VoiceButton.READING)
        assertThat(VoiceButton.of(voice("book:b", playing = false), "book:b")).isEqualTo(VoiceButton.PAUSED)
    }

    @Test fun aVoiceThatRanOutOffersReadAloudAgain() {
        assertThat(VoiceButton.of(voice("pack:p/a", playing = false, ended = true), "pack:p/a")).isEqualTo(VoiceButton.IDLE)
    }
}
