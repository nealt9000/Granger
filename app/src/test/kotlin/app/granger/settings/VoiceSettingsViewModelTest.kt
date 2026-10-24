package app.granger.settings

import app.granger.FakeCatalogSource
import app.granger.FakeDownloads
import app.granger.FakeVolumes
import app.granger.MainDispatcherRule
import app.granger.core.catalog.Catalog
import app.granger.core.catalog.VoiceEntry
import app.granger.core.downloads.DownloadState
import app.granger.core.speech.InstalledVoices
import app.granger.core.speech.Speech
import app.granger.core.speech.SpeechException
import app.granger.core.speech.SpeechFactory
import app.granger.core.speech.Voice
import app.granger.core.speech.VoiceId
import app.granger.record
import app.granger.sources.DownloadPrefs
import app.granger.volume
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceSettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private class FakeVoicePrefs : VoicePrefs {
        override val voiceId = MutableStateFlow<VoiceId?>(null)
        override val speed = MutableStateFlow(1f)
        override val markSentence = MutableStateFlow(true)
        override val speakAnswers = MutableStateFlow(false)
        override fun setVoice(id: VoiceId?) { voiceId.value = id }
        override fun setSpeed(speed: Float) { this.speed.value = speed }
        override fun setMarkSentence(on: Boolean) { markSentence.value = on }
        override fun setSpeakAnswers(on: Boolean) { speakAnswers.value = on }
    }

    private val entry = VoiceEntry(
        id = "vits-piper-en_US-ljspeech-medium", title = "Granger voice (ljspeech)", engine = "piper", language = "en-US",
        sizeBytes = 67_456_670, sha256 = "a".repeat(64), url = "https://github.com/nealt9000/Granger/releases/download/voices-v1/v.zip",
        license = "Public domain",
    )
    private val systemVoice = Voice(VoiceId.System("com.google.android.tts", "en-us-x-iom-local"), "en-us-x-iom-local (en-US)", "en-US")
    private val noFactory = object : SpeechFactory { override suspend fun open(voice: Voice): Speech = throw SpeechException("not here") }
    private val prefs = FakeVoicePrefs()
    private val downloads = FakeDownloads()
    private val catalog = FakeCatalogSource(Catalog(1, "2026-10-21T00:00:00Z", voices = listOf(entry)))
    private val downloadPrefs = object : DownloadPrefs {
        override var wifiOnly = true
        override var volumePath: String? = null
        override var askBeforeLarge = true
    }

    private fun voiceDir(root: File, id: String) {
        val d = File(root, "voices/$id").also { it.mkdirs() }
        File(d, "$id.onnx").writeText("x"); File(d, "tokens.txt").writeText("x"); File(d, "espeak-ng-data").mkdirs()
    }

    @Test fun listsTheInstalledVoicesAndTheCatalogsAndGetsAndRemoves() = runTest(main.dispatcher) {
        val root = tmp.newFolder("internal")
        voiceDir(root, entry.id)
        val installed = InstalledVoices({ listOf(File(root, "voices")) }, { listOf(systemVoice) }, noFactory, noFactory, main.dispatcher)
        val vm = VoiceSettingsViewModel(prefs, installed, catalog, downloads, FakeVolumes(listOf(volume(root.path, "Internal storage"))), downloadPrefs, this, main.dispatcher)
        backgroundScope.launch { vm.ui.collect {} }
        testScheduler.advanceUntilIdle()

        var ui = vm.ui.value
        assertThat(ui.voices.map { it.id }).containsExactly(VoiceId.Granger(entry.id), systemVoice.id).inOrder()
        assertThat(ui.chosen).isNull()
        assertThat(ui.catalog.single().installed).isFalse()      // on disk by hand, but not in the ledger

        vm.get(entry)
        testScheduler.advanceUntilIdle()
        assertThat(downloads.enqueued).containsExactly(entry.id to root)

        downloads.list.value = listOf(record(entry.id, DownloadState.RUNNING, sha256 = entry.sha256, kind = app.granger.core.downloads.ItemKind.VOICE, sizeBytes = 100, bytesDone = 25))
        testScheduler.advanceUntilIdle()
        ui = vm.ui.value
        assertThat(ui.catalog.single().progress).isEqualTo(0.25f)

        downloads.list.value = listOf(record(entry.id, DownloadState.INSTALLED, sha256 = entry.sha256, kind = app.granger.core.downloads.ItemKind.VOICE))
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.catalog.single().installed).isTrue()

        vm.choose(VoiceId.Granger(entry.id))
        assertThat(prefs.voiceId.value).isEqualTo(VoiceId.Granger(entry.id))
        vm.setSpeed(1.3f)
        vm.setMarkSentence(false)
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.speed).isEqualTo(1.3f)
        assertThat(vm.ui.value.markSentence).isFalse()

        vm.remove(entry)
        testScheduler.advanceUntilIdle()
        assertThat(downloads.calls).contains("remove:${entry.id}")
        assertThat(File(root, "voices/${entry.id}").exists()).isFalse()
        assertThat(prefs.voiceId.value).isNull()                 // the chosen voice fell back
        assertThat(vm.ui.value.voices.map { it.id }).containsExactly(systemVoice.id)
    }

    @Test fun withNothingInstalledTheListIsEmptyAndGetNeedsAVolume() = runTest(main.dispatcher) {
        val installed = InstalledVoices({ emptyList() }, { emptyList() }, noFactory, noFactory, main.dispatcher)
        val vm = VoiceSettingsViewModel(prefs, installed, catalog, downloads, FakeVolumes(emptyList()), downloadPrefs, this, main.dispatcher)
        backgroundScope.launch { vm.ui.collect {} }
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.voices).isEmpty()
        vm.get(entry)
        testScheduler.advanceUntilIdle()
        assertThat(downloads.enqueued).isEmpty()
        assertThat(vm.ui.value.notice).isEqualTo("No storage to download to.")
    }
}
