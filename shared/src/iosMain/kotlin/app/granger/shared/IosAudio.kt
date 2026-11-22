@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.granger.shared

import kotlin.concurrent.AtomicLong
import kotlin.math.log10
import kotlinx.cinterop.get
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioEngineConfigurationChangeNotification
import platform.AVFAudio.AVAudioEngineManualRenderingModeOffline
import platform.AVFAudio.AVAudioEngineManualRenderingStatusSuccess
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioPlayerNodeCompletionDataPlayedBack
import platform.AVFAudio.AVAudioUnitEQ
import platform.AVFAudio.AVAudioUnitTimePitch
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSURL

/**
 * The iPhone's one audio graph (Plan I6 call 1): a single `AVAudioEngine` for the voice and for music and audiobooks,
 * so music under the voice is one mix.
 * - The voice: [voice] → main mixer, in the voice's own format (mono, the voice's rate). Changing the rate means a
 *   reconnect, which needs the engine stopped (a running reconnect raises), so it's done only when a voice of another
 *   rate opens; [onRestart] puts the media back where it was.
 * - Media: [media] → [pitch] (speed) → [eq] (ReplayGain and the audiobook boost, above 1.0 as well) → main mixer, at a
 *   fixed [MEDIA_RATE] stereo. A file of any rate or channel count is converted by the player node, so the chain never
 *   reconnects and the next file can be scheduled back to back (gapless).
 *
 * Tests can render it offline ([renderOffline]) to measure what comes out exactly.
 */
class AudioGraph(offline: Boolean) {
    /** A graph for the speaker (the app's). */
    constructor() : this(false)

    val engine = AVAudioEngine()
    val media = AVAudioPlayerNode()
    val pitch = AVAudioUnitTimePitch()
    val eq = AVAudioUnitEQ(numberOfBands = 0u)
    val voice = AVAudioPlayerNode()
    val lock = NSRecursiveLock()
    val mediaFormat = AVAudioFormat(standardFormatWithSampleRate = MEDIA_RATE, channels = 2u)
    private var voiceRate = 0
    private val offline = offline

    /** Told when the engine was stopped and started again (a voice rate change, a route change): the nodes replay. */
    private val restartHandlers = ArrayList<() -> Unit>()
    private val beforeStopHandlers = ArrayList<() -> Unit>()

    init {
        engine.attachNode(media); engine.attachNode(pitch); engine.attachNode(eq); engine.attachNode(voice)
        engine.connect(media, pitch, mediaFormat)
        engine.connect(pitch, eq, mediaFormat)
        engine.connect(eq, engine.mainMixerNode, mediaFormat)
        connectVoiceNode(22050)
        if (offline) {
            // Tests: render offline at MEDIA_RATE stereo, set before the graph is prepared.
            engine.enableManualRenderingMode(AVAudioEngineManualRenderingModeOffline, mediaFormat, OFFLINE_CHUNK.toUInt(), null)
            check(engine.isInManualRenderingMode()) { "offline rendering refused" }
        }
        engine.prepare()
        // A route or format change stops the engine behind our back: start it again and replay what played.
        NSNotificationCenter.defaultCenter.addObserverForName(AVAudioEngineConfigurationChangeNotification, engine, NSOperationQueue.mainQueue) { _ ->
            locked { if (!offline) { beforeStopHandlers.forEach { it() }; startEngine(); restartHandlers.forEach { it() } } }
        }
    }

    inline fun <T> locked(block: () -> T): T { lock.lock(); try { return block() } finally { lock.unlock() } }

    /** [before] runs just before a deliberate stop (the media notes its place), [after] once it runs again. */
    fun onRestart(before: () -> Unit, after: () -> Unit) = locked { beforeStopHandlers += before; restartHandlers += after }

    val running: Boolean get() = engine.running

    fun startEngine(): Boolean = locked {
        if (!engine.running) engine.startAndReturnError(null)
        engine.running
    }

    private fun connectVoiceNode(rate: Int) {
        engine.connect(voice, engine.mainMixerNode, AVAudioFormat(standardFormatWithSampleRate = rate.toDouble(), channels = 1u))
        voiceRate = rate
    }

    /** Readies the voice node for mono [rate]; another rate stops the engine for the reconnect and starts it again. */
    fun connectVoice(rate: Int) = locked {
        if (rate == voiceRate) return@locked
        val wasRunning = engine.running
        if (wasRunning) { beforeStopHandlers.forEach { it() }; engine.stop() }
        voice.stop()
        connectVoiceNode(rate)
        engine.prepare()
        if (wasRunning) { startEngine(); restartHandlers.forEach { it() } }
    }

    /** Tests: renders [frames] offline and returns the left channel. */
    fun renderOffline(frames: Int): FloatArray = locked {
        val buffer = AVAudioPCMBuffer(pCMFormat = engine.manualRenderingFormat, frameCapacity = OFFLINE_CHUNK.toUInt())!!
        val out = FloatArray(frames)
        var at = 0
        startEngine()
        while (at < frames) {
            val n = minOf(OFFLINE_CHUNK, frames - at)
            val status = engine.renderOffline(n.toUInt(), buffer, null)
            if (status != AVAudioEngineManualRenderingStatusSuccess) break
            val p = buffer.floatChannelData!![0]!!
            for (i in 0 until buffer.frameLength.toInt()) out[at + i] = p[i]
            at += buffer.frameLength.toInt()
        }
        out
    }

    companion object {
        const val MEDIA_RATE = 44100.0
        private const val OFFLINE_CHUNK = 1024
    }
}

/** One file for the media node: its [key] (the hub's index), [path], and its gain in dB (ReplayGain + boost). */
class MediaFile(val key: Int, val path: String, val gainDb: Float)

/**
 * Plays files on the graph's media node, the next one scheduled back to back so albums are gapless (the spike's
 * measure: 0.02 ms where two files meet). The hub calls in from one coroutine at a time; the node's completion
 * callbacks arrive on an audio thread and are passed on through [events] as the item that is now heard.
 *
 * Position: the node's own clock (frames heard since its last start, in [AudioGraph.MEDIA_RATE]) maps to a file
 * through the scheduled segments, so speed doesn't change it (the time-pitch unit sits after the node).
 */
class MediaFilePlayer(private val graph: AudioGraph) {
    sealed interface Event {
        /** [next] (its key) is now heard after the one before ended. */
        data class Advanced(val next: Int) : Event
        /** The last scheduled file ended with nothing after it. */
        data object Ended : Event
    }

    /** Called on an audio thread; the hub hops onto its own. */
    var events: (Event) -> Unit = {}

    private class Segment(var file: MediaFile, val startMs: Long, val lengthMs: Long, val nodeStartMs: Long)

    private val segments = ArrayList<Segment>()
    private val generation = AtomicLong(0)
    private val files = HashMap<String, AVAudioFile>()
    private var playing = false
    private var held: Pair<MediaFile, Long>? = null

    init {
        graph.onRestart(before = { graph.locked { held = current()?.let { it.file to positionMs() } } },
                        after = { graph.locked { held?.let { (f, at) -> load(f, at); if (playing) graph.media.play() }; held = null } })
    }

    private fun open(path: String): AVAudioFile? = files[path] ?: run {
        // A file uri (`file:///…`), a Music library song (`ipod-library://…`, Plan I6 Task 6), or a plain path.
        val url = if (path.contains("://")) NSURL.URLWithString(path) else NSURL.fileURLWithPath(path)
        url?.let { AVAudioFile(forReading = it, error = null) }?.also {
            if (files.size > 4) files.clear()
            files[path] = it
        }
    }

    /** The file's length in ms, or null when it can't be opened. */
    fun durationMs(path: String): Long? = open(path)?.let { (it.length * 1000 / it.processingFormat.sampleRate).toLong() }

    /** Can the file be opened as audio at all? */
    fun canOpen(path: String): Boolean = open(path) != null

    // The node's clock as last read: a paused node gives no player time, so the place it paused at is kept here.
    private var lastNodeMs = 0L

    private fun nodeMs(): Long = graph.locked {
        val node = graph.media
        val t = node.lastRenderTime ?: return@locked lastNodeMs
        // Asking with a time that holds neither clock raises (before the first render).
        if (!t.sampleTimeValid && !t.hostTimeValid) return@locked lastNodeMs
        val p = node.playerTimeForNodeTime(t) ?: return@locked lastNodeMs
        // The time-pitch unit pulls audio ahead of what is heard by its latency: take it off.
        val ahead = (graph.pitch.latency * 1000).toLong()
        lastNodeMs = ((p.sampleTime * 1000.0 / p.sampleRate).toLong() - ahead).coerceAtLeast(0L)
        lastNodeMs
    }

    private fun current(): Segment? = graph.locked {
        if (segments.isEmpty()) return@locked null
        val now = nodeMs()
        segments.lastOrNull { it.nodeStartMs <= now } ?: segments.first()
    }

    /** The key of the file being heard, or null with nothing loaded. */
    val currentKey: Int? get() = current()?.file?.key

    /** Milliseconds into the file being heard. */
    fun positionMs(): Long = graph.locked {
        val s = current() ?: return@locked held?.second ?: 0L
        (s.startMs + (nodeMs() - s.nodeStartMs).coerceIn(0L, s.lengthMs)).coerceAtLeast(0L)
    }

    /** Starts over with [file] at [positionMs] (a new item, a seek); paused or playing as before. False if it can't open. */
    fun load(file: MediaFile, positionMs: Long): Boolean = graph.locked {
        val f = open(file.path) ?: return@locked false
        generation.incrementAndGet()
        graph.media.stop()
        segments.clear()
        lastNodeMs = 0L
        val rate = f.processingFormat.sampleRate
        val total = f.length
        val start = (positionMs * rate / 1000).toLong().coerceIn(0L, (total - 1).coerceAtLeast(0L))
        schedule(file, f, start)
        graph.eq.globalGain = clampDb(file.gainDb)
        if (playing) { graph.startEngine(); graph.media.play() }
        true
    }

    /** Schedules [file] to follow the one loaded, back to back (gapless); null drops a following file not yet heard. */
    fun setNext(file: MediaFile?) = graph.locked {
        val head = segments.firstOrNull() ?: return@locked
        if (segments.size > 1) {
            // The same file already follows: only its key may have changed (a queue edit renumbered it).
            if (file != null && segments[1].file.path == file.path) { segments[1].file = file; return@locked }
            // A node can't unschedule one segment: start the current one again where it is, then add the new next.
            val at = positionMs()
            load(head.file, at)
        }
        if (file == null) return@locked
        val f = open(file.path) ?: return@locked
        schedule(file, f, 0L)
    }

    private fun schedule(file: MediaFile, f: AVAudioFile, startFrame: Long) {
        val rate = f.processingFormat.sampleRate
        val frames = (f.length - startFrame).coerceAtLeast(0L)
        // A following segment starts on the node's clock where the one before it ends.
        val nodeStart = segments.lastOrNull()?.let { it.nodeStartMs + it.lengthMs } ?: 0L
        val segment = Segment(file, (startFrame * 1000 / rate).toLong(), (frames * 1000 / rate).toLong(), nodeStart)
        segments += segment
        val gen = generation.value
        graph.media.scheduleSegment(f, startFrame, frames.toUInt(), null, AVAudioPlayerNodeCompletionDataPlayedBack) { _ ->
            if (generation.value != gen) return@scheduleSegment
            val event = graph.locked {
                if (generation.value != gen || segments.firstOrNull() !== segment) return@locked null
                segments.removeAt(0)
                val next = segments.firstOrNull()
                if (next == null) Event.Ended else { graph.eq.globalGain = clampDb(next.file.gainDb); Event.Advanced(next.file.key) }
            }
            event?.let(events)
        }
    }

    /** The file heard keeps playing under a new key (a queue edit renumbered it). */
    fun rekeyCurrent(key: Int) = graph.locked {
        val s = current() ?: return@locked
        s.file = MediaFile(key, s.file.path, s.file.gainDb)
    }

    fun play() = graph.locked {
        playing = true
        graph.startEngine()
        graph.media.play()
    }

    fun pause() = graph.locked {
        nodeMs()   // the place it pauses at
        playing = false
        graph.media.pause()
    }

    /** Empties the node. */
    fun clear() = graph.locked {
        generation.incrementAndGet()
        playing = false
        graph.media.stop()
        segments.clear()
        lastNodeMs = 0L
        held = null
    }

    val isPlaying: Boolean get() = playing

    /** 0–1: the sleep timer's fade, and the mix level under the voice. */
    var volume: Float
        get() = graph.media.volume
        set(value) { graph.media.volume = value.coerceIn(0f, 1f) }

    /** Speed, pitch kept (audiobooks 0.5–3.0). */
    var speed: Float
        get() = graph.pitch.rate
        set(value) { graph.pitch.rate = value }

    /** The gain of what plays now, for a change to the boost while it plays. */
    fun setGainDb(db: Float) = graph.locked {
        graph.eq.globalGain = clampDb(db)
    }

    companion object {
        fun clampDb(db: Float): Float = db.coerceIn(-96f, 24f)
        /** A linear factor (ReplayGain's) as dB. */
        fun dbOf(factor: Float): Float = if (factor <= 0f) -96f else (20.0 * log10(factor.toDouble())).toFloat()
    }
}
