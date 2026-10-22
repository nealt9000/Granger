package app.granger.core.player.voice

import app.granger.core.speech.Pcm
import app.granger.core.speech.ReadAddress
import app.granger.core.speech.ReadSource
import app.granger.core.speech.Speech
import app.granger.core.speech.SpeechException
import app.granger.core.speech.Utterance
import app.granger.core.speech.UtteranceKind
import app.granger.core.speech.Wav
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The sentence being spoken: what the readers mark and the notification shows. */
data class VoiceProgress(val address: ReadAddress, val utterance: Utterance, val index: Int)

/**
 * Reads a [ReadSource] aloud through a [Speech] engine into a [SpeechOutput]: synthesizes a little ahead so
 * sentences flow, writes the pauses as silence so the output's clock stays true, pauses at a sentence start, and
 * skips by sentence or paragraph. One engine per player; [start] replaces whatever was reading.
 *
 * Look-ahead is bounded two ways: at most [lookAheadSentences] synthesized but unheard, and at most [lookAheadMs]
 * of them (V0: a sentence takes an eighth of its length to make, so one or two is plenty on the Pixel 6; the
 * screen-off run may raise it). [idleCloseMs] after a stop the speech engine is closed; a Piper voice holds
 * hundreds of megabytes while open.
 */
class ReadAloudEngine(
    private val output: SpeechOutput,
    private val scope: CoroutineScope,
    val lookAheadSentences: Int = 2,
    val lookAheadMs: Long = 30_000,
    val idleCloseMs: Long = 120_000,
    private val batch: Int = 24,
) {
    private val _progress = MutableStateFlow<VoiceProgress?>(null)
    val progress: StateFlow<VoiceProgress?> = _progress.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val _ended = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The source ran out while playing. */
    val ended: SharedFlow<Unit> = _ended.asSharedFlow()
    private val _errors = MutableSharedFlow<SpeechException>(extraBufferCapacity = 8)
    val errors: SharedFlow<SpeechException> = _errors.asSharedFlow()

    private val lock = Mutex()
    private var source: ReadSource? = null
    private var speech: Speech? = null
    private var speed = 1f
    private val buffer = ArrayList<Utterance>()      // what the source has handed over so far, in order
    private var exhausted = false                    // the source said "no more"
    private var index = 0                            // the sentence playing or to play next
    private var jobs: Job? = null
    private var idleClose: Job? = null
    private val recent = object : LinkedHashMap<ReadAddress, Pcm>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ReadAddress, Pcm>?) = size > 6
    }

    val current: Utterance? get() = buffer.getOrNull(index)

    suspend fun start(source: ReadSource, speech: Speech, from: ReadAddress?, speed: Float) = lock.withLock {
        cancelJobs()
        idleClose?.cancel(); idleClose = null
        this.speech?.takeIf { it !== speech }?.close()
        this.source = source
        this.speech = speech
        this.speed = speed
        buffer.clear(); recent.clear(); exhausted = false
        val located = from?.let { source.locate(it) }
        fetchMore(located)
        index = 0
        output.flush()
        output.play()
        _playing.value = true
        launchJobs()
    }

    fun play() {
        if (speech == null || _playing.value) return
        output.play()
        _playing.value = true
        launchJobs()
    }

    /** Stops the sound now; the next [play] starts at the beginning of the sentence that was playing. */
    fun pause() {
        if (!_playing.value) return
        cancelJobs()
        output.pause()
        output.flush()
        _playing.value = false
    }

    suspend fun nextParagraph() = lock.withLock {
        val here = buffer.getOrNull(index) ?: return@withLock
        var i = index
        while (i >= buffer.size || buffer[i].paragraph <= here.paragraph) {
            if (i >= buffer.size && !fetchMore(null)) return@withLock
            if (i < buffer.size && buffer[i].paragraph > here.paragraph) break
            i++
        }
        restartAt(i)
    }

    /** The start of this paragraph, or of the previous one when already at its first sentence. */
    suspend fun previousParagraph() = lock.withLock {
        val here = buffer.getOrNull(index) ?: return@withLock
        val firstOfThis = buffer.indexOfFirst { it.paragraph == here.paragraph }
        val target = if (firstOfThis < index) firstOfThis else buffer.indexOfFirst { it.paragraph == here.paragraph - 1 }.coerceAtLeast(0)
        restartAt(target)
    }

    suspend fun previousSentence() = lock.withLock { restartAt((index - 1).coerceAtLeast(0)) }

    suspend fun jumpTo(address: ReadAddress) = lock.withLock {
        var i = buffer.indexOfFirst { it.address == address }
        if (i < 0) {
            val src = source ?: return@withLock
            val located = src.locate(address) ?: return@withLock
            buffer.clear(); exhausted = false
            if (!fetchMore(located)) return@withLock
            i = 0
        }
        restartAt(i)
    }

    fun setSpeed(speed: Float) { this.speed = speed; recent.clear() }

    suspend fun stop() = lock.withLock {
        cancelJobs()
        output.pause()
        output.flush()
        _playing.value = false
        _progress.value = null
        source = null
        buffer.clear(); recent.clear()
        val s = speech ?: return@withLock
        idleClose = scope.launch {
            delay(idleCloseMs)
            lock.withLock { if (speech === s) { s.close(); speech = null } }
        }
    }

    /** The source's utterances from [from] (or where the buffer ends); false when there are none. */
    private suspend fun fetchMore(from: ReadAddress?): Boolean {
        if (exhausted) return false
        val src = source ?: return false
        val start = from ?: buffer.lastOrNull()?.address
        val more = src.utterances(start, batch)
        // When continuing from the buffer's last address the source repeats it; drop the overlap.
        val fresh = if (from == null && buffer.isNotEmpty()) more.dropWhile { it.address == buffer.last().address } else more
        if (fresh.isEmpty()) { exhausted = true; return false }
        buffer += fresh
        return true
    }

    private fun restartAt(i: Int) {
        val wasPlaying = _playing.value
        cancelJobs()
        output.flush()
        index = i
        _progress.value = buffer.getOrNull(i)?.let { VoiceProgress(it.address, it, i) }
        if (wasPlaying) launchJobs()
    }

    private fun cancelJobs() { jobs?.cancel(); jobs = null }

    private fun launchJobs() {
        val s = speech ?: return
        val spoken = Channel<Pair<Int, Pcm?>>(capacity = (lookAheadSentences - 1).coerceAtLeast(0))
        val queuedMs = MutableStateFlow(0L)
        val from = index
        jobs = scope.launch {
            val producer = launch { produce(s, from, spoken, queuedMs) }
            try {
                for ((i, pcm) in spoken) {
                    index = i
                    val u = buffer[i]
                    _progress.value = VoiceProgress(u.address, u, i)
                    if (pcm != null) {
                        output.open(pcm.sampleRate)
                        output.write(pcm)
                        queuedMs.value -= pcm.durationMs
                        if (u.pauseAfterMs > 0) output.write(Pcm.silence(u.pauseAfterMs, pcm.sampleRate))
                    }
                }
                // The source is finished.
                index = buffer.size
                _playing.value = false
                _ended.tryEmit(Unit)
            } finally {
                producer.cancel()
            }
        }
    }

    /** Synthesizes from [from] onward into [spoken], fetching more of the source as it goes, until the source ends. */
    private suspend fun produce(s: Speech, from: Int, spoken: SendChannel<Pair<Int, Pcm?>>, queuedMs: MutableStateFlow<Long>) {
        var i = from
        while (true) {
            if (i >= buffer.size) {
                val got = lock.withLock { fetchMore(null) }
                if (!got) { spoken.close(); return }
            }
            val u = buffer[i]
            // Wait while more than lookAheadMs of audio is synthesized but unheard.
            while (queuedMs.value > lookAheadMs) delay(50)
            val pcm = recent[u.address] ?: synthesize(s, u)
            if (pcm != null) { recent[u.address] = pcm; queuedMs.value += pcm.durationMs }
            spoken.send(i to pcm)
            i++
        }
    }

    /** One sentence's audio, or null when the engine failed on it (reported, then skipped). */
    private suspend fun synthesize(s: Speech, u: Utterance): Pcm? {
        if (u.kind == UtteranceKind.CODE_OMITTED && u.text.isEmpty()) return null
        return try {
            val chunks = s.synthesize(u.text, speed).toList()
            if (chunks.isEmpty()) null else Pcm(Wav.concat(chunks.map { it.samples }), chunks.first().sampleRate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _errors.tryEmit(e as? SpeechException ?: SpeechException("Synthesis failed: ${e.message}", e))
            null
        }
    }
}
