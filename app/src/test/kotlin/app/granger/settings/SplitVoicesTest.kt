package app.granger.settings

import app.granger.core.speech.Voice
import app.granger.core.speech.VoiceId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SplitVoicesTest {
    private val granger = Voice(VoiceId.Granger("vits-piper-en_US-ljspeech-medium"), "Granger voice (ljspeech)", "en-US")
    private val usA = Voice(VoiceId.System("tts", "en-us-x-iob-local"), "System voice iob (en-US)", "en-US")
    private val usB = Voice(VoiceId.System("tts", "en-us-x-iom-local"), "System voice iom (en-US)", "en-US")
    private val gb = Voice(VoiceId.System("tts", "en-gb-x-gba-local"), "System voice gba (en-GB)", "en-GB")
    private val au = Voice(VoiceId.System("tts", "en-au-x-aua-local"), "System voice aua (en-AU)", "en-AU")
    private val all = listOf(granger, usA, usB, gb, au)

    @Test
    fun grangerVoicesAndThePhonesRegionAreShownTheRestFolded() {
        val (shown, more) = splitVoices(all, chosen = null, deviceTag = "en-US")
        assertThat(shown).containsExactly(granger, usA, usB).inOrder()
        assertThat(more).containsExactly(gb, au).inOrder()
    }

    @Test
    fun theChosenVoiceIsAlwaysShown() {
        val (shown, more) = splitVoices(all, chosen = au.id, deviceTag = "en-US")
        assertThat(shown).containsExactly(granger, usA, usB, au).inOrder()
        assertThat(more).containsExactly(gb)
    }

    @Test
    fun aPhoneOutsideTheEnglishRegionsStillSeesOneSystemVoice() {
        val (shown, more) = splitVoices(all, chosen = null, deviceTag = "de-DE")
        assertThat(shown).containsExactly(granger, usA).inOrder()
        assertThat(more).containsExactly(usB, gb, au).inOrder()
        assertThat(splitVoices(listOf(granger), null, "de-DE")).isEqualTo(listOf(granger) to emptyList<Voice>())
    }
}
