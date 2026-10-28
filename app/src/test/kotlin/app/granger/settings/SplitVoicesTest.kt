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
    fun grangerVoicesAreShownAndEverySystemVoiceIsFolded() {
        val (shown, more) = splitVoices(all, chosen = granger.id)
        assertThat(shown).containsExactly(granger)
        assertThat(more).containsExactly(usA, usB, gb, au).inOrder()
        // Nothing chosen: the first voice reads, and it's a Granger one.
        assertThat(splitVoices(all, chosen = null)).isEqualTo(listOf(granger) to listOf(usA, usB, gb, au))
    }

    @Test
    fun aChosenSystemVoiceStaysOutsideTheFold() {
        val (shown, more) = splitVoices(all, chosen = au.id)
        assertThat(shown).containsExactly(granger, au).inOrder()
        assertThat(more).containsExactly(usA, usB, gb).inOrder()
    }

    @Test
    fun withOnlySystemVoicesTheOneThatReadsIsShown() {
        val system = listOf(usA, usB, gb, au)
        assertThat(splitVoices(system, chosen = null)).isEqualTo(listOf(usA) to listOf(usB, gb, au))
        // A chosen voice that's gone: the first one reads instead.
        assertThat(splitVoices(system, chosen = VoiceId.System("tts", "gone"))).isEqualTo(listOf(usA) to listOf(usB, gb, au))
        assertThat(splitVoices(listOf(granger), null)).isEqualTo(listOf(granger) to emptyList<Voice>())
        assertThat(splitVoices(emptyList(), null)).isEqualTo(emptyList<Voice>() to emptyList<Voice>())
    }
}
