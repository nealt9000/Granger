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

/**
 * The sentence being spoken: what the readers mark and the notification shows. [previous] and [next] are the
 * sentences around it for the voice's full view (Plan V2); [next] is null at the end of what the source has handed over.
 */
data class VoiceProgress(val address: ReadAddress, val utterance: Utterance, val index: Int, val previous: String? = null, val next: String? = null)

/** One sentence's cost: how long the engine took and how much audio it made (the real-time factor is their ratio). */
data class SentenceStat(val address: ReadAddress, val chars: Int, val synthMs: Long, val audioMs: Long) {
    val rtf: Double get() = if (audioMs <= 0) Double.NaN else synthMs.toDouble() / audioMs
}

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
    private val _stats = MutableSharedFlow<SentenceStat>(extraBufferCapacity = 64)
    /** Every sentence synthesized (not served from the cache), for the log and the screen-off run. */
    val stats: SharedFlow<SentenceStat> = _stats.asSharedFlow()
    private val _held = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The voice stopped itself before a sentence [holdBefore] named, once everything before it was heard. */
    val held: SharedFlow<Unit> = _held.asSharedFlow()

    /**
     * Asked before each sentence is heard (Plan U2, End of chapter): true stops the voice there, after the sentences
     * before it have played out, with [progress] on the sentence not read (so play goes on from it).
     */
    var holdBefore: (suspend (Utterance) -> Boolean)? = null

    private val lock = Mutex()
    private var source: ReadSource? = null
    private var speech: Speech? = null
    private var speed = 1f
    private val buffer = ArrayList<Utterance>()      // what the source has handed over so far, in order
    private var exhausted = false                    // the source said "no more"
    private var index = 0                            // the sentence playing or to play next
    private var jobs: Job? = null
    private var idleClose: Job? = null
    // The last few sentences' audio, by address and the speed they were made at, so a step back (or a speed change
    // and back) replays without a second synthesis.
    private val recent = object : LinkedHashMap<Pair<ReadAddress, Float>, Pcm>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<ReadAddress, Float>, Pcm>?) = size > 6
    }

    val current: Utterance? get() = buffer.getOrNull(index)

    /** Queues [source] from [from] with [speech]; speaks at once unless [autoplay] is false (then [play] starts it). */
    suspend fun start(source: ReadSource, speech: Speech, from: ReadAddress?, speed: Float, autoplay: Boolean = true) = lock.withLock {
        val previous = jobs
        cancelJobs()
        idleClose?.cancel(); idleClose = null
        retire(this.speech?.takeIf { it !== speech }, previous)
        this.source = source
        this.speech = speech
        this.speed = speed
        buffer.clear(); recent.clear(); exhausted = false
        val located = from?.let { source.locate(it) }
        fetchMore(located)
        index = 0
        output.flush()
        _progress.value = progressAt(0)
        if (autoplay) {
            output.play()
            _playing.value = true
            launchJobs()
        } else {
            _playing.value = false
        }
    }

    suspend fun nextSentence() = lock.withLock { if (index + 1 < buffer.size || fetchMore(null)) restartAt(index + 1) }

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

    fun setSpeed(speed: Float) { this.speed = speed }

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
        _progress.value = progressAt(i)
        if (wasPlaying) launchJobs()
    }

    /** The progress for sentence [i] of the buffer, with its neighbours; null past the end. */
    private fun progressAt(i: Int): VoiceProgress? =
        buffer.getOrNull(i)?.let { VoiceProgress(it.address, it, i, buffer.getOrNull(i - 1)?.text, buffer.getOrNull(i + 1)?.text) }

    private fun cancelJobs() { jobs?.cancel(); jobs = null }

    /**
     * Closes [old] once [itsJobs] have ended. A cancelled producer may still be inside the engine's native call (a
     * sentence is made in one piece, and a slow voice takes seconds over it); freeing the engine under it crashed the
     * process (SIGSEGV in sherpa-onnx, 2026-10-22, on a voice switch mid-sentence).
     */
    private fun retire(old: Speech?, itsJobs: Job?) {
        if (old == null) return
        scope.launch {
            itsJobs?.join()
            old.close()
        }
    }

    private fun launchJobs() {
        val s = speech ?: return
        val spoken = Channel<Pair<Int, Pcm?>>(capacity = (lookAheadSentences - 1).coerceAtLeast(0))
        val queuedMs = MutableStateFlow(0L)
        val from = index
        jobs = scope.launch {
            val producer = launch { produce(s, from, spoken, queuedMs) }
            try {
                var writtenMs = 0L                           // since the output was flushed (every launch follows a flush)
                for ((i, pcm) in spoken) {
                    val u = buffer[i]
                    if (holdBefore?.invoke(u) == true) {
                        drain(writtenMs)
                        index = i
                        _progress.value = progressAt(i)
                        output.pause()
                        output.flush()
                        _playing.value = false
                        _held.tryEmit(Unit)
                        return@launch
                    }
                    index = i
                    _progress.value = progressAt(i)
                    if (pcm != null) {
                        output.open(pcm.sampleRate)
                        output.write(pcm)
                        queuedMs.value -= pcm.durationMs
                        writtenMs += pcm.durationMs
                        if (u.pauseAfterMs > 0) { output.write(Pcm.silence(u.pauseAfterMs, pcm.sampleRate)); writtenMs += u.pauseAfterMs }
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

    /** Waits, bounded, until the output has played the [writtenMs] written to it, so a hold doesn't cut the last sentence short. */
    private suspend fun drain(writtenMs: Long) {
        val limit = (writtenMs - output.playedMs).coerceAtLeast(0L) + DRAIN_SLACK_MS
        var waited = 0L
        while (output.playedMs < writtenMs && waited < limit) { delay(DRAIN_POLL_MS); waited += DRAIN_POLL_MS }
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
            val at = speed
            val pcm = recent[u.address to at] ?: synthesize(s, u)
            if (pcm != null) { recent[u.address to at] = pcm; queuedMs.value += pcm.durationMs }
            spoken.send(i to pcm)
            i++
        }
    }

    /** One sentence's audio, or null when the engine failed on it (reported, then skipped). */
    private suspend fun synthesize(s: Speech, u: Utterance): Pcm? {
        if (u.kind == UtteranceKind.CODE_OMITTED && u.text.isEmpty()) return null
        return try {
            val start = System.nanoTime()
            val chunks = s.synthesize(u.text, speed, u.quoteOpen).toList()
            val pcm = if (chunks.isEmpty()) null else Pcm(Wav.concat(chunks.map { it.samples }), chunks.first().sampleRate)
            _stats.tryEmit(SentenceStat(u.address, u.text.length, (System.nanoTime() - start) / 1_000_000, pcm?.durationMs ?: 0))
            pcm
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _errors.tryEmit(e as? SpeechException ?: SpeechException("Synthesis failed: ${e.message}", e))
            null
        }
    }

    private companion object {
        const val DRAIN_POLL_MS = 50L
        const val DRAIN_SLACK_MS = 1_000L
    }
}
