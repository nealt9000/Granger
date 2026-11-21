package app.granger.readaloud

import app.granger.UiText
import app.granger.core.library.PlaybackDao
import app.granger.core.library.VoiceState
import app.granger.core.player.SleepSetting
import app.granger.core.player.voice.SpeechOutput
import app.granger.core.speech.InstalledVoices
import app.granger.core.speech.Pcm
import app.granger.core.speech.ReadAddress
import app.granger.core.speech.ReadChapter
import app.granger.core.speech.ReadSource
import app.granger.core.speech.Speech
import app.granger.core.speech.SpeechFactory
import app.granger.core.speech.TextReadSource
import app.granger.core.speech.Utterance
import app.granger.core.speech.Voice
import app.granger.core.speech.VoiceId
import app.granger.settings.VoicePrefs
import com.google.common.truth.Truth.assertThat
import java.lang.reflect.Proxy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The iPhone's voice player (Plan I5 Task 8) over the shared engine, a fake voice, a fake output and the voice_states rows. */
class VoicePlayerTest {
    /** Each sentence is 40 ms of audio per character, made in 50 ms. */
    private class FakeSpeech(override val voice: Voice) : Speech {
        val spoken = mutableListOf<String>()
        override fun synthesize(text: String, speed: Float): Flow<Pcm> = flow {
            delay(50)
            spoken += text
            emit(Pcm(FloatArray(text.length * 40 * 22_050 / 1000), 22_050))
        }
        override fun close() {}
    }

    /** Plays what's written in real (virtual) time. */
    private class FakeOutput : SpeechOutput {
        override var volume = 1f
        override var playedMs = 0L
        var playing = false
        val volumes = mutableListOf<Float>()
        override fun open(sampleRate: Int) {}
        override suspend fun write(pcm: Pcm) { delay(pcm.durationMs); playedMs += pcm.durationMs }
        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun flush() {}
        override fun close() {}
    }

    private class Prefs : VoicePrefs {
        override val voiceId = MutableStateFlow<VoiceId?>(null)
        override val speed = MutableStateFlow(1f)
        override val markSentence = MutableStateFlow(true)
        override val speakAnswers = MutableStateFlow(false)
        override fun setVoice(id: VoiceId?) { voiceId.value = id }
        override fun setSpeed(speed: Float) { this.speed.value = speed }
        override fun setMarkSentence(on: Boolean) {}
        override fun setSpeakAnswers(on: Boolean) {}
    }

    /** The voice_states table in memory; everything else the player never touches. */
    private class VoiceRows(private val rows: MutableStateFlow<Map<String, VoiceState>> = MutableStateFlow(emptyMap())) :
        PlaybackDao by unused() {
        val all: Map<String, VoiceState> get() = rows.value
        override suspend fun voiceState(sourceKey: String) = rows.value[sourceKey]
        override fun observeVoiceStates(): Flow<List<VoiceState>> = rows.map { it.values.sortedByDescending(VoiceState::updatedAt) }
        override suspend fun upsertVoiceState(state: VoiceState) { rows.value = rows.value + (state.sourceKey to state) }
        override suspend fun deleteVoiceState(sourceKey: String) { rows.value = rows.value - sourceKey }

        companion object {
            fun unused(): PlaybackDao = Proxy.newProxyInstance(PlaybackDao::class.java.classLoader, arrayOf(PlaybackDao::class.java)) { _, m, _ ->
                error("not used: ${m.name}")
            } as PlaybackDao
        }
    }

    /** Two chapters of two paragraphs each, by paragraph: chapter 0 holds paragraphs 0–1, chapter 1 paragraphs 2–3. */
    private class ChapteredSource(private val inner: TextReadSource) : ReadSource by inner {
        override suspend fun chapters() = listOf(ReadChapter("One"), ReadChapter("Two"))
        override suspend fun chapterAt(at: ReadAddress): Int = if ((at as ReadAddress.Text).block < 2) 0 else 1
        override suspend fun chapterStart(index: Int): ReadAddress = ReadAddress.Text(inner.key, index * 2, 0)
        override suspend fun locate(address: ReadAddress): ReadAddress = address
    }

    private val owl = Voice(VoiceId.Granger("vits-piper-en_GB-granger_owl-medium"), "Granger voice (Owl)", "en-GB")
    private var speech: FakeSpeech? = null
    private val factory = object : SpeechFactory {
        override suspend fun open(voice: Voice): Speech = FakeSpeech(voice).also { speech = it }
    }
    private val text = "One two. Three four.\n\nFive six.\n\nSeven eight. Nine.\n\nTen."

    private fun TestScope.player(rows: VoiceRows, out: FakeOutput, sources: Map<String, ReadSource> = emptyMap(), voices: List<Voice> = listOf(owl)): VoicePlayer {
        val installed = InstalledVoices({ emptyList() }, { voices }, factory, factory, io = kotlinx.coroutines.Dispatchers.Unconfined)
        return VoicePlayer(out, installed, Prefs(), rows, { key, _ -> sources[key] }, backgroundScope, { testScheduler.currentTime })
    }

    private fun TestScope.settle(ms: Long = 60_000) { advanceTimeBy(ms); runCurrent() }

    @Test fun readsAloudAndKeepsThePlaceAsEachSentenceStarts() = runTest {
        val rows = VoiceRows(); val out = FakeOutput()
        val p = player(rows, out)
        p.readAloud(TextReadSource("text:t", "The text", text))
        advanceTimeBy(500); runCurrent()
        assertThat(p.ui.value.playing).isTrue()
        assertThat(p.ui.value.now?.title).isEqualTo("The text")
        assertThat(rows.all["text:t"]?.title).isEqualTo("The text")
        settle()
        assertThat(speech!!.spoken).containsExactly("One two.", "Three four.", "Five six.", "Seven eight.", "Nine.", "Ten.").inOrder()
        assertThat(p.ui.value.now?.ended).isTrue()
        assertThat(p.ui.value.playing).isFalse()
        assertThat(ReadAddress.decode(rows.all.getValue("text:t").address)).isEqualTo(ReadAddress.Text("text:t", 3, 0))
    }

    @Test fun pauseKeepsTheSentenceAndResumeLastReopensThere() = runTest {
        val rows = VoiceRows(); val out = FakeOutput()
        val src = TextReadSource("text:t", "The text", text)
        val p = player(rows, out, sources = mapOf("text:t" to src))
        p.readAloud(src, from = ReadAddress.Text("text:t", 1, 0))
        advanceTimeBy(300); runCurrent()
        p.pause(); runCurrent()
        assertThat(p.ui.value.playing).isFalse()
        assertThat(ReadAddress.decode(rows.all.getValue("text:t").address)).isEqualTo(ReadAddress.Text("text:t", 1, 0))
        p.stop(); runCurrent()
        assertThat(p.ui.value.now).isNull()
        assertThat(p.lastRead.value?.address).isEqualTo(ReadAddress.Text("text:t", 1, 0))
        val resumed = backgroundScope.launch { assertThat(p.resumeLast()).isTrue() }
        advanceTimeBy(100); runCurrent()
        resumed.join()
        assertThat(p.ui.value.playing).isTrue()
        assertThat(speech!!.spoken.first()).isEqualTo("Five six.")
    }

    @Test fun theSleepTimerFadesThenPauses() = runTest {
        val rows = VoiceRows(); val out = FakeOutput()
        val long = (1..40).joinToString("\n\n") { "Sentence number $it is here." }
        val p = player(rows, out)
        p.readAloud(TextReadSource("text:long", "Long", long))
        advanceTimeBy(200); runCurrent()
        p.startSleepTimer(SleepSetting.After(15_000)); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        assertThat(p.ui.value.sleepRemainingMs).isIn(11_000L..12_500L)
        advanceTimeBy(9_000); runCurrent()
        assertThat(out.volume).isLessThan(1f)
        advanceTimeBy(4_000); runCurrent()
        assertThat(p.ui.value.playing).isFalse()
        assertThat(p.ui.value.sleeping).isFalse()
        assertThat(out.volume).isEqualTo(1f)
    }

    @Test fun endOfChapterStopsBeforeTheNextChaptersFirstSentence() = runTest {
        val rows = VoiceRows(); val out = FakeOutput()
        val src = ChapteredSource(TextReadSource("text:c", "Chaptered", text))
        val p = player(rows, out)
        p.readAloud(src)
        advanceTimeBy(200); runCurrent()
        p.startSleepTimer(SleepSetting.EndOfChapter); runCurrent()
        assertThat(p.ui.value.sleepAtChapterEnd).isTrue()
        settle()
        assertThat(p.ui.value.playing).isFalse()
        assertThat(p.ui.value.sleeping).isFalse()
        assertThat(p.progress.value?.utterance?.text).isEqualTo("Seven eight.")
        assertThat(p.chapters()?.current).isEqualTo(1)
    }

    @Test fun withNoVoiceItSaysSoAndLoadsNothing() = runTest {
        val rows = VoiceRows(); val out = FakeOutput()
        val p = player(rows, out, voices = emptyList())
        val notices = mutableListOf<UiText>()
        backgroundScope.launch { p.notices.collect { notices += it } }
        runCurrent()
        p.readAloud(TextReadSource("text:t", "The text", text)); runCurrent()
        assertThat(p.ui.value.now).isNull()
        assertThat(notices).containsExactly(UiText.Res("player_voice_none"))
    }
}
