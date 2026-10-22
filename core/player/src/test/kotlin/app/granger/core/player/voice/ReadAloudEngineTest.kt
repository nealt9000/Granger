package app.granger.core.player.voice

import app.granger.core.speech.Pcm
import app.granger.core.speech.ReadAddress
import app.granger.core.speech.Speech
import app.granger.core.speech.SpeechException
import app.granger.core.speech.TextReadSource
import app.granger.core.speech.Voice
import app.granger.core.speech.VoiceId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ICU (for the sentence split) comes from the framework, so Robolectric; the engine itself is plain coroutines. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadAloudEngineTest {
    /** Runs the engine's background work: `advanceUntilIdle` skips background-scope tasks in this coroutines version. */
    private fun TestScope.settle() { advanceTimeBy(60_000); runCurrent() }

    /** Speaks each sentence as [msPerChar] ms of audio per character after [synthMs] of work; fails sentences that contain "boom". */
    private class FakeSpeech(val synthMs: Long = 100, val msPerChar: Long = 50) : Speech {
        override val voice = Voice(VoiceId.Granger("fake"), "Fake", "en-US")
        val spoken = mutableListOf<String>()
        val speeds = mutableListOf<Float>()
        var closed = 0
        override fun synthesize(text: String, speed: Float): Flow<Pcm> = flow {
            delay(synthMs)
            if ("boom" in text) throw SpeechException("boom")
            spoken += text; speeds += speed
            emit(Pcm(FloatArray((text.length * msPerChar * 22_050 / 1000).toInt()) { 0.1f }, 22_050))
        }
        override fun close() { closed++ }
    }

    /** Records writes; each write waits for one [release] so a test can hold the output back like a full buffer. */
    private class FakeOutput : SpeechOutput {
        val gate = Channel<Unit>(Channel.UNLIMITED)
        val written = mutableListOf<Long>()          // durations in ms, silence included
        val events = mutableListOf<String>()
        override var volume = 1f
        override var playedMs = 0L
        var rate = 0
        override fun open(sampleRate: Int) { if (rate != sampleRate) { rate = sampleRate; events += "open:$sampleRate" } }
        override suspend fun write(pcm: Pcm) { gate.receive(); written += pcm.durationMs; playedMs += pcm.durationMs }
        override fun play() { events += "play" }
        override fun pause() { events += "pause" }
        override fun flush() { events += "flush" }
        override fun close() { events += "close" }
        fun release(n: Int = 1) = repeat(n) { gate.trySend(Unit) }
    }

    private val text = "One two. Three four five. Six.\n\nSeven eight. Nine.\n\nTen."   // paragraphs of 3, 2, 1 sentences

    @Test
    fun startSpeaksSentencesInOrderWithPausesBetween() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val engine = ReadAloudEngine(out, backgroundScope)
        engine.start(TextReadSource("text:t", "T", text), speech, from = null, speed = 1f)
        out.release(100)
        settle()
        assertThat(speech.spoken).containsExactly("One two.", "Three four five.", "Six.", "Seven eight.", "Nine.", "Ten.").inOrder()
        // sentence, pause, sentence, pause …: the pause after a paragraph's last sentence is the long one
        assertThat(out.written.filterIndexed { i, _ -> i % 2 == 1 }).containsExactly(350L, 350L, 700L, 350L, 700L, 700L).inOrder()
        assertThat(out.events.first()).isEqualTo("flush")
        assertThat(out.events).contains("open:22050")
        assertThat(engine.playing.value).isFalse()     // ended
    }

    @Test
    fun lookAheadStaysWithinTwoSentences() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val engine = ReadAloudEngine(out, backgroundScope, lookAheadSentences = 2)
        engine.start(TextReadSource("text:t", "T", text), speech, null, 1f)
        advanceTimeBy(10_000); runCurrent()
        // Nothing has been heard (the gate is shut): the producer may finish the sentence being written plus one more.
        assertThat(speech.spoken.size).isAtMost(3)
        out.release(2)                                  // first sentence and its pause go out
        advanceTimeBy(10_000); runCurrent()
        assertThat(speech.spoken.size).isAtMost(4)
    }

    @Test
    fun lookAheadStaysWithinThirtySecondsOfAudio() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech(msPerChar = 2_000)     // "One two." alone is 16 s
        val engine = ReadAloudEngine(out, backgroundScope, lookAheadSentences = 10, lookAheadMs = 30_000)
        engine.start(TextReadSource("text:t", "T", text), speech, null, 1f)
        advanceTimeBy(60_000); runCurrent()
        assertThat(speech.spoken.size).isAtMost(2)      // 16 s + 32 s crosses the cap; the third waits
    }

    @Test
    fun pauseRewindsToTheCurrentSentenceStart() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val engine = ReadAloudEngine(out, backgroundScope)
        engine.start(TextReadSource("text:t", "T", text), speech, null, 1f)
        out.release(2); settle()              // "One two." heard; "Three four five." is being written
        assertThat(engine.progress.value?.utterance?.text).isEqualTo("Three four five.")
        engine.pause()
        assertThat(engine.playing.value).isFalse()
        assertThat(out.events.takeLast(2)).containsExactly("pause", "flush").inOrder()
        engine.play()
        out.release(2); settle()
        // The second sentence starts over (from the cache, not re-synthesized) and the next one follows.
        assertThat(speech.spoken.count { it == "Three four five." }).isEqualTo(1)
        assertThat(engine.progress.value?.utterance?.text).isEqualTo("Six.")
    }

    @Test
    fun nextParagraphSkipsTheRestOfThisOne() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val engine = ReadAloudEngine(out, backgroundScope)
        engine.start(TextReadSource("text:t", "T", text), speech, null, 1f)
        out.release(1); settle()
        engine.nextParagraph()
        out.release(1); settle()
        assertThat(engine.progress.value?.utterance?.text).isEqualTo("Seven eight.")
        engine.previousParagraph()                       // at the paragraph's first sentence: the previous paragraph
        settle()
        assertThat(engine.progress.value?.utterance?.text).isEqualTo("One two.")
    }

    @Test
    fun previousSentenceAtTheFirstSentenceStaysThere() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val engine = ReadAloudEngine(out, backgroundScope)
        engine.start(TextReadSource("text:t", "T", text), speech, null, 1f)
        settle()
        engine.previousSentence()
        settle()
        assertThat(engine.progress.value?.index).isEqualTo(0)
        assertThat(engine.playing.value).isTrue()
    }

    @Test
    fun stopProducesNoFurtherWritesAndClosesSpeechAfterIdle() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val engine = ReadAloudEngine(out, backgroundScope, idleCloseMs = 120_000)
        engine.start(TextReadSource("text:t", "T", text), speech, null, 1f)
        out.release(1); settle()
        engine.stop()
        val writes = out.written.size
        out.release(50); advanceTimeBy(5_000); runCurrent()
        assertThat(out.written.size).isEqualTo(writes)
        assertThat(engine.progress.value).isNull()
        assertThat(speech.closed).isEqualTo(0)
        advanceTimeBy(120_001); runCurrent()
        assertThat(speech.closed).isEqualTo(1)
    }

    @Test
    fun aSentenceThatFailsIsSkippedAndReported() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val engine = ReadAloudEngine(out, backgroundScope)
        val errors = mutableListOf<SpeechException>()
        backgroundScope.launch { engine.errors.collect { errors += it } }
        engine.start(TextReadSource("text:t", "T", "Fine one. Then boom. Fine two."), speech, null, 1f)
        out.release(100); settle()
        assertThat(speech.spoken).containsExactly("Fine one.", "Fine two.").inOrder()
        assertThat(errors).hasSize(1)
    }

    @Test
    fun theSourceIsAskedForMoreBeforeTheQueueRunsDry() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val long = (1..30).joinToString(" ") { "Sentence number $it." }
        val engine = ReadAloudEngine(out, backgroundScope, batch = 5)
        engine.start(TextReadSource("text:t", "T", long), speech, null, 1f)
        out.release(1000); settle()
        assertThat(speech.spoken).hasSize(30)
        assertThat(speech.spoken.last()).isEqualTo("Sentence number 30.")
    }

    @Test
    fun endedFiresAfterTheLastSentenceAndStartFromAnAddressWorks() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val engine = ReadAloudEngine(out, backgroundScope)
        var ended = 0
        backgroundScope.launch { engine.ended.collect { ended++ } }
        engine.start(TextReadSource("text:t", "T", text), speech, from = ReadAddress.Text("text:t", 1, 1), speed = 1.5f)
        out.release(100); settle()
        assertThat(speech.spoken).containsExactly("Nine.", "Ten.").inOrder()
        assertThat(speech.speeds).containsExactly(1.5f, 1.5f)
        assertThat(ended).isEqualTo(1)
    }

    @Test
    fun speedChangeAppliesFromTheNextSentence() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val engine = ReadAloudEngine(out, backgroundScope, lookAheadSentences = 1)
        engine.start(TextReadSource("text:t", "T", text), speech, null, 1f)
        out.release(2); settle()
        engine.setSpeed(2f)
        out.release(100); settle()
        assertThat(speech.speeds.first()).isEqualTo(1f)
        assertThat(speech.speeds.last()).isEqualTo(2f)
    }

    @Test
    fun jumpToAnAddressRestartsThere() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val engine = ReadAloudEngine(out, backgroundScope)
        engine.start(TextReadSource("text:t", "T", text), speech, null, 1f)
        out.release(1); settle()
        engine.jumpTo(ReadAddress.Text("text:t", 2, 0))
        out.release(100); settle()
        assertThat(speech.spoken.last()).isEqualTo("Ten.")
        assertThat(engine.playing.value).isFalse()
    }
}
