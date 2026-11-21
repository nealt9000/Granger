package app.granger.core.player.voice

import app.granger.core.speech.Pcm
import app.granger.core.speech.ReadAddress
import app.granger.core.speech.Speech
import app.granger.core.speech.SpeechException
import app.granger.core.speech.TextReadSource
import app.granger.core.speech.Voice
import app.granger.core.speech.VoiceId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine on every phone (Plan I5): plain coroutines over a fake voice and a fake output. The text's sentences break
 * the same with ICU (Android, the iPhone) and with the plain JVM fallback.
 */
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
        assertEquals(listOf("One two.", "Three four five.", "Six.", "Seven eight.", "Nine.", "Ten."), (speech.spoken).toList())
        // sentence, pause, sentence, pause …: the pause after a paragraph's last sentence is the long one
        assertEquals(listOf(350L, 350L, 700L, 350L, 700L, 700L), (out.written.filterIndexed { i, _ -> i % 2 == 1 }).toList())
        assertEquals("flush", out.events.first())
        assertTrue((out.events).contains("open:22050"))
        assertFalse(engine.playing.value)     // ended
    }

    @Test
    fun holdBeforeStopsOnceTheSentencesBeforeItAreHeardAndPlayGoesOnFromIt() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val engine = ReadAloudEngine(out, backgroundScope)
        var held = 0
        backgroundScope.launch { engine.held.collect { held++ } }
        engine.holdBefore = { it.text == "Seven eight." }
        engine.start(TextReadSource("text:t", "T", text), speech, null, 1f)
        out.release(100); settle()
        // Everything up to "Six." and its pause went out; nothing of "Seven eight." did.
        assertEquals(6, (out.written).size)
        assertEquals(1, held)
        assertFalse(engine.playing.value)
        assertEquals("Seven eight.", engine.progress.value?.utterance?.text)
        assertEquals(listOf("pause", "flush"), (out.events.takeLast(2)).toList())

        engine.holdBefore = null
        engine.play()
        settle()
        assertEquals(12, (out.written).size)
        assertEquals("Ten.", engine.progress.value?.utterance?.text)
    }

    @Test
    fun lookAheadStaysWithinTwoSentences() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val engine = ReadAloudEngine(out, backgroundScope, lookAheadSentences = 2)
        engine.start(TextReadSource("text:t", "T", text), speech, null, 1f)
        advanceTimeBy(10_000); runCurrent()
        // Nothing has been heard (the gate is shut): the producer may finish the sentence being written plus one more.
        assertTrue((speech.spoken.size) <= 3, "speech.spoken.size <= 3")
        out.release(2)                                  // first sentence and its pause go out
        advanceTimeBy(10_000); runCurrent()
        assertTrue((speech.spoken.size) <= 4, "speech.spoken.size <= 4")
    }

    @Test
    fun lookAheadStaysWithinThirtySecondsOfAudio() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech(msPerChar = 2_000)     // "One two." alone is 16 s
        val engine = ReadAloudEngine(out, backgroundScope, lookAheadSentences = 10, lookAheadMs = 30_000)
        engine.start(TextReadSource("text:t", "T", text), speech, null, 1f)
        advanceTimeBy(60_000); runCurrent()
        assertTrue((speech.spoken.size) <= 2, "speech.spoken.size <= 2")      // 16 s + 32 s crosses the cap; the third waits
    }

    @Test
    fun pauseRewindsToTheCurrentSentenceStart() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val engine = ReadAloudEngine(out, backgroundScope)
        engine.start(TextReadSource("text:t", "T", text), speech, null, 1f)
        out.release(2); settle()              // "One two." heard; "Three four five." is being written
        assertEquals("Three four five.", engine.progress.value?.utterance?.text)
        engine.pause()
        assertFalse(engine.playing.value)
        assertEquals(listOf("pause", "flush"), (out.events.takeLast(2)).toList())
        engine.play()
        out.release(2); settle()
        // The second sentence starts over (from the cache, not re-synthesized) and the next one follows.
        assertEquals(1, speech.spoken.count { it == "Three four five." })
        assertEquals("Six.", engine.progress.value?.utterance?.text)
    }

    @Test
    fun nextParagraphSkipsTheRestOfThisOne() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val engine = ReadAloudEngine(out, backgroundScope)
        engine.start(TextReadSource("text:t", "T", text), speech, null, 1f)
        out.release(1); settle()
        engine.nextParagraph()
        out.release(1); settle()
        assertEquals("Seven eight.", engine.progress.value?.utterance?.text)
        engine.previousParagraph()                       // at the paragraph's first sentence: the previous paragraph
        settle()
        assertEquals("One two.", engine.progress.value?.utterance?.text)
    }

    @Test
    fun previousSentenceAtTheFirstSentenceStaysThere() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val engine = ReadAloudEngine(out, backgroundScope)
        engine.start(TextReadSource("text:t", "T", text), speech, null, 1f)
        settle()
        engine.previousSentence()
        settle()
        assertEquals(0, engine.progress.value?.index)
        assertTrue(engine.playing.value)
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
        assertEquals(writes, out.written.size)
        assertNull(engine.progress.value)
        assertEquals(0, speech.closed)
        advanceTimeBy(120_001); runCurrent()
        assertEquals(1, speech.closed)
    }

    @Test
    fun aSentenceThatFailsIsSkippedAndReported() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val engine = ReadAloudEngine(out, backgroundScope)
        val errors = mutableListOf<SpeechException>()
        backgroundScope.launch { engine.errors.collect { errors += it } }
        engine.start(TextReadSource("text:t", "T", "Fine one. Then boom. Fine two."), speech, null, 1f)
        out.release(100); settle()
        assertEquals(listOf("Fine one.", "Fine two."), (speech.spoken).toList())
        assertEquals(1, (errors).size)
    }

    @Test
    fun theSourceIsAskedForMoreBeforeTheQueueRunsDry() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val long = (1..30).joinToString(" ") { "Sentence number $it." }
        val engine = ReadAloudEngine(out, backgroundScope, batch = 5)
        engine.start(TextReadSource("text:t", "T", long), speech, null, 1f)
        out.release(1000); settle()
        assertEquals(30, (speech.spoken).size)
        assertEquals("Sentence number 30.", speech.spoken.last())
    }

    @Test
    fun endedFiresAfterTheLastSentenceAndStartFromAnAddressWorks() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val engine = ReadAloudEngine(out, backgroundScope)
        var ended = 0
        backgroundScope.launch { engine.ended.collect { ended++ } }
        engine.start(TextReadSource("text:t", "T", text), speech, from = ReadAddress.Text("text:t", 1, 1), speed = 1.5f)
        out.release(100); settle()
        assertEquals(listOf("Nine.", "Ten."), (speech.spoken).toList())
        assertEquals(listOf(1.5f, 1.5f), (speech.speeds).toList())
        assertEquals(1, ended)
    }

    @Test
    fun speedChangeAppliesFromTheNextSentence() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val engine = ReadAloudEngine(out, backgroundScope, lookAheadSentences = 1)
        engine.start(TextReadSource("text:t", "T", text), speech, null, 1f)
        out.release(2); settle()
        engine.setSpeed(2f)
        out.release(100); settle()
        assertEquals(1f, speech.speeds.first())
        assertEquals(2f, speech.speeds.last())
    }

    @Test
    fun theCacheIsKeyedBySpeedSoAStepBackAtTheOldSpeedReplaysWithoutSynthesis() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val engine = ReadAloudEngine(out, backgroundScope, lookAheadSentences = 1)
        engine.start(TextReadSource("text:t", "T", text), speech, null, 1f)
        out.release(2); settle()                                   // "One two." heard at 1×
        val madeAtOne = speech.spoken.count { it == "One two." }
        engine.setSpeed(2f)
        engine.previousSentence(); out.release(2); settle()        // again at 2×: a new synthesis
        assertEquals(madeAtOne + 1, speech.spoken.count { it == "One two." })
        engine.setSpeed(1f)
        engine.previousSentence(); out.release(2); settle()        // back at 1×: from the cache
        assertEquals(madeAtOne + 1, speech.spoken.count { it == "One two." })
        assertEquals(2f, speech.speeds.last())
    }

    @Test
    fun aNewStartClosesTheOldEngineOnlyAfterItsSentenceIsMade() = runTest {
        val out = FakeOutput()
        // A slow voice: a sentence takes a second to make, and making one after close is the crash this guards against.
        val slow = object : Speech {
            override val voice = Voice(VoiceId.Granger("slow"), "Slow", "en-US")
            var closed = 0
            var madeAfterClose = 0
            override fun synthesize(text: String, speed: Float): Flow<Pcm> = flow {
                // Like the native call: once begun, a sentence runs to its end whatever the collector does.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { delay(1_000) }
                if (closed > 0) madeAfterClose++
                emit(Pcm(FloatArray(2_205), 22_050))
            }
            override fun close() { closed++ }
        }
        val engine = ReadAloudEngine(out, backgroundScope, lookAheadSentences = 1)
        engine.start(TextReadSource("text:t", "T", text), slow, null, 1f)
        advanceTimeBy(200); runCurrent()                           // the first sentence is being made
        val fast = FakeSpeech()
        engine.start(TextReadSource("text:t", "T", text), fast, null, 1f)
        runCurrent()
        assertEquals(0, slow.closed)                       // not while its sentence is in flight
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, slow.closed)
        assertEquals(0, slow.madeAfterClose)
        out.release(100); settle()
        assertEquals("One two.", fast.spoken.first())
    }

    @Test
    fun jumpToAnAddressRestartsThere() = runTest {
        val out = FakeOutput(); val speech = FakeSpeech()
        val engine = ReadAloudEngine(out, backgroundScope)
        engine.start(TextReadSource("text:t", "T", text), speech, null, 1f)
        out.release(1); settle()
        engine.jumpTo(ReadAddress.Text("text:t", 2, 0))
        out.release(100); settle()
        assertEquals("Ten.", speech.spoken.last())
        assertFalse(engine.playing.value)
    }
}
