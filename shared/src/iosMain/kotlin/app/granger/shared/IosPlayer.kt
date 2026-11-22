package app.granger.shared

import app.granger.core.library.AudioBookmark
import app.granger.core.library.Audiobook
import app.granger.core.library.AudiobookState
import app.granger.core.library.MediaLibrary
import app.granger.core.library.MediaOrigin
import app.granger.core.library.MusicState
import app.granger.core.library.PackStatus
import app.granger.core.library.PlaybackDao
import app.granger.core.library.RecentPlay
import app.granger.core.library.Track
import app.granger.core.player.AutoRewind
import app.granger.core.player.ChapterMap
import app.granger.core.player.MusicQueue
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlaySource
import app.granger.core.player.PlayerHub
import app.granger.core.player.PlayerMode
import app.granger.core.player.PlayerNotices
import app.granger.core.player.PlayerPrefs
import app.granger.core.player.PlayerSettingsKeys
import app.granger.core.player.PlayerUi
import app.granger.core.player.PositionSaver
import app.granger.core.player.PositionSaver.SaveReason
import app.granger.core.player.ReplayGainMath
import app.granger.core.player.ReplayGainMode
import app.granger.core.player.SavedModes
import app.granger.core.player.SavedModesReader
import app.granger.core.player.SleepSetting
import app.granger.core.player.SleepTimer
import app.granger.core.player.VoiceChapters
import app.granger.core.player.lastAudiobookId
import app.granger.core.player.restoreQueue
import app.granger.core.player.voice.VoiceProgress
import app.granger.core.speech.ReadAddress
import app.granger.core.speech.ReadSource
import app.granger.core.speech.VoiceId
import app.granger.readaloud.VoicePlayer
import app.granger.readaloud.VoiceUi
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import platform.Foundation.NSUserDefaults

/**
 * The player's prefs in UserDefaults (Android's SharedPreferences file "player"), each key as `player.<key>`. Media
 * settings (Task 6) write through here too, so [listen] hears every change.
 */
class DefaultsPlayerPrefs(suiteName: String?) : PlayerPrefs {
    constructor() : this(null)

    private val d: NSUserDefaults = suiteName?.let { NSUserDefaults(suiteName = it) } ?: NSUserDefaults.standardUserDefaults
    // Written from the player's coroutines and read by listeners on other threads: guarded, so a put while a listener
    // comes or goes can't read the list mid-change (a crash in Music's playlist test, Plan I7 Task 6).
    private val listeners = ArrayList<(String?) -> Unit>()
    private val lock = platform.Foundation.NSLock()
    private fun k(key: String) = "player.$key"

    override fun getString(key: String): String? = d.stringForKey(k(key))
    fun putString(key: String, value: String?) {
        if (value == null) d.removeObjectForKey(k(key)) else d.setObject(value, k(key))
        changed(key)
    }
    fun getFloat(key: String, default: Float): Float = if (d.objectForKey(k(key)) != null) d.doubleForKey(k(key)).toFloat() else default
    fun putFloat(key: String, value: Float) { d.setDouble(value.toDouble(), k(key)); changed(key) }
    fun getLong(key: String, default: Long): Long = if (d.objectForKey(k(key)) != null) d.integerForKey(k(key)) else default
    fun putLong(key: String, value: Long?) {
        if (value == null) d.removeObjectForKey(k(key)) else d.setInteger(value, k(key))
        changed(key)
    }
    fun getInt(key: String, default: Int): Int = getLong(key, default.toLong()).toInt()

    private fun changed(key: String) { snapshot().forEach { it(key) } }

    private fun snapshot(): List<(String?) -> Unit> {
        lock.lock()
        try { return listeners.toList() } finally { lock.unlock() }
    }

    override fun listen(onChange: (key: String?) -> Unit): () -> Unit {
        lock.lock()
        try { listeners += onChange } finally { lock.unlock() }
        return {
            lock.lock()
            try { listeners -= onChange } finally { lock.unlock() }
        }
    }
}

/**
 * The iPhone's player (Plan I6 Task 5): the shared [PlayerHub] over the one [AudioGraph], Android's `PlayerEngine`
 * without ExoPlayer.
 * - Music: the shared [MusicQueue] (shuffle, repeat, edits) on the graph's media node, the next song scheduled back to
 *   back (gapless), ReplayGain per song through the graph's EQ ([ReplayGainMath], the mode in Media settings).
 * - Audiobooks: [ChapterMap] over the book's files, the place in `audiobook_states` (books every 5 s, music every 15 s,
 *   and on every pause, seek, chapter and stop: [PositionSaver]), speed through the time-pitch unit, the boost above
 *   1.0 through the EQ, auto-rewind after a pause ([AutoRewind]), next/previous by chapter.
 * - The voice is [voice] (I5's [VoicePlayer], which writes into the same graph). Music playing when the voice starts
 *   goes on under it at the mix level (Plan V2), music started while the voice reads goes under it, and an audiobook
 *   gives way to the voice. [voiceUi] is how the hub hears the voice start and stop (readers call the voice directly).
 * - The sleep timer ([SleepTimer]: After with the fade, End of chapter by the chapter or the song) for music and
 *   books; the voice keeps its own.
 * - Skip silence has no iPhone form yet (call 10, "≈ later"): the book's setting is kept as stored and not applied.
 *
 * Calls are fire-and-forget and run in order on [scope], as on Android; [ui] reflects them once applied.
 */
class IosPlayerHub(
    private val graph: AudioGraph,
    private val media: MediaLibrary,
    private val playback: PlaybackDao,
    val prefs: DefaultsPlayerPrefs,
    private val voice: VoicePlayer?,
    private val voiceUi: StateFlow<VoiceUi>,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) : PlayerHub {
    private enum class Mode { NONE, MUSIC, BOOK }

    private val player = MediaFilePlayer(graph)
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val random = Random.Default

    private val _ui = MutableStateFlow(PlayerUi.IDLE)
    override val ui: StateFlow<PlayerUi> = _ui.asStateFlow()
    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    override val queue: StateFlow<List<Track>> = _queue.asStateFlow()
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val notices: Flow<String> = _notices
    override val voiceProgress: StateFlow<VoiceProgress?> = voice?.progress ?: MutableStateFlow(null)
    override val saved: StateFlow<SavedModes> by lazy {
        SavedModesReader(media, playback, prefs).flow().stateIn(scope, SharingStarted.Eagerly, SavedModes.UNKNOWN)
    }

    private var mode = Mode.NONE
    private var loaded = false
    private var playing = false      // wants to play (what the button shows)
    private var ended = false        // ran out: play starts again

    // music
    private var musicQueue: MusicQueue? = null
    private var tracks: Map<String, Track> = emptyMap()
    private var sourceKey = ""
    private var errorRun = 0

    // book
    private var book: Audiobook? = null
    private var map: ChapterMap? = null
    private var bookFiles: List<Track> = emptyList()
    private var speed = 1f
    private var skipSilence = false
    private var boostMb = 0
    private var finished = false
    private var restoreUnresolved = false
    private var pausedAt = 0L
    private var lastChapter = -1

    // volume and the sleep timer
    private var mix = prefs.getFloat(PlayerSettingsKeys.MIX_LEVEL, PlayerSettingsKeys.DEFAULT_MIX).coerceIn(0f, 1f)
    private var fade = 1f
    private var duck = 1f
    private val sleep = SleepTimer(now)
    private var sleepTarget = -1
    private var ticker: Job? = null
    private var voiceWasActive = false

    private val saver = PositionSaver(now) { _ -> snapshot()?.let { write(it) } }

    init {
        scope.launch { for (c in commands) runCatching { c() }.onFailure { if (it is CancellationException) throw it else log("player: ${it.message}") } }
        player.events = { e -> submit { onPlayerEvent(e) } }
        scope.launch { voiceUi.collect { submit { onVoiceChanged() } } }
    }

    private fun submit(command: suspend () -> Unit) { commands.trySend(command) }

    private suspend fun <T> inTurn(block: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        submit { try { result.complete(block()) } catch (e: Throwable) { result.completeExceptionally(e); if (e is CancellationException) throw e } }
        return result.await()
    }

    private val voiceActive: Boolean get() = voiceUi.value.now != null
    private val voiceReading: Boolean get() = voiceUi.value.let { it.playing && it.now?.ended == false }

    // ---- what the hub is asked ----

    override fun playTracks(trackIds: List<String>, startIndex: Int, shuffle: Boolean, source: PlaySource) = submit {
        val found = trackIds.mapIndexedNotNull { i, id -> media.track(id)?.let { i to it } }
        if (found.isEmpty()) return@submit
        val start = found.indexOfFirst { it.first >= startIndex }.takeIf { it >= 0 } ?: 0
        val list = found.map { it.second }
        val q = MusicQueue(list.map { it.id }, start, random)
        if (shuffle) q.setShuffle(true)
        // A paused voice gives way to the music; a reading voice keeps reading with the music under it (Plan V2).
        if (voiceActive && !voiceReading) voice?.stop()
        stopCurrent()
        sourceKey = source.key
        startMusic(q, list.associateBy { it.id }, 0L)
        playback.upsertRecentPlay(RecentPlay(source.key, source.title, now()))
        resume()
    }

    /** Debug and tests: plays [tracks] as given, without the library (no recent play). */
    fun playTrackRows(rows: List<Track>, startIndex: Int, source: PlaySource) = submit {
        if (rows.isEmpty()) return@submit
        stopCurrent()
        sourceKey = source.key
        startMusic(MusicQueue(rows.map { it.id }, startIndex, random), rows.associateBy { it.id }, 0L)
        resume()
    }

    override fun playAudiobook(id: String, at: AudioBookmark?) = submit { startBook(id, at) }

    private suspend fun startBook(id: String, at: AudioBookmark?) {
        val b = media.audiobook(id) ?: return
        if (voiceActive) voice?.stop()
        stopCurrent()
        if (loadBook(id, at, play = true)) playback.upsertRecentPlay(RecentPlay(b.id, b.title, now()))
    }

    override fun readAloud(source: ReadSource, from: ReadAddress?, voice: VoiceId?) = submit {
        // An audiobook gives way to the voice; music goes on under it.
        if (mode == Mode.BOOK) stopCurrent()
        this.voice?.readAloud(source, from, voice)
    }

    override fun stopVoice() = submit { if (voiceActive) voice?.stop() }

    override fun stop() = submit {
        if (voiceActive) voice?.stop()
        if (mode != Mode.NONE) stopCurrent()
        refresh()
    }

    override fun nextParagraph() { voice?.nextParagraph() }
    override fun previousParagraph() { voice?.previousParagraph() }
    override fun nextSentence() { voice?.nextSentence() }
    override fun previousSentence() { voice?.previousSentence() }
    override fun jumpTo(address: ReadAddress) { voice?.jumpTo(address) }
    override suspend fun voiceChapters(): VoiceChapters? = voice?.chapters()?.let { VoiceChapters(it.chapters, it.current) }
    override fun jumpToChapter(index: Int) { voice?.jumpToChapter(index) }
    override fun setVoice(voice: VoiceId) { this.voice?.setVoice(voice) }

    override fun activate(mode: PlayerMode) = submit {
        when (mode) {
            PlayerMode.MUSIC -> {
                if (this.mode != Mode.MUSIC) {
                    val state = playback.musicState() ?: return@submit
                    val restored = restoreQueue(state, random) { media.track(it) } ?: return@submit
                    if (voiceActive && !voiceReading) voice?.stop()
                    stopCurrent()
                    sourceKey = state.sourceKey
                    startMusic(restored.queue, restored.tracks, state.positionMs)
                }
                resume()
            }
            PlayerMode.AUDIOBOOK -> {
                if (this.mode == Mode.BOOK) return@submit resume()
                val states = playback.observeAudiobookStates().first()
                lastAudiobookId(prefs, states, media)?.let { startBook(it, null) }
            }
        }
    }

    override fun setFinished(audiobookId: String, finished: Boolean) = submit {
        if (mode != Mode.BOOK || book?.id != audiobookId) return@submit
        val m = map ?: return@submit
        this.finished = finished
        if (!finished && ended) {
            pauseNow()
            val first = m.start(m.chapters.first().ordinal)
            loadFile(first.fileIndex, first.positionMs)
            ended = false
        }
        saver.onEvent(SaveReason.STOP)
        refresh()
    }

    override fun unload(audiobookId: String, trackIds: Set<String>) = submit {
        val here = when (mode) {
            Mode.BOOK -> book?.id == audiobookId
            Mode.MUSIC -> musicQueue?.ids?.any { it in trackIds } == true
            Mode.NONE -> false
        }
        if (!here) return@submit
        val q = musicQueue
        if (mode == Mode.MUSIC && q != null && !q.ids.all { it in trackIds }) {
            val current = q.ids[q.index]
            for (i in q.playOrder.indices.reversed()) if (q.ids[q.playOrder[i]] in trackIds) q.remove(i)
            followQueue(current)
            musicChanged()
            return@submit
        }
        stopCurrent()
        refresh()
    }

    override fun playPause() = submit {
        if (voiceActive && mode != Mode.BOOK) { voice?.playPause(); return@submit }
        if (playing && !ended) userPause() else resume()
    }

    /** Play (the lock screen, a headset): the voice when it's loaded, else the media. */
    fun play() = submit { if (voiceActive && mode != Mode.BOOK) voice?.play() else resume() }

    /** Pause everything (the lock screen, a call, headphones out): the voice and the media. */
    fun pause() = submit {
        if (voiceActive) voice?.pause()
        if (playing) userPause()
    }

    override fun next() = submit {
        if (voiceActive && mode != Mode.BOOK) { voice?.nextParagraph(); return@submit }
        when (mode) {
            Mode.MUSIC -> {
                val q = musicQueue ?: return@submit
                // As Android: a press moves on as if repeat one were off; the end of the queue wraps only with repeat all.
                val order = q.playOrder
                val pos = order.indexOf(q.index)
                val target = when { pos + 1 < order.size -> order[pos + 1]; q.repeat != 0 -> order[0]; else -> return@submit }
                q.select(target)
                loadFile(target, 0L)
                musicChanged()
            }
            Mode.BOOK -> { restoreUnresolved = false; chapterStep(+1) }
            Mode.NONE -> Unit
        }
    }

    override fun previous() = submit {
        if (voiceActive && mode != Mode.BOOK) { voice?.previousParagraph(); return@submit }
        when (mode) {
            Mode.MUSIC -> {
                val q = musicQueue ?: return@submit
                val target = q.previous(player.positionMs())
                loadFile(target, 0L)
                musicChanged()
            }
            Mode.BOOK -> { restoreUnresolved = false; chapterStep(-1) }
            Mode.NONE -> Unit
        }
    }

    override fun skipBack() = submit {
        if (voiceActive && mode != Mode.BOOK) { voice?.previousSentence(); return@submit }
        if (mode != Mode.BOOK) return@submit
        restoreUnresolved = false
        seekBookBy(-prefs.getInt(PlayerSettingsKeys.SKIP_BACK_S, DEFAULT_SKIP_S) * 1_000L)
    }

    override fun skipForward() = submit {
        if (voiceActive && mode != Mode.BOOK) { voice?.nextSentence(); return@submit }
        if (mode != Mode.BOOK) return@submit
        restoreUnresolved = false
        seekBookBy(prefs.getInt(PlayerSettingsKeys.SKIP_FORWARD_S, DEFAULT_SKIP_S) * 1_000L)
    }

    override fun seekTo(positionMs: Long) = submit {
        when (mode) {
            Mode.MUSIC -> { val q = musicQueue ?: return@submit; loadFile(q.index, positionMs.coerceAtLeast(0L)); saver.onEvent(SaveReason.SEEK) }
            Mode.BOOK -> {
                val m = map ?: return@submit
                val key = player.currentKey ?: return@submit
                val c = m.chapterAt(key, player.positionMs())
                restoreUnresolved = false
                loadFile(m.fileIndexOf(c), c.startMs + positionMs.coerceIn(0L, c.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE))
                retargetSleep()
                checkChapter()
                saver.onEvent(SaveReason.SEEK)
            }
            Mode.NONE -> Unit
        }
        refresh()
    }

    override fun seekToChapter(ordinal: Int) = submit {
        val m = map ?: return@submit
        if (mode != Mode.BOOK) return@submit
        val at = m.start(ordinal)
        restoreUnresolved = false
        loadFile(at.fileIndex, at.positionMs)
        retargetSleep()
        checkChapter()
        saver.onEvent(SaveReason.SEEK)
        refresh()
    }

    override fun setShuffle(on: Boolean) = submit {
        val q = musicQueue ?: return@submit
        if (mode != Mode.MUSIC) return@submit
        q.setShuffle(on)
        updateGain()
        scheduleNext()
        musicChanged()
    }

    override fun cycleRepeat() = submit {
        val q = musicQueue ?: return@submit
        if (mode != Mode.MUSIC) return@submit
        q.repeat = (q.repeat + 1) % 3
        scheduleNext()
        musicChanged()
    }

    override fun moveInQueue(from: Int, to: Int) = submit {
        val q = musicQueue ?: return@submit
        if (mode != Mode.MUSIC || from !in q.ids.indices || to !in q.ids.indices) return@submit
        val current = q.ids[q.index]
        q.move(from, to)
        followQueue(current)
        musicChanged()
    }

    override fun removeFromQueue(i: Int) = submit {
        val q = musicQueue ?: return@submit
        if (mode != Mode.MUSIC || i !in q.ids.indices || q.ids.size == 1) return@submit
        val current = q.ids[q.index]
        q.remove(i)
        followQueue(current)
        musicChanged()
    }

    override fun playQueueItem(position: Int) = submit {
        val q = musicQueue ?: return@submit
        if (mode != Mode.MUSIC || position !in q.ids.indices) return@submit
        val index = q.playOrder[position]
        if (index == q.index && !ended) return@submit resume()
        q.select(index)
        loadFile(index, 0L)
        musicChanged()
        resume()
    }

    override fun playNext(trackIds: List<String>) = submit { insert(trackIds, next = true) }
    override fun addToQueue(trackIds: List<String>) = submit { insert(trackIds, next = false) }

    private suspend fun insert(trackIds: List<String>, next: Boolean) {
        val q = musicQueue
        if (mode != Mode.MUSIC || q == null) return
        val added = trackIds.mapNotNull { media.track(it) }
        if (added.isEmpty()) return
        tracks = tracks + added.associateBy { it.id }
        val current = q.ids[q.index]
        if (next) q.playNext(added.map { it.id }) else q.add(added.map { it.id })
        followQueue(current)
        updateGain()
        musicChanged()
    }

    // ---- music under the voice ----

    override fun setMixLevel(level: Float) = submit {
        mix = level.coerceIn(0f, 1f)
        prefs.putFloat(PlayerSettingsKeys.MIX_LEVEL, mix)
        applyVolume()
        refresh()
    }

    override fun playPauseMusic() = submit {
        if (!voiceActive || mode != Mode.MUSIC) return@submit
        if (playing && !ended) userPause() else resume()
    }

    override fun nextMusic() = submit {
        if (!voiceActive || mode != Mode.MUSIC) return@submit
        val q = musicQueue ?: return@submit
        val order = q.playOrder
        val pos = order.indexOf(q.index)
        val target = when { pos + 1 < order.size -> order[pos + 1]; q.repeat != 0 -> order[0]; else -> return@submit }
        q.select(target)
        loadFile(target, 0L)
        musicChanged()
    }

    override fun seekMusic(positionMs: Long) = submit {
        if (!voiceActive || mode != Mode.MUSIC) return@submit
        val q = musicQueue ?: return@submit
        loadFile(q.index, positionMs.coerceAtLeast(0L))
        refresh()
    }

    // ---- modes ----

    override fun setSpeed(speed: Float) = submit {
        if (voiceActive && mode != Mode.BOOK) { voice?.setSpeed(speed); return@submit }
        if (mode != Mode.BOOK) return@submit
        this.speed = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        player.speed = this.speed
        saver.onEvent(SaveReason.SEEK)
        refresh()
    }

    /** Kept and saved, not applied: the iPhone has no silence skipping yet (call 10, "≈ later"). */
    override fun setSkipSilence(on: Boolean) = submit {
        if (mode != Mode.BOOK) return@submit
        skipSilence = on
        saver.onEvent(SaveReason.SEEK)
        refresh()
    }

    override fun setVolumeBoost(mb: Int) = submit {
        if (mode != Mode.BOOK) return@submit
        boostMb = mb.coerceAtLeast(0)
        player.setGainDb(boostMb / 100f)
        saver.onEvent(SaveReason.SEEK)
        refresh()
    }

    override fun startSleepTimer(setting: SleepSetting) = submit {
        if (voiceActive && mode != Mode.BOOK) { voice?.startSleepTimer(setting); return@submit }
        if (!loaded) return@submit
        fade = 1f
        retargetSleep(force = true)
        sleep.start(setting) { untilSleepTargetEnds() }
        applyVolume()
        updateTicker()
        refresh()
    }

    override fun cancelSleepTimer() = submit {
        if (voiceActive && mode != Mode.BOOK) { voice?.cancelSleepTimer(); return@submit }
        cancelSleep()
    }

    override suspend fun addBookmark(title: String): Long = inTurn {
        val b = book ?: return@inTurn -1L
        val m = map ?: return@inTurn -1L
        val key = player.currentKey ?: return@inTurn -1L
        if (mode != Mode.BOOK || !loaded) return@inTurn -1L
        playback.insertBookmark(AudioBookmark(0L, b.id, m.files[key], player.positionMs(), title, "", now()))
    }

    // ---- loading ----

    private suspend fun stopCurrent() {
        if (mode != Mode.NONE) saver.onEvent(SaveReason.STOP)
        cancelSleep()
        loaded = false
        playing = false
        ended = false
        player.clear()
        player.speed = 1f
        player.setGainDb(0f)
        mode = Mode.NONE
        musicQueue = null; tracks = emptyMap()
        book = null; map = null; bookFiles = emptyList()
        _queue.value = emptyList()
        updateTicker()
    }

    private suspend fun startMusic(q: MusicQueue, byId: Map<String, Track>, positionMs: Long) {
        musicQueue = q
        tracks = byId
        mode = Mode.MUSIC
        prefs.putString(PlayerSettingsKeys.LAST_MODE, SavedModesReader.MODE_MUSIC)
        prefs.putString(PlayerSettingsKeys.LAST_MEDIA, SavedModesReader.MODE_MUSIC)
        player.speed = 1f
        loaded = true
        errorRun = 0
        applyVolume()
        _queue.value = q.playOrder.map { byId.getValue(q.ids[it]) }
        loadFile(q.index, positionMs)
        refresh()
    }

    /** Loads [id] at [at], else its saved place (the start if finished), else the start; true when it loaded. */
    private suspend fun loadBook(id: String, at: AudioBookmark?, play: Boolean): Boolean {
        val b = media.audiobook(id) ?: return false
        val chapters = media.chapters(id).first().sortedBy { it.ordinal }
        if (chapters.isEmpty()) return false
        val m = ChapterMap(chapters)
        val files = m.files.map { media.track(it) ?: return false }
        val state = playback.audiobookState(id)
        val restart = at == null && state?.finished == true
        val first = m.start(m.chapters.first().ordinal)
        val startTrack = if (restart) m.files[first.fileIndex] else at?.trackId ?: state?.trackId
        val fileIndex = startTrack?.let { m.files.indexOf(it) }?.takeIf { it >= 0 }
        restoreUnresolved = fileIndex == null && state != null
        if (restoreUnresolved) _notices.tryEmit(PlayerNotices.PLACE_LOST)
        val position = when {
            fileIndex == null -> 0L
            restart -> first.positionMs
            else -> at?.positionMs ?: state?.positionMs ?: 0L
        }
        val lastPaused = prefs.getLong(PlayerSettingsKeys.PAUSED_AT, 0L)
        val sameBook = prefs.getString(PlayerSettingsKeys.LAST_AUDIOBOOK) == id
        book = b
        map = m
        bookFiles = files
        speed = state?.speed ?: 1f
        skipSilence = state?.skipSilence ?: false
        boostMb = state?.volumeBoostMb ?: 0
        finished = !play && state?.finished == true
        musicQueue = null; tracks = emptyMap()
        mode = Mode.BOOK
        prefs.putString(PlayerSettingsKeys.LAST_MODE, SavedModesReader.MODE_BOOK)
        prefs.putString(PlayerSettingsKeys.LAST_MEDIA, SavedModesReader.MODE_BOOK)
        prefs.putString(PlayerSettingsKeys.LAST_AUDIOBOOK, id)
        player.speed = speed
        loaded = true
        applyVolume()
        lastChapter = m.chapterAt(fileIndex ?: 0, position).ordinal
        loadFile(fileIndex ?: 0, position)
        pausedAt = when {
            at != null || restart -> 0L
            sameBook && lastPaused > 0 -> lastPaused
            else -> state?.updatedAt ?: 0L
        }
        refresh()
        if (play) resume()
        return true
    }

    /** The file for queue item (music) or book file [key], with its gain: ReplayGain for songs, the boost for books. */
    private fun fileFor(key: Int): MediaFile? = when (mode) {
        Mode.MUSIC -> musicQueue?.let { q ->
            val t = tracks[q.ids.getOrNull(key)] ?: return null
            MediaFile(key, t.uri, MediaFilePlayer.dbOf(ReplayGainMath.factor(t, replayGainMode(), sourceKey.startsWith("album:") && !q.shuffle)))
        }
        Mode.BOOK -> bookFiles.getOrNull(key)?.let { MediaFile(key, it.uri, boostMb / 100f) }
        Mode.NONE -> null
    }

    private fun replayGainMode(): ReplayGainMode =
        runCatching { ReplayGainMode.valueOf(prefs.getString(PlayerSettingsKeys.REPLAYGAIN_MODE) ?: "AUTO") }.getOrDefault(ReplayGainMode.AUTO)

    /** What follows [key] by itself (the automatic advance: repeat one plays it again). */
    private fun nextKey(key: Int): Int? = when (mode) {
        Mode.MUSIC -> musicQueue?.let { q ->
            val order = q.playOrder
            val pos = order.indexOf(key)
            when { q.repeat == 2 -> key; pos + 1 < order.size -> order[pos + 1]; q.repeat == 1 -> order[0]; else -> null }
        }
        Mode.BOOK -> (key + 1).takeIf { it < bookFiles.size }
        Mode.NONE -> null
    }

    /** Loads item [key] at [positionMs] (playing or paused as before) and schedules what follows; a file that won't open is skipped (music) or reported (books). */
    private fun loadFile(key: Int, positionMs: Long) {
        ended = false
        val file = fileFor(key) ?: return
        if (!player.load(file, positionMs)) return onFailed(key)
        scheduleNext()
    }

    /**
     * The queue was edited with [heardId] playing: keys are indices into the queue's ids, which an edit can renumber.
     * The same song still current keeps playing (its key follows it, and the next one is scheduled again); another
     * current song (the heard one was removed) starts from its beginning.
     */
    private fun followQueue(heardId: String) {
        val q = musicQueue ?: return
        if (q.ids.getOrNull(q.index) == heardId) {
            player.rekeyCurrent(q.index)
            scheduleNext()
        } else {
            loadFile(q.index, 0L)
        }
    }

    private fun scheduleNext() {
        val key = player.currentKey ?: return
        player.setNext(nextKey(key)?.let { fileFor(it) }?.takeIf { player.canOpen(it.path) })
    }

    private fun onFailed(key: Int) {
        errorRun++
        log("player: couldn't open item $key")
        val q = musicQueue
        if (mode == Mode.MUSIC && q != null && errorRun < q.ids.size) {
            val next = nextKey(key)
            if (next != null && next != key) { q.select(next); loadFile(next, 0L); musicChanged(); return }
        }
        pauseNow()
        _notices.tryEmit(when (mode) {
            Mode.MUSIC -> if ((q?.ids?.size ?: 0) > 1) PlayerNotices.QUEUE_FAILED else PlayerNotices.SONG_FAILED
            Mode.BOOK -> PlayerNotices.BOOK_FAILED
            Mode.NONE -> PlayerNotices.SONG_FAILED
        })
        refresh()
    }

    private suspend fun onPlayerEvent(e: MediaFilePlayer.Event) {
        if (!loaded) return
        when (e) {
            is MediaFilePlayer.Event.Advanced -> {
                errorRun = 0
                if (mode == Mode.MUSIC) musicQueue?.select(e.next)
                scheduleNext()
                checkChapter()
                if (mode == Mode.MUSIC) {
                    // End of chapter on a song: the song it waited for has ended.
                    if (sleepTarget >= 0 && sleep.expired()) sleepNow()
                    musicChanged()
                } else {
                    saver.onEvent(SaveReason.TRACK)
                }
            }
            MediaFilePlayer.Event.Ended -> {
                ended = true
                playing = false
                player.pause()
                if (mode == Mode.BOOK) { finished = true; restoreUnresolved = false }
                saver.onEvent(SaveReason.STOP)
                if (sleepTarget >= 0) cancelSleep()
                updateTicker()
            }
        }
        refresh()
    }

    // ---- playback ----

    private fun resume() {
        if (!loaded) return
        if (sleep.expired()) cancelSleep()
        errorRun = 0
        if (mode == Mode.BOOK) finished = false
        if (mode == Mode.BOOK && ended) pausedAt = 0L
        if (mode == Mode.BOOK && pausedAt > 0L) {
            val rewind = AutoRewind.rewindMs(now() - pausedAt)
            pausedAt = 0L
            if (rewind > 0) seekBookBy(-rewind)
        }
        if (ended) {
            ended = false
            when (mode) {
                Mode.MUSIC -> musicQueue?.let { q -> q.playOrder.firstOrNull()?.let { q.select(it); loadFile(it, 0L); musicChanged() } }
                Mode.BOOK -> map?.let { m -> m.start(m.chapters.first().ordinal).let { loadFile(it.fileIndex, it.positionMs) } }
                Mode.NONE -> Unit
            }
        }
        playing = true
        if (mode == Mode.BOOK) prefs.putLong(PlayerSettingsKeys.PAUSED_AT, null)
        audioSession(true)
        player.play()
        updateTicker()
        refresh()
    }

    /** The user's pause (or the lock screen's, a call's): the place is saved and an audiobook remembers when, for auto-rewind. */
    private fun userPause() {
        pauseNow()
        if (loaded) {
            pausedAt = now()
            if (mode == Mode.BOOK) prefs.putLong(PlayerSettingsKeys.PAUSED_AT, pausedAt)
            submit { saver.onEvent(SaveReason.PAUSE) }
        }
        refresh()
    }

    private fun pauseNow() {
        playing = false
        player.pause()
        updateTicker()
    }

    /** Moves an audiobook by [deltaMs], across its files. */
    private fun seekBookBy(deltaMs: Long) {
        if (bookFiles.isEmpty()) return
        var index = player.currentKey ?: 0
        var position = player.positionMs() + deltaMs
        while (position < 0 && index > 0) { index--; position += fileDuration(index) }
        while (index < bookFiles.size - 1 && fileDuration(index).let { it > 0 && position >= it }) { position -= fileDuration(index); index++ }
        val d = fileDuration(index)
        position = position.coerceIn(0L, if (d > 0) d else Long.MAX_VALUE)
        loadFile(index, position)
        retargetSleep()
        checkChapter()
        submit { saver.onEvent(SaveReason.SEEK) }
        refresh()
    }

    private fun fileDuration(index: Int): Long {
        val t = bookFiles.getOrNull(index) ?: return 0L
        return player.durationMs(t.uri) ?: t.durationMs
    }

    private fun chapterStep(step: Int) {
        val m = map ?: return
        val key = player.currentKey ?: 0
        val position = player.positionMs()
        val i = m.chapters.indexOf(m.chapterAt(key, position))
        val target = when {
            step > 0 -> i + 1
            m.inChapter(key, position) > RESTART_CHAPTER_MS -> i
            else -> i - 1
        }
        if (target !in m.chapters.indices) {
            if (target < 0) m.start(m.chapters[0].ordinal).let { loadFile(it.fileIndex, it.positionMs) }
            return
        }
        val at = m.start(m.chapters[target].ordinal)
        loadFile(at.fileIndex, at.positionMs)
        retargetSleep()
        checkChapter()
        refresh()
    }

    private fun checkChapter() {
        val m = map ?: return
        if (mode != Mode.BOOK) return
        val c = m.chapterAt(player.currentKey ?: 0, player.positionMs()).ordinal
        if (c != lastChapter) {
            val first = lastChapter == -1
            lastChapter = c
            if (!first) submit { saver.onEvent(SaveReason.CHAPTER) }
        }
    }

    private fun musicChanged() {
        val q = musicQueue ?: return
        _queue.value = q.playOrder.mapNotNull { tracks[q.ids[it]] }
        submit { saver.onEvent(SaveReason.TRACK) }
        refresh()
    }

    private fun updateGain() {
        val key = player.currentKey ?: return
        fileFor(key)?.let { player.setGainDb(it.gainDb) }
    }

    // ---- the voice beside the media ----

    private suspend fun onVoiceChanged() {
        val active = voiceActive
        if (active && !voiceWasActive && mode == Mode.BOOK) {
            // Read aloud started from a reader while a book played: the book gives way (its place saved).
            stopCurrent()
        }
        voiceWasActive = active
        applyVolume()
        refresh()
    }

    // ---- saving ----

    private sealed interface Snapshot {
        data class Book(val state: AudiobookState) : Snapshot
        data class Music(val state: MusicState) : Snapshot
    }

    private fun snapshot(): Snapshot? {
        if (!loaded) return null
        val position = player.positionMs().coerceAtLeast(0L)
        return when (mode) {
            Mode.BOOK -> {
                if (restoreUnresolved) return null
                val b = book ?: return null
                val m = map ?: return null
                val file = m.files.getOrNull(player.currentKey ?: return null) ?: return null
                Snapshot.Book(AudiobookState(b.id, file, position, speed, skipSilence, boostMb, finished, now()))
            }
            Mode.MUSIC -> musicQueue?.let { Snapshot.Music(it.toState(position, sourceKey, now())) }
            Mode.NONE -> null
        }
    }

    private suspend fun write(s: Snapshot) = try {
        when (s) {
            is Snapshot.Book -> playback.upsertAudiobookState(s.state)
            is Snapshot.Music -> playback.upsertMusicState(s.state)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("player: couldn't save the place: ${e.message}")
    }

    // ---- ticks, the sleep timer, volume ----

    private fun updateTicker() {
        val want = loaded && (playing || sleep.remainingMs != null)
        if (want == (ticker?.isActive == true)) return
        ticker?.cancel()
        ticker = if (want) scope.launch {
            while (true) {
                delay(sleep.remainingMs?.takeIf { it <= FADE_WINDOW_MS }?.let { FADE_TICK_MS } ?: TICK_MS)
                submit { tick() }
            }
        } else null
    }

    private suspend fun tick() {
        if (!loaded) return
        if (playing) {
            saver.onTick(if (mode == Mode.MUSIC) MUSIC_SAVE_MS else BOOK_SAVE_MS)
            checkChapter()
        }
        if (sleep.remainingMs != null) {
            if (sleep.expired()) sleepNow() else { fade = sleep.volume() ?: 1f; applyVolume() }
        }
        refresh()
    }

    private fun sleepNow() {
        if (playing) userPause()
        cancelSleep()
    }

    private fun cancelSleep() {
        sleep.cancel()
        sleepTarget = -1
        fade = 1f
        applyVolume()
        updateTicker()
        refresh()
    }

    private fun retargetSleep(force: Boolean = false) {
        if (!force && sleepTarget < 0) return
        sleepTarget = when (mode) {
            Mode.BOOK -> map?.chapterAt(player.currentKey ?: 0, player.positionMs())?.ordinal ?: -1
            Mode.MUSIC -> player.currentKey ?: -1
            Mode.NONE -> -1
        }
    }

    /** Wall-clock ms until the pinned chapter (or song) ends: media time ÷ speed; 0 once past it. */
    private fun untilSleepTargetEnds(): Long? {
        if (!loaded || sleepTarget < 0) return null
        val key = player.currentKey ?: return null
        val position = player.positionMs()
        val left = when (mode) {
            Mode.BOOK -> {
                val m = map ?: return null
                val current = m.chapterAt(key, position)
                if (current.ordinal > sleepTarget) return 0L
                if (current.ordinal < sleepTarget) sleepTarget = current.ordinal
                val end = current.startMs + (current.durationMs.takeIf { it > 0 } ?: (fileDuration(key) - current.startMs))
                end - position
            }
            Mode.MUSIC -> {
                if (key != sleepTarget) return 0L
                val t = musicQueue?.let { tracks[it.ids[key]] } ?: return null
                (player.durationMs(t.uri) ?: t.durationMs) - position
            }
            Mode.NONE -> return null
        }
        return (left / (if (mode == Mode.BOOK) speed else 1f)).toLong()
    }

    /** The media node's volume: the sleep timer's fade, the duck, and the mix level while the voice reads over the music. */
    private fun applyVolume() {
        val under = voiceActive && mode == Mode.MUSIC
        player.volume = (if (under) mix else 1f) * fade * duck
    }

    /** The media's volume now (tests read the mix level here). */
    val mediaVolume: Float get() = player.volume

    // ---- ui ----

    private fun refresh() {
        val v = voiceUi.value
        val vNow = v.now
        if (vNow != null && mode != Mode.BOOK) {
            val music = if (mode == Mode.MUSIC) musicNow() else null
            _ui.value = PlayerUi(
                NowPlaying.Voice(
                    vNow.sourceKey, vNow.title, vNow.section, vNow.sentence, vNow.paragraph, vNow.speed, vNow.voiceId, vNow.ended,
                    music = music, musicPlaying = music != null && playing && !ended,
                    musicPositionMs = if (music != null) player.positionMs() else 0L,
                    musicDurationMs = music?.let { m -> player.durationMs(m.track.uri) ?: m.track.durationMs } ?: 0L,
                ),
                playing = v.playing, positionMs = 0L, durationMs = 0L, sleepRemainingMs = v.sleepRemainingMs,
            )
            return
        }
        if (!loaded || mode == Mode.NONE) { _ui.value = PlayerUi.IDLE; return }
        val wants = playing && !ended
        when (mode) {
            Mode.MUSIC -> {
                val music = musicNow() ?: return
                val duration = player.durationMs(music.track.uri) ?: music.track.durationMs
                _ui.value = PlayerUi(music, wants, player.positionMs(), duration, sleep.remainingMs)
            }
            Mode.BOOK -> {
                val m = map ?: return
                val b = book ?: return
                val key = player.currentKey ?: 0
                val at = player.positionMs()
                val c = m.chapterAt(key, at)
                val duration = c.durationMs.takeIf { it > 0 } ?: (fileDuration(key) - c.startMs).coerceAtLeast(0L)
                _ui.value = PlayerUi(NowPlaying.Book(b, c, m.size, speed, skipSilence, boostMb), wants, m.inChapter(key, at), duration, sleep.remainingMs)
            }
            Mode.NONE -> Unit
        }
    }

    private fun musicNow(): NowPlaying.Music? {
        val q = musicQueue ?: return null
        val track = tracks[q.ids.getOrNull(q.index)] ?: return null
        return NowPlaying.Music(track, q.playOrder.indexOf(q.index), q.ids.size, q.shuffle, q.repeat, sourceKey)
    }

    /** Swift's audio session: told when the media wants to play. */
    var audioSession: (Boolean) -> Unit = {}

    /** Swift's interruption handling: ducking isn't used on the iPhone (the system ducks others), so this is 1. */
    fun setDuck(level: Float) = submit { duck = level.coerceIn(0f, 1f); applyVolume() }

    private fun log(message: String) = platform.Foundation.NSLog("Granger: $message".replace("%", "%%"))

    companion object {
        const val TICK_MS = 1_000L
        const val FADE_TICK_MS = 200L
        const val FADE_WINDOW_MS = 11_000L
        const val BOOK_SAVE_MS = 5_000L
        const val MUSIC_SAVE_MS = 15_000L
        const val DEFAULT_SKIP_S = 30
        const val RESTART_CHAPTER_MS = 3_000L
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f

        /** A hub on its own scope over [database] (tests): [voiceUi] stands in for the voice. */
        fun standalone(graph: AudioGraph, database: app.granger.core.library.LibraryDatabase, prefs: DefaultsPlayerPrefs, voiceUi: MutableStateFlow<VoiceUi>): IosPlayerHub =
            IosPlayerHub(graph, app.granger.core.library.RoomMediaLibrary(database), database.playbackDao(), prefs, null, voiceUi,
                CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default), { app.granger.screens.nowMillis() })

        /** Tests: a voice state to set by hand. */
        fun voiceState(ui: VoiceUi): MutableStateFlow<VoiceUi> = MutableStateFlow(ui)

        /** An audiobook row for files outside the library (tests). */
        fun fileAudiobook(id: String, title: String, author: String): Audiobook =
            Audiobook(id, MediaOrigin.PHONE, "", title, author, "", "", null, 0L, PackStatus.OK, 0L)

        /** A playable row for a file outside the library (the `-debugPlay` hook, tests): its path is its id. */
        fun fileTrack(path: String, title: String, durationMs: Long): Track = fileTrack(path, title, durationMs, null)

        fun fileTrack(path: String, title: String, durationMs: Long, audiobookId: String?): Track = Track(
            id = "file:$path", origin = MediaOrigin.PHONE, uri = path, folder = path.substringBeforeLast('/'), title = title,
            album = "", albumKey = "", albumArtists = "", artists = "", composers = "", genres = "", disc = null, trackNo = null,
            date = "", originalDate = "", releaseType = "", sortTitle = title, sortAlbum = "", sortArtist = "", sortAlbumArtist = "",
            durationMs = durationMs, rgTrackGain = null, rgTrackPeak = null, rgAlbumGain = null, rgAlbumPeak = null, coverPath = null,
            audiobookId = audiobookId, status = PackStatus.OK, addedAt = 0L,
        )
    }
}
