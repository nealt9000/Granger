package app.granger.voiceinput

import app.granger.R
import app.granger.UiText
import app.granger.core.speech.Availability
import app.granger.core.speech.DictationEvent
import app.granger.core.speech.DictationFailure
import app.granger.core.speech.DownloadOutcome
import app.granger.core.speech.RecognizerLanguages
import app.granger.core.speech.RecognizerTags
import app.granger.core.speech.SpeechInput
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DictationTest {
    private class Setup(val speech: FakeSpeech, val language: MutableStateFlow<String>, val prefs: FakePrefs, val voice: VoiceInput, var readAloudStops: Int = 0)

    private fun TestScope.setup(speech: FakeSpeech = FakeSpeech(), language: String = "en"): Setup {
        val lang = MutableStateFlow(language)
        val prefs = FakePrefs()
        lateinit var s: Setup
        val voice = VoiceInput(speech, prefs, lang, stopReadAloud = { s.readAloudStops++ }, scope = backgroundScope)
        s = Setup(speech, lang, prefs, voice)
        backgroundScope.launch { voice.status.collect {} }
        runCurrent()
        return s
    }

    @Test fun theMicListensInTheAppLanguageAndHidesWithout() = runTest(UnconfinedTestDispatcher()) {
        val s = setup()
        assertThat(s.voice.status.value).isEqualTo(VoiceInputStatus("en-US", Availability.READY, phoneCanListen = true))
        s.prefs.setEnglishTag(RecognizerTags.ENGLISH_UK)
        assertThat(s.voice.status.first { it.tag == "en-GB" }.micShown).isFalse() // downloadable only
        s.language.value = "es"
        assertThat(s.voice.status.first { it.tag == "es-ES" }.micShown).isTrue()
        s.language.value = "sw"
        assertThat(s.voice.status.first { it.tag == null }.micShown).isFalse()
    }

    @Test fun aPhoneWithoutOnDeviceRecognitionHasNoMic() = runTest(UnconfinedTestDispatcher()) {
        val s = setup(FakeSpeech(languages = null))
        assertThat(s.voice.status.value.micShown).isFalse()
    }

    @Test fun partialsThenTheFinal() = runTest(UnconfinedTestDispatcher()) {
        val s = setup()
        val dictation = Dictation(s.voice, backgroundScope)
        val texts = mutableListOf<String>()
        var final: String? = null
        dictation.start(onText = { texts += it }) { final = it }
        assertThat(s.readAloudStops).isEqualTo(1)
        assertThat(dictation.state.value).isEqualTo(Dictation.State.Listening())
        s.speech.send(DictationEvent.Listening, DictationEvent.Partial("What did"), DictationEvent.Partial("What did Kant"))
        assertThat(dictation.state.value).isEqualTo(Dictation.State.Listening("What did Kant"))
        s.speech.end(DictationEvent.EndOfSpeech, DictationEvent.Final("What did Kant add to philosophy"))
        assertThat(texts).containsExactly("What did", "What did Kant").inOrder()
        assertThat(final).isEqualTo("What did Kant add to philosophy")
        assertThat(dictation.state.value).isEqualTo(Dictation.State.Idle)
        assertThat(s.speech.tags).containsExactly("en-US")
    }

    @Test fun aNoMatchShowsThenClears() = runTest(UnconfinedTestDispatcher()) {
        val s = setup()
        val dictation = Dictation(s.voice, backgroundScope)
        var final: String? = null
        dictation.start { final = it }
        s.speech.end(DictationEvent.Failed(DictationFailure.NO_MATCH))
        assertThat(dictation.state.value).isEqualTo(Dictation.State.Failed(UiText.Res(R.string.voice_input_no_match)))
        advanceTimeBy(3_001)
        assertThat(dictation.state.value).isEqualTo(Dictation.State.Idle)
        assertThat(final).isNull()
    }

    @Test fun stopAsksForTheFinalAndCancelDropsTheTake() = runTest(UnconfinedTestDispatcher()) {
        val s = setup()
        val dictation = Dictation(s.voice, backgroundScope)
        var final: String? = null
        dictation.start { final = it }
        dictation.stop()
        assertThat(s.speech.stops).isEqualTo(1)
        dictation.cancel()
        assertThat(s.speech.cancelled).isTrue()
        assertThat(dictation.state.value).isEqualTo(Dictation.State.Idle)
        assertThat(final).isNull()
    }

    @Test fun oneTakeAtATimeAndNoneWithoutAMic() = runTest(UnconfinedTestDispatcher()) {
        val s = setup()
        val dictation = Dictation(s.voice, backgroundScope)
        dictation.start {}
        dictation.start {}
        assertThat(s.speech.tags).hasSize(1)
        dictation.cancel()
        s.language.value = "sw"
        s.voice.status.first { it.tag == null }
        dictation.start {}
        assertThat(s.speech.tags).hasSize(1)
    }

    @Test fun everyFailureHasAMessage() {
        DictationFailure.entries.forEach { assertThat(Dictation.messageFor(it)).isNotEqualTo(0) }
    }
}
