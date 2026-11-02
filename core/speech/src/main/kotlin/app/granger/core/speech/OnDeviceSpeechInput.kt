package app.granger.core.speech

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [SpeechInput] over a [RecognizerBackend] ([AndroidRecognizerBackend] on the phone). What the A0 spike taught is
 * here rather than in the backend, so it is unit-tested:
 * - the first take after the recognition service has been stopped took 16.6 s, so the microphone gets
 *   [readyTimeoutMs] to open;
 * - a recognizer opened straight after the last one closed sometimes fails as the service goes down, so takes are
 *   [gapMs] apart and a busy failure before the microphone opens is retried once after [retryDelayMs];
 * - a final result can come back empty, so the last partial stands in for it.
 */
class OnDeviceSpeechInput(
    private val backend: RecognizerBackend,
    private val main: CoroutineDispatcher = Dispatchers.Main,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val readyTimeoutMs: Long = 25_000,
    private val gapMs: Long = 500,
    private val retryDelayMs: Long = 1_000,
) : SpeechInput {
    private val cacheLock = Mutex()
    private var cached: RecognizerLanguages? = null
    private var current: RecognizerSession? = null
    private var lastEndedAt = Long.MIN_VALUE / 2

    override suspend fun languages(): RecognizerLanguages? = cacheLock.withLock {
        cached ?: run {
            withContext(main) {
                if (!backend.onDeviceAvailable()) null else backend.support()
            }?.also { cached = it }
        }
    }

    override suspend fun availability(tag: String): Availability =
        languages()?.availability(tag) ?: Availability.UNAVAILABLE

    override fun listen(tag: String): Flow<DictationEvent> = callbackFlow {
        val wait = lastEndedAt + gapMs - now()
        if (wait > 0) delay(wait)
        var finished = false
        var opened = false
        var lastPartial = ""
        var retried = false
        var timeout: Job? = null

        fun end(event: DictationEvent) {
            if (finished) return
            finished = true
            timeout?.cancel()
            trySend(event)
            close()
        }

        lateinit var startTake: () -> Unit
        val listener = object : RecognizerListener {
            override fun onReady() {
                if (finished) return
                opened = true
                timeout?.cancel()
                trySend(DictationEvent.Listening)
            }

            override fun onPartial(text: String) {
                if (finished || text.isBlank()) return
                lastPartial = text
                trySend(DictationEvent.Partial(text))
            }

            override fun onEndOfSpeech() { if (!finished) trySend(DictationEvent.EndOfSpeech) }

            override fun onResults(texts: List<String>) {
                val text = texts.firstOrNull { it.isNotBlank() } ?: lastPartial
                end(if (text.isBlank()) DictationEvent.Failed(DictationFailure.NO_MATCH) else DictationEvent.Final(text))
            }

            override fun onError(failure: DictationFailure) {
                if (finished) return
                when {
                    failure == DictationFailure.BUSY && !opened && !retried -> {
                        retried = true
                        current?.cancel()
                        launch { delay(retryDelayMs); if (!finished) startTake() }
                    }
                    // Words were heard before the recognizer gave up: keep them.
                    failure == DictationFailure.NO_MATCH && lastPartial.isNotBlank() -> end(DictationEvent.Final(lastPartial))
                    else -> end(DictationEvent.Failed(failure))
                }
            }
        }
        startTake = {
            timeout?.cancel()
            timeout = launch {
                delay(readyTimeoutMs)
                if (!opened) { current?.cancel(); end(DictationEvent.Failed(DictationFailure.TIMEOUT)) }
            }
            current = backend.start(tag, listener)
        }
        startTake()
        awaitClose {
            if (!finished) current?.cancel() else current?.release()
            current = null
            lastEndedAt = now()
        }
    }.flowOn(main)

    override fun stop() { current?.stop() }

    override suspend fun download(tag: String, onProgress: (Int) -> Unit): DownloadOutcome {
        val outcome = withContext(main) { backend.download(tag, onProgress) }
        cacheLock.withLock { cached = null }
        return outcome
    }
}

/** The platform recognizer, kept thin so [OnDeviceSpeechInput]'s rules can be tested with a fake. Main thread only. */
interface RecognizerBackend {
    fun onDeviceAvailable(): Boolean
    suspend fun support(): RecognizerLanguages?
    fun start(tag: String, listener: RecognizerListener): RecognizerSession
    suspend fun download(tag: String, onProgress: (Int) -> Unit): DownloadOutcome
}

interface RecognizerListener {
    fun onReady()
    fun onPartial(text: String)
    fun onEndOfSpeech()
    fun onResults(texts: List<String>)
    fun onError(failure: DictationFailure)
}

interface RecognizerSession {
    /** Stop listening; results follow. */
    fun stop()
    /** Abandon the take; nothing follows. Frees the recognizer. */
    fun cancel()
    /** Free the recognizer after the take has ended. */
    fun release()
}
