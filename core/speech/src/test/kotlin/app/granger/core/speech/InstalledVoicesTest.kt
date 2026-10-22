package app.granger.core.speech

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstalledVoicesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val systemVoice = Voice(VoiceId.System("com.google.android.tts", "en-us-x-iom-local"), "en-us-x-iom-local (en-US)", "en-US")
    private val noFactory = object : SpeechFactory { override suspend fun open(voice: Voice): Speech = throw SpeechException("not in this test") }

    private fun voiceDir(root: File, name: String) {
        val d = File(root, name).also { it.mkdirs() }
        File(d, "$name.onnx").writeText("x"); File(d, "tokens.txt").writeText("x"); File(d, "espeak-ng-data").mkdirs()
    }

    @Test
    fun installedVoicesListsGrangerVoicesBeforeSystemOnes() = runTest {
        val volumeA = tmp.newFolder("a"); val volumeB = tmp.newFolder("b")
        voiceDir(File(volumeA, "voices"), "vits-piper-en_US-ljspeech-medium")
        voiceDir(File(volumeB, "voices"), "vits-piper-en_US-ljspeech-medium")   // the same voice on two volumes counts once
        val voices = InstalledVoices({ listOf(File(volumeA, "voices"), File(volumeB, "voices"), File(tmp.root, "missing")) }, { listOf(systemVoice) }, noFactory, noFactory)
        assertThat(voices.voices.value).isEmpty()
        val list = voices.refresh()
        assertThat(list.map { it.id }).containsExactly(VoiceId.Granger("vits-piper-en_US-ljspeech-medium"), systemVoice.id).inOrder()
        assertThat(list.first().title).isEqualTo("Granger voice (ljspeech)")
        assertThat(voices.voices.value).isEqualTo(list)
    }

    @Test
    fun grangerVoicesAreRankedCatalogThenPiperThenTheRest() = runTest {
        val volume = tmp.newFolder("v")
        voiceDir(File(volume, "voices"), "kokoro-int8-multi-lang-v1_1")       // first by name, last by rank
        voiceDir(File(volume, "voices"), "vits-piper-en_US-libritts_r-medium")
        voiceDir(File(volume, "voices"), "vits-piper-en_US-ljspeech-medium")
        val voices = InstalledVoices({ listOf(File(volume, "voices")) }, { listOf(systemVoice) }, noFactory, noFactory)
        voices.refresh()
        assertThat(voices.voices.value.map { (it.id as? VoiceId.Granger)?.dir ?: "system" })
            .containsExactly("vits-piper-en_US-ljspeech-medium", "vits-piper-en_US-libritts_r-medium", "kokoro-int8-multi-lang-v1_1", "system").inOrder()
        assertThat(voices.preferred(null)?.id).isEqualTo(VoiceId.Granger("vits-piper-en_US-ljspeech-medium"))
    }

    @Test
    fun preferredIsTheWantedVoiceElseTheFirst() = runTest {
        val voices = InstalledVoices({ emptyList() }, { listOf(systemVoice) }, noFactory, noFactory)
        voices.refresh()
        assertThat(voices.preferred(VoiceId.Granger("gone"))).isEqualTo(systemVoice)
        assertThat(voices.preferred(systemVoice.id)).isEqualTo(systemVoice)
        assertThat(voices.preferred(null)).isEqualTo(systemVoice)
    }

    @Test
    fun aSystemEngineThatFailsLeavesTheGrangerVoices() = runTest {
        val volume = tmp.newFolder("v")
        voiceDir(File(volume, "voices"), "vits-piper-en_US-ljspeech-medium")
        val voices = InstalledVoices({ listOf(File(volume, "voices")) }, { throw SpeechException("no engine") }, noFactory, noFactory)
        assertThat(voices.refresh().map { it.id }).containsExactly(VoiceId.Granger("vits-piper-en_US-ljspeech-medium"))
    }

    @Test
    fun voiceIdsRoundTripAndRejectJunk() {
        for (id in listOf(systemVoice.id, VoiceId.Granger("vits-piper-en_US-ljspeech-medium"))) assertThat(VoiceId.decode(id.encode())).isEqualTo(id)
        assertThat(VoiceId.decode("system:only-engine")).isNull()
        assertThat(VoiceId.decode("granger:")).isNull()
        assertThat(VoiceId.decode("kokoro:x")).isNull()
    }

    @Test
    fun shortNamesComeFromTheDirectoryName() {
        assertThat(SherpaSpeech.shortName("vits-piper-en_US-ljspeech-medium")).isEqualTo("ljspeech")
        assertThat(SherpaSpeech.shortName("vits-piper-en_GB-alan-low")).isEqualTo("alan")
        assertThat(SherpaSpeech.shortName("kokoro-en-v0_19")).isEqualTo("kokoro-en-v0_19")
    }
}
