package app.granger.readaloud

import app.granger.UiText
import app.granger.core.library.PlaybackDao
import app.granger.core.library.VoiceState
import app.granger.core.player.SleepSetting
import app.granger.core.player.SleepTimer
import app.granger.core.player.voice.ReadAloudEngine
import app.granger.core.player.voice.SpeechOutput
import app.granger.core.player.voice.VoiceProgress
import app.granger.core.speech.InstalledVoices
import app.granger.core.speech.ReadAddress
import app.granger.core.speech.ReadChapter
import app.granger.core.speech.ReadSource
import app.granger.core.speech.Speech
import app.granger.core.speech.SpeechException
import app.granger.core.speech.VoiceId
import app.granger.settings.VoicePrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What the voice is reading: [title] (an article, a book; [sourceKey] names it as the saved place does), at [sentence]
 * of [paragraph], in [section] when the source has sections. [ended]: the source ran out; play reads it again.
 * Android's `NowPlaying.Voice` without the music under it (Plan V2's mix comes with music, I6).
 */
data class VoiceNow(
    val sourceKey: String, val title: String, val section: String?, val sentence: String, val paragraph: Int,
    val speed: Float, val voiceId: VoiceId, val ended: Boolean = false,
)

/** The voice as the screens show it: what's loaded, whether it plays, the sleep timer. */
data class VoiceUi(
    val now: VoiceNow? = null,
    val playing: Boolean = false,
    /** The After timer's time left; null with no timer, or for End of chapter (whose end isn't known in time). */
    val sleepRemainingMs: Long? = null,
    /** End of chapter is set: the voice stops before the next chapter's first sentence. */
    val sleepAtChapterEnd: Boolean = false,
) {
    val sleeping: Boolean get() = sleepRemainingMs != null || sleepAtChapterEnd

    companion object {
        val IDLE = VoiceUi()
    }
}

/** The last thing the voice read, at the sentence it stopped on (Android's `SavedVoice`). */
data class LastRead(val sourceKey: String, val title: String, val section: String?, val address: ReadAddress, val speed: Float, val voiceId: VoiceId?)

/** A text's chapters (a book's contents, an article's sections) and the one the voice is in (null before the first). */
data class ReadChapters(val chapters: List<ReadChapter>, val current: Int?)

/**
 * The voice's player on the iPhone (Plan I5 Task 8): Android's `PlayerEngine` in its voice mode, without ExoPlayer, the
 * media session or the music. It reads a [ReadSource] through the shared [ReadAloudEngine] into [output] (the
 * iPhone's AVAudioEngine), keeps the place in `voice_states` as each sentence starts ([PlaybackDao], the store both
 * phones share), and runs the sleep timer (After a while with Android's fade, or End of chapter by the source's
 * chapters). Calls are fire-and-forget and run in order on [scope], as Android's hub's do. The speech engine opens on the
 * first play, so loading a place costs nothing until then; it's closed [ReadAloudEngine.idleCloseMs] after a stop.
 *
 * [onAudio] is told when the voice starts and stops wanting the audio session (the iPhone activates AVAudioSession).
 */
class VoicePlayer(
    private val output: SpeechOutput,
    private val voices: InstalledVoices,
    private val prefs: VoicePrefs,
    private val playback: PlaybackDao,
    private val sources: suspend (key: String, title: String) -> ReadSource?,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val onAudio: (Boolean) -> Unit = {},
    idleCloseMs: Long = 120_000,
) {
    private val engine = ReadAloudEngine(output, scope, idleCloseMs = idleCloseMs)
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private val _ui = MutableStateFlow(VoiceUi.IDLE)
    val ui: StateFlow<VoiceUi> = _ui.asStateFlow()

    /** The sentence the voice is on, null while nothing is read; the readers mark and follow from this. */
    val progress: StateFlow<VoiceProgress?> get() = engine.progress

    private val _notices = MutableSharedFlow<UiText>(extraBufferCapacity = 4)
    /** One-off lines for the user when the voice had to give up (no voice, or one that wouldn't start). */
    val notices: SharedFlow<UiText> = _notices.asSharedFlow()

    /** The last thing read, from the store: what Listen offers to resume while nothing is loaded. */
    val lastRead: StateFlow<LastRead?> = playback.observeVoiceStates().map { rows ->
        rows.firstOrNull()?.let { r -> ReadAddress.decode(r.address)?.let { LastRead(r.sourceKey, r.title, r.section, it, r.speed, VoiceId.decode(r.voiceId)) } }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private var source: ReadSource? = null
    private var voiceId: VoiceId? = null
    private var speed = 1f
    private var from: ReadAddress? = null
    private var section: String? = null
    private var open = false
    private var speech: Speech? = null
    private var ended = false

    private val sleep = SleepTimer(now)
    private var sleepTicker: Job? = null
    private var sleepChapter: Int? = null

    init {
        scope.launch { for (c in commands) runCatching { c() }.onFailure { if (it is CancellationException) throw it } }
        scope.launch { engine.progress.collect { p -> if (p != null) submit { onProgress() } } }
        scope.launch { engine.playing.collect { submit { refresh() } } }
        scope.launch { engine.ended.collect { submit { onEnded() } } }
        scope.launch { engine.held.collect { submit { cancelSleep(); save(); refresh() } } }
        engine.holdBefore = { u ->
            val chapter = sleepChapter
            val src = source
            chapter != null && src != null && (runCatching { src.chapterAt(u.address) }.getOrNull() ?: chapter) != chapter
        }
    }

    private fun submit(command: suspend () -> Unit) { commands.trySend(command) }

    /** Runs [block] in turn with the calls before it and returns its result. */
    private suspend fun <T> inTurn(block: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        submit {
            try { result.complete(block()) } catch (e: Throwable) { result.completeExceptionally(e); if (e is CancellationException) throw e }
        }
        return result.await()
    }

    // ---- what the screens call ----

    /**
     * Reads [source] aloud from [from] (its start when null) with [voice], else the chosen voice, else the first one
     * installed. Whatever was read is saved first. Nothing happens when no voice can speak (a notice says so).
     */
    fun readAloud(source: ReadSource, from: ReadAddress? = null, voice: VoiceId? = null) = submit {
        stopCurrent()
        load(source, from, voice, prefs.speed.value)
        startPlaying()
    }

    /** Reopens the last thing read ([lastRead]) at its sentence and plays it; false when its source is gone. */
    suspend fun resumeLast(): Boolean = inTurn {
        val last = lastRead.value ?: return@inTurn false
        val src = runCatching { sources(last.sourceKey, last.title) }.getOrNull()
        if (src == null) { playback.deleteVoiceState(last.sourceKey); return@inTurn false }
        stopCurrent()
        load(src, last.address, voices.forResume(prefs.voiceId.value, last.voiceId), prefs.speed.value)
        startPlaying()
        true
    }

    fun playPause() = submit { if (_ui.value.playing) pauseNow() else startPlaying() }
    fun play() = submit { startPlaying() }
    fun pause() = submit { pauseNow() }

    /** Stops the voice and empties the player, saving its place. */
    fun stop() = submit { stopCurrent(); refresh() }

    fun nextSentence() = submit { if (open) engine.nextSentence() }
    fun previousSentence() = submit { if (open) engine.previousSentence() }
    fun nextParagraph() = submit { if (open) engine.nextParagraph() }
    fun previousParagraph() = submit { if (open) engine.previousParagraph() }

    /** Moves the voice to [address] of its source (Read from here, a chapter); it keeps playing or stays paused. */
    fun jumpTo(address: ReadAddress) = submit { jump(address) }

    fun jumpToChapter(index: Int) = submit {
        val address = source?.let { runCatching { it.chapterStart(index) }.getOrNull() } ?: return@submit
        jump(address)
    }

    /** The source's chapters and the one the voice is in; null with nothing loaded or no chapters. */
    suspend fun chapters(): ReadChapters? = inTurn {
        val src = source ?: return@inTurn null
        val at = engine.progress.value?.address ?: from
        runCatching { ReadChapters(src.chapters(), at?.let { src.chapterAt(it) }) }.getOrNull()?.takeIf { it.chapters.isNotEmpty() }
    }

    /** 0.7–2.0 for the text being read; Settings' speed is the default for the next. */
    fun setSpeed(value: Float) = submit {
        speed = value.coerceIn(VoicePrefs.MIN_SPEED, VoicePrefs.MAX_SPEED)
        engine.setSpeed(speed)
        save(); refresh()
    }

    /** Switches the reading voice to [voice] (an installed one) at the current sentence. */
    fun setVoice(voice: VoiceId) = submit {
        if (source == null || voice == voiceId) return@submit
        if (voices.voices.value.none { it.id == voice }) return@submit
        val wasPlaying = _ui.value.playing
        from = engine.progress.value?.address ?: from
        if (open) engine.stop()
        open = false
        speech = null
        voiceId = voice
        save(); refresh()
        if (wasPlaying) startPlaying()
    }

    fun startSleepTimer(setting: SleepSetting) = submit {
        if (source == null) return@submit
        output.volume = 1f
        if (setting == SleepSetting.EndOfChapter) {
            val at = engine.progress.value?.address ?: from
            sleepChapter = at?.let { a -> source?.let { runCatching { it.chapterAt(a) }.getOrNull() } } ?: -1
            sleep.start(setting, estimated = true) { null }
        } else {
            sleepChapter = null
            sleep.start(setting) { null }
            tickSleep()
        }
        refresh()
    }

    fun cancelSleepTimer() = submit { cancelSleep(); refresh() }

    // ---- inside ----

    private suspend fun load(src: ReadSource, at: ReadAddress?, wanted: VoiceId?, defaultSpeed: Float): Boolean {
        if (voices.voices.value.isEmpty()) voices.refresh()
        val voice = voices.installed(wanted) ?: voices.preferred(prefs.voiceId.value)
        if (voice == null) { _notices.tryEmit(UiText.Res("player_voice_none")); return false }
        source = src
        voiceId = voice.id
        speed = defaultSpeed.coerceIn(VoicePrefs.MIN_SPEED, VoicePrefs.MAX_SPEED)
        from = at
        open = false
        ended = false
        section = at?.let { runCatching { src.sectionTitle(it) }.getOrNull() }
        refresh()
        return true
    }

    private suspend fun startPlaying() {
        val src = source ?: return
        if (!open) {
            val id = voiceId ?: return
            val voice = voices.voices.value.firstOrNull { it.id == id } ?: voices.preferred(prefs.voiceId.value)?.also { voiceId = it.id } ?: return
            val s = speech ?: try {
                voices.open(voice)
            } catch (e: SpeechException) {
                _notices.tryEmit(UiText.Res("player_voice_failed"))
                return
            }
            speech = s
            onAudio(true)
            engine.start(src, s, if (ended) null else from, speed, autoplay = true)
            open = true
            ended = false
        } else if (ended) {
            ended = false
            onAudio(true)
            engine.start(src, speech ?: return, null, speed, autoplay = true)
        } else {
            onAudio(true)
            engine.play()
        }
        refresh()
    }

    private fun pauseNow() {
        if (!open) return
        engine.pause()
        from = engine.progress.value?.address ?: from
        scope.launch { save() }
        onAudio(false)
        refresh()
    }

    private suspend fun jump(address: ReadAddress) {
        if (source == null) return
        if (open) engine.jumpTo(address) else from = address
        if (sleepChapter != null) {
            sleepChapter = source?.let { runCatching { it.chapterAt(address) }.getOrNull() } ?: -1
        }
        save(); refresh()
    }

    private suspend fun stopCurrent() {
        if (source == null) return
        save()
        cancelSleep()
        if (open) engine.stop()
        open = false; ended = false
        source = null; speech = null; from = null; section = null
        onAudio(false)
    }

    private suspend fun onProgress() {
        val p = engine.progress.value ?: return
        val src = source ?: return
        from = p.address
        val s = runCatching { src.sectionTitle(p.address) }.getOrNull()
        section = s
        save()
        refresh()
    }

    private suspend fun onEnded() {
        ended = true
        if (sleepChapter != null) cancelSleep()
        save()
        onAudio(false)
        refresh()
    }

    private suspend fun save() {
        val src = source ?: return
        if (!src.remembered) return
        val at = engine.progress.value?.address ?: from ?: return
        val id = voiceId ?: return
        runCatching { playback.upsertVoiceState(VoiceState(src.key, at.encode(), speed, src.title, section, id.encode(), now())) }
    }

    private fun tickSleep() {
        sleepTicker?.cancel()
        sleepTicker = scope.launch {
            while (isActive) {
                val left = sleep.remainingMs ?: break
                output.volume = sleep.volume() ?: 1f
                if (sleep.expired()) {
                    submit { pauseNow(); cancelSleep(); refresh() }
                    break
                }
                _ui.value = _ui.value.copy(sleepRemainingMs = left)
                delay(if (left <= FADE_WINDOW_MS) 250 else 1_000)
            }
        }
    }

    private fun cancelSleep() {
        sleep.cancel()
        sleepChapter = null
        sleepTicker?.cancel(); sleepTicker = null
        output.volume = 1f
    }

    private fun refresh() {
        val src = source
        val id = voiceId
        val p = engine.progress.value
        _ui.value = VoiceUi(
            now = if (src == null || id == null) null else VoiceNow(
                src.key, src.title, section, p?.utterance?.text.orEmpty(), p?.utterance?.paragraph ?: 0, speed, id, ended,
            ),
            playing = open && engine.playing.value,
            sleepRemainingMs = if (sleepChapter == null) sleep.remainingMs else null,
            sleepAtChapterEnd = sleepChapter != null,
        )
    }

    private companion object {
        const val FADE_WINDOW_MS = 10_000L
    }
}
