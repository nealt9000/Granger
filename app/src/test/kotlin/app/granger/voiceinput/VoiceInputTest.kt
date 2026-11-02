package app.granger.voiceinput

import app.granger.core.speech.Availability
import app.granger.core.speech.DownloadOutcome
import app.granger.core.speech.RecognizerTags
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Settings → Voice input's download (Plan A1). */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInputTest {
    @Test fun choosingUkEnglishOffersItsDownloadThenTheMicComesBack() = runTest(UnconfinedTestDispatcher()) {
        val prefs = FakePrefs()
        val voice = fakeVoiceInput(backgroundScope, FakeSpeech(), prefs = prefs)
        backgroundScope.launch { voice.status.collect {} }
        prefs.setEnglishTag(RecognizerTags.ENGLISH_UK)
        val uk = voice.status.first { it.tag == "en-GB" }
        assertThat(uk.availability).isEqualTo(Availability.DOWNLOADABLE)
        assertThat(uk.micShown).isFalse()
        assertThat(uk.phoneCanListen).isTrue()
        voice.downloadCurrent()
        assertThat(voice.status.first { it.tag == "en-GB" && it.availability == Availability.READY }.micShown).isTrue()
        assertThat(voice.download.value).isEqualTo(VoiceInput.Download.Idle)
    }

    @Test fun aScheduledOrFailedDownloadSaysSo() = runTest(UnconfinedTestDispatcher()) {
        val prefs = FakePrefs()
        val speech = FakeSpeech()
        val voice = fakeVoiceInput(backgroundScope, speech, prefs = prefs)
        backgroundScope.launch { voice.status.collect {} }
        prefs.setEnglishTag(RecognizerTags.ENGLISH_UK)
        voice.status.first { it.tag == "en-GB" }
        speech.downloadOutcome = DownloadOutcome.SCHEDULED
        voice.downloadCurrent()
        assertThat(voice.download.value).isEqualTo(VoiceInput.Download.Scheduled)
        speech.downloadOutcome = DownloadOutcome.FAILED
        voice.downloadCurrent()
        assertThat(voice.download.value).isEqualTo(VoiceInput.Download.Failed)
    }
}
