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

    private fun voiceDir(root: File, name: String, config: String? = null) {
        val d = File(root, name).also { it.mkdirs() }
        File(d, "$name.onnx").writeText("x"); File(d, "tokens.txt").writeText("x"); File(d, "espeak-ng-data").mkdirs()
        if (config != null) File(d, "$name.onnx.json").writeText(config)
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
    fun aVoiceWithNamedSpeakersListsEachSpeaker() = runTest {
        val volume = tmp.newFolder("v")
        val dir = "vits-piper-en_US-granger_libritts-medium"
        voiceDir(File(volume, "voices"), dir, """{"num_speakers": 2, "speaker_id_map": {"Martin": 1, "Maggie": 0}, "audio": {}}""")
        val voices = InstalledVoices({ listOf(File(volume, "voices")) }, { emptyList() }, noFactory, noFactory)
        val list = voices.refresh()
        assertThat(list.map { it.id }).containsExactly(VoiceId.Granger(dir, 0), VoiceId.Granger(dir, 1)).inOrder()
        assertThat(list.map { it.title }).containsExactly("Granger voice (Maggie)", "Granger voice (Martin)").inOrder()
        assertThat(list.map { it.speakers }).containsExactly(2, 2)
        // A saved speaker the voice no longer has falls back to the same voice, not to another one.
        assertThat(voices.preferred(VoiceId.Granger(dir, 7))?.id).isEqualTo(VoiceId.Granger(dir, 0))
        assertThat(voices.preferred(VoiceId.Granger(dir, 1))?.id).isEqualTo(VoiceId.Granger(dir, 1))
    }

    @Test
    fun readerNumbersAreNotNamesSoTheVoiceIsListedOnce() = runTest {
        val volume = tmp.newFolder("v")
        voiceDir(File(volume, "voices"), "vits-piper-en_US-libritts-high", """{"num_speakers": 3, "speaker_id_map": {"3922": 0, "4535": 1, "14": 2}}""")
        voiceDir(File(volume, "voices"), "vits-piper-en_US-ljspeech-medium", """{"num_speakers": 1, "speaker_id_map": {}}""")
        val voices = InstalledVoices({ listOf(File(volume, "voices")) }, { emptyList() }, noFactory, noFactory)
        assertThat(voices.refresh().map { it.id }).containsExactly(
            VoiceId.Granger("vits-piper-en_US-ljspeech-medium"), VoiceId.Granger("vits-piper-en_US-libritts-high"),
        ).inOrder()
    }

    @Test
    fun voiceIdsRoundTripAndRejectJunk() {
        for (id in listOf(systemVoice.id, VoiceId.Granger("vits-piper-en_US-ljspeech-medium"), VoiceId.Granger("vits-piper-en_US-granger_libritts-medium", 1))) {
            assertThat(VoiceId.decode(id.encode())).isEqualTo(id)
        }
        // Speaker 0 is written as V1 wrote every Granger voice, so V1's saved choices still decode.
        assertThat(VoiceId.Granger("d", 0).encode()).isEqualTo("granger:d")
        assertThat(VoiceId.Granger("d", 1).encode()).isEqualTo("granger:d@1")
        assertThat(VoiceId.decode("granger:d@x")).isEqualTo(VoiceId.Granger("d@x"))
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
