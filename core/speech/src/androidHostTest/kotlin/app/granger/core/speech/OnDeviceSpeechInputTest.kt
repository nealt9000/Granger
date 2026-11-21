package app.granger.core.speech

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnDeviceSpeechInputTest {
    private class FakeSession : RecognizerSession {
        var stopped = false
        var cancelled = false
        var released = false
        override fun stop() { stopped = true }
        override fun cancel() { cancelled = true }
        override fun release() { released = true }
    }

    private class FakeBackend(
        var available: Boolean = true,
        var languages: RecognizerLanguages? = RecognizerLanguages(listOf("en-US"), listOf("en-GB")),
    ) : RecognizerBackend {
        val listeners = mutableListOf<RecognizerListener>()
        val sessions = mutableListOf<FakeSession>()
        val startedAt = mutableListOf<Long>()
        var supportCalls = 0
        var clock: () -> Long = { 0 }
        override fun onDeviceAvailable() = available
        override suspend fun support(): RecognizerLanguages? { supportCalls++; return languages }
        override fun start(tag: String, listener: RecognizerListener): RecognizerSession {
            listeners += listener
            startedAt += clock()
            return FakeSession().also { sessions += it }
        }
        override suspend fun download(tag: String, onProgress: (Int) -> Unit): DownloadOutcome {
            onProgress(100)
            languages = languages?.let { it.copy(installed = it.installed + tag) }
            return DownloadOutcome.DONE
        }
        val last get() = listeners.last()
    }

    private fun TestScope.input(backend: FakeBackend): OnDeviceSpeechInput {
        val dispatcher = StandardTestDispatcher(testScheduler)
        backend.clock = { testScheduler.currentTime }
        return OnDeviceSpeechInput(backend, main = dispatcher, now = { testScheduler.currentTime })
    }

    @Test
    fun partialsThenAFinal() = runTest {
        val backend = FakeBackend()
        val speech = input(backend)
        val events = mutableListOf<DictationEvent>()
        val job = launch { speech.listen("en-US").toList(events) }
        runCurrent()
        backend.last.onReady()
        backend.last.onPartial("What did")
        backend.last.onPartial("")
        backend.last.onPartial("What did Kant")
        backend.last.onEndOfSpeech()
        backend.last.onResults(listOf("What did Kant add to philosophy", "what did can't"))
        advanceUntilIdle()
        assertThat(job.isCompleted).isTrue()
        assertThat(events).containsExactly(
            DictationEvent.Listening,
            DictationEvent.Partial("What did"),
            DictationEvent.Partial("What did Kant"),
            DictationEvent.EndOfSpeech,
            DictationEvent.Final("What did Kant add to philosophy"),
        ).inOrder()
        assertThat(backend.sessions.single().released).isTrue()
    }

    @Test
    fun anEmptyFinalFallsBackToTheLastPartial() = runTest {
        val backend = FakeBackend()
        val speech = input(backend)
        val events = mutableListOf<DictationEvent>()
        launch { speech.listen("en-US").toList(events) }
        runCurrent()
        backend.last.onReady()
        backend.last.onPartial("Open Peter Pan")
        backend.last.onResults(listOf(""))
        advanceUntilIdle()
        assertThat(events.last()).isEqualTo(DictationEvent.Final("Open Peter Pan"))
    }

    @Test
    fun nothingSaidIsANoMatch() = runTest {
        val backend = FakeBackend()
        val speech = input(backend)
        val events = mutableListOf<DictationEvent>()
        launch { speech.listen("en-US").toList(events) }
        runCurrent()
        backend.last.onReady()
        backend.last.onError(DictationFailure.NO_MATCH)
        advanceUntilIdle()
        assertThat(events.last()).isEqualTo(DictationEvent.Failed(DictationFailure.NO_MATCH))
    }

    @Test
    fun aNoMatchAfterWordsKeepsTheWords() = runTest {
        val backend = FakeBackend()
        val speech = input(backend)
        val events = mutableListOf<DictationEvent>()
        launch { speech.listen("en-US").toList(events) }
        runCurrent()
        backend.last.onReady()
        backend.last.onPartial("Roman roads")
        backend.last.onError(DictationFailure.NO_MATCH)
        advanceUntilIdle()
        assertThat(events.last()).isEqualTo(DictationEvent.Final("Roman roads"))
    }

    @Test
    fun theColdStartGets25SecondsThenTimesOut() = runTest {
        val backend = FakeBackend()
        val speech = input(backend)
        val events = mutableListOf<DictationEvent>()
        launch { speech.listen("en-US").toList(events) }
        runCurrent()
        advanceTimeBy(20_000)
        assertThat(events).isEmpty()
        advanceTimeBy(5_001)
        assertThat(events).containsExactly(DictationEvent.Failed(DictationFailure.TIMEOUT))
        assertThat(backend.sessions.single().cancelled).isTrue()
    }

    @Test
    fun aSlowColdStartThatOpensInTimeIsFine() = runTest {
        val backend = FakeBackend()
        val speech = input(backend)
        val events = mutableListOf<DictationEvent>()
        launch { speech.listen("en-US").toList(events) }
        runCurrent()
        advanceTimeBy(16_600)
        backend.last.onReady()
        advanceTimeBy(30_000)
        backend.last.onResults(listOf("Hello"))
        advanceUntilIdle()
        assertThat(events).containsExactly(DictationEvent.Listening, DictationEvent.Final("Hello")).inOrder()
    }

    @Test
    fun busyBeforeTheMicOpensIsRetriedOnce() = runTest {
        val backend = FakeBackend()
        val speech = input(backend)
        val events = mutableListOf<DictationEvent>()
        launch { speech.listen("en-US").toList(events) }
        runCurrent()
        backend.last.onError(DictationFailure.BUSY)
        advanceTimeBy(1_001)
        assertThat(backend.sessions).hasSize(2)
        assertThat(backend.sessions.first().cancelled).isTrue()
        backend.last.onError(DictationFailure.BUSY)
        advanceUntilIdle()
        assertThat(backend.sessions).hasSize(2)
        assertThat(events).containsExactly(DictationEvent.Failed(DictationFailure.BUSY))
    }

    @Test
    fun takesAreHalfASecondApart() = runTest {
        val backend = FakeBackend()
        val speech = input(backend)
        launch { speech.listen("en-US").toList(mutableListOf()) }
        runCurrent()
        backend.last.onReady()
        backend.last.onResults(listOf("one"))
        advanceUntilIdle()
        val endedAt = testScheduler.currentTime
        launch { speech.listen("en-US").toList(mutableListOf()) }
        advanceUntilIdle()
        assertThat(backend.startedAt.last() - endedAt).isEqualTo(500)
    }

    @Test
    fun cancellingTheCollectionCancelsTheTake() = runTest {
        val backend = FakeBackend()
        val speech = input(backend)
        val job = launch { speech.listen("en-US").toList(mutableListOf()) }
        runCurrent()
        backend.last.onReady()
        job.cancel()
        advanceUntilIdle()
        assertThat(backend.sessions.single().cancelled).isTrue()
    }

    @Test
    fun stopAsksTheRecognizerToFinish() = runTest {
        val backend = FakeBackend()
        val speech = input(backend)
        launch { speech.listen("en-US").toList(mutableListOf()) }
        runCurrent()
        backend.last.onReady()
        speech.stop()
        assertThat(backend.sessions.single().stopped).isTrue()
        backend.last.onResults(listOf("done"))
        advanceUntilIdle()
    }

    @Test
    fun availabilityIsCachedUntilADownload() = runTest {
        val backend = FakeBackend()
        val speech = input(backend)
        assertThat(speech.availability("en-US")).isEqualTo(Availability.READY)
        assertThat(speech.availability("en-GB")).isEqualTo(Availability.DOWNLOADABLE)
        assertThat(speech.availability("fr-FR")).isEqualTo(Availability.UNAVAILABLE)
        assertThat(backend.supportCalls).isEqualTo(1)
        assertThat(speech.download("en-GB")).isEqualTo(DownloadOutcome.DONE)
        assertThat(speech.availability("en-GB")).isEqualTo(Availability.READY)
        assertThat(backend.supportCalls).isEqualTo(2)
    }

    @Test
    fun forgetAsksThePhoneAgain() = runTest {
        val backend = FakeBackend()
        val speech = input(backend)
        speech.availability("en-GB")
        backend.languages = RecognizerLanguages(listOf("en-US", "en-GB"), emptyList())
        assertThat(speech.availability("en-GB")).isEqualTo(Availability.DOWNLOADABLE)
        speech.forget()
        assertThat(speech.availability("en-GB")).isEqualTo(Availability.READY)
        assertThat(backend.supportCalls).isEqualTo(2)
    }

    @Test
    fun aPhoneWithoutOnDeviceRecognitionHasNothing() = runTest {
        val speech = input(FakeBackend(available = false))
        assertThat(speech.languages()).isNull()
        assertThat(speech.availability("en-US")).isEqualTo(Availability.UNAVAILABLE)
    }
}
