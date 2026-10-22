package app.granger.core.speech

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SystemSpeechTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A system engine that writes 100 ms of 24 kHz audio per request on its own thread, like the real one. */
    private class FakeTts(private val fail: Boolean = false) : TtsEngine {
        override val engineName = "com.example.tts"
        var voice: String? = null
        var rate = 1f
        val spoken = mutableListOf<String>()
        var stopped = 0
        var shutdowns = 0
        override fun voices() = listOf(
            TtsVoice("en-us-x-iom-local", "en-US", offline = true, quality = 400),
            TtsVoice("en-us-x-net", "en-US", offline = false, quality = 500),
            TtsVoice("fr-fr-x-local", "fr-FR", offline = true, quality = 400),
            TtsVoice("en-gb-x-gba-local", "en-GB", offline = true, quality = 300),
        )
        override fun setVoice(name: String) = voices().any { it.name == name }.also { if (it) voice = name }
        override fun setSpeechRate(rate: Float) { this.rate = rate }
        override fun synthesizeToFile(text: String, file: File, utteranceId: String, onDone: () -> Unit, onError: (Int) -> Unit): Boolean {
            spoken += text
            thread {
                if (fail) onError(-3) else { Wav.write(Pcm(FloatArray(2400) { 0.25f }, 24_000), file); onDone() }
            }
            return true
        }
        override fun stop() { stopped++ }
        override fun shutdown() { shutdowns++ }
    }

    @Test
    fun systemSpeechSynthesizesOneSentenceToPcm() = runTest {
        val tts = FakeTts()
        val factory = SystemSpeech.Factory({ tts }, tmp.newFolder("scratch"))
        val voice = factory.voices().first()
        val speech = factory.open(voice)
        val chunks = speech.synthesize("Hello there.", speed = 1.3f).toList()
        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].sampleRate).isEqualTo(24_000)
        assertThat(chunks[0].durationMs).isEqualTo(100)
        assertThat(tts.spoken).containsExactly("Hello there.")
        assertThat(tts.rate).isEqualTo(1.3f)
        assertThat(tts.voice).isEqualTo("en-us-x-iom-local")
        assertThat(tmp.root.walk().filter { it.isFile }.toList()).isEmpty()   // the scratch file is gone
    }

    @Test
    fun voicesAreOfflineEnglishOnlyBestQualityFirst() = runTest {
        val factory = SystemSpeech.Factory({ FakeTts() }, tmp.root)
        assertThat(factory.voices().map { (it.id as VoiceId.System).name }).containsExactly("en-us-x-iom-local", "en-gb-x-gba-local").inOrder()
        assertThat(factory.voices().first().id).isEqualTo(VoiceId.System("com.example.tts", "en-us-x-iom-local"))
    }

    @Test
    fun anEngineErrorBecomesASpeechException() = runTest {
        val factory = SystemSpeech.Factory({ FakeTts(fail = true) }, tmp.root)
        val speech = factory.open(factory.voices().first())
        val e = assertThrows(SpeechException::class.java) { kotlinx.coroutines.runBlocking { speech.synthesize("x").toList() } }
        assertThat(e).hasMessageThat().contains("-3")
    }

    @Test
    fun aMissingVoiceOrAnEngineThatWontStartIsReported() = runTest {
        val factory = SystemSpeech.Factory({ FakeTts() }, tmp.root)
        assertThrows(SpeechException::class.java) {
            kotlinx.coroutines.runBlocking { factory.open(Voice(VoiceId.System("com.example.tts", "gone"), "gone", "en-US")) }
        }
        val dead = SystemSpeech.Factory({ throw SpeechException("no engine") }, tmp.root)
        assertThat(dead.voices()).isEmpty()
    }

    @Test
    fun theEngineIsSharedAndShutDownOnce() = runTest {
        var created = 0
        val tts = FakeTts()
        val factory = SystemSpeech.Factory({ created++; tts }, tmp.root)
        factory.open(factory.voices().first()).close()
        factory.open(factory.voices().last()).close()
        assertThat(created).isEqualTo(1)
        factory.shutdown()
        assertThat(tts.shutdowns).isEqualTo(1)
    }
}
