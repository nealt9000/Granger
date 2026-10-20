package app.granger.core.player

import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.HandlerWrapper
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import app.granger.core.library.Albums
import app.granger.core.library.AudioBookmark
import app.granger.core.library.Audiobook
import app.granger.core.library.AudiobookState
import app.granger.core.library.MediaAddress
import app.granger.core.library.MediaLibrary
import app.granger.core.library.MusicState
import app.granger.core.library.PlaybackDao
import app.granger.core.library.RecentPlay
import app.granger.core.library.Track
import app.granger.core.player.PositionSaver.SaveReason
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The player's logic around one [ExoPlayer] (spec §6–§8): music and audiobook modes, one active at a time, each with
 * its own saved state.
 *
 * Threading: everything runs on the player's application looper (main). Hub calls are queued and run in order, so a
 * call made while a queue is still loading from the db waits for it. Focus, headset and player events are handled
 * at once. Saves take their snapshot on the main thread and write on [writer], one at a time in order.
 *
 * Music: the whole queue is one playlist, items in [MusicQueue.ids] order. Shuffle never reorders items: ExoPlayer
 * gets [MusicQueue.playOrder] as its [ShuffleOrder] with shuffle mode on, so transitions stay gapless and both agree
 * on what plays next. Queue edits are applied to both with move/remove/add plus a new shuffle order, never by
 * rebuilding the playlist. [MusicQueue] stays the source of truth for the saved state; on every transition it is
 * re-pointed at the item ExoPlayer moved to.
 *
 * Audiobooks: every file is one playlist item; [ChapterMap] maps chapters to (item, ms) and back.
 *
 * Position safety: [PositionSaver] (books every 5 s, music every 15 s, plus pause, seek, chapter, track and stop).
 * Nothing is saved while a mode is loading or before the restored seek is confirmed (first READY), so a save can
 * never put 0 over a real position. A saved book position whose file can't be found isn't overwritten by the fallback
 * start either: saves wait until the user seeks, picks a chapter or bookmark, or plays the book to its end.
 */
class PlayerEngine(
    val player: ExoPlayer,
    private val media: MediaLibrary,
    private val playback: PlaybackDao,
    private val prefs: SharedPreferences,
    private val focus: FocusPolicy,
    private val scope: CoroutineScope,
    private val effects: AudioEffects = AudioEffects.NONE,
    private val replayGain: ReplayGainProcessor? = null,
    writer: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val random: Random = Random.Default,
) : PlayerHub {
    private enum class Mode { NONE, MUSIC, BOOK }

    private val clock = player.clock
    private fun now() = clock.currentTimeMillis()

    private val _ui = MutableStateFlow(PlayerUi.IDLE)
    override val ui: StateFlow<PlayerUi> = _ui
    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    override val queue: StateFlow<List<Track>> = _queue

    private val writerDispatcher = writer
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val ticker: HandlerWrapper = clock.createHandler(player.applicationLooper, null)
    private val tickRunnable = Runnable { tick() }
    private var ticking = false
    private var released = false

    private var mode = Mode.NONE
    private var loaded = false            // the player holds the current mode's items
    private var awaitingReady = false     // the start position isn't confirmed yet: no saves
    private var saveWhenReady = false     // a new queue or book started by the user: save it once it's ready
    private var editing = false           // inside a queue edit: the engine, not the transition, moves the queue

    // Music
    private var musicQueue: MusicQueue? = null
    private var tracks: Map<String, Track> = emptyMap()
    private var sourceKey = ""

    // Audiobook
    private var book: Audiobook? = null
    private var map: ChapterMap? = null
    private var speed = 1f
    private var skipSilence = false
    private var boostMb = 0
    private var finished = false
    private var lastChapter = -1
    // The saved position names a file the book no longer has, so it played from a fallback start: the saved row is
    // left alone until the user picks a place (seek, skip, chapter, bookmark) or plays the book to its end.
    private var restoreUnresolved = false

    private var errorRun = 0              // playback errors since an item last got ready: stops a queue of broken files
    private var pausedAt = 0L
    private var pausedForFocus = false
    private var duck = 1f
    private var fade = 1f
    private val sleep = SleepTimer(::now)
    private var sleepTarget = -1          // the chapter (book) or item (music) an end-of-chapter timer waits for

    // The snapshot is taken at once (on main); the write then waits its turn on the writer.
    private val saver = PositionSaver(::now) { _ -> snapshot()?.let { withContext(writerDispatcher) { write(it) } } }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) errorRun = 0
            if (state == Player.STATE_READY && awaitingReady) {
                awaitingReady = false
                if (saveWhenReady) { saveWhenReady = false; save(SaveReason.TRACK) }
            }
            if (state == Player.STATE_ENDED && mode == Mode.BOOK) { finished = true; restoreUnresolved = false; save(SaveReason.STOP) }
            refresh()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Who paused or played (reason: 1 us or a controller, 3 headphones unplugged): for reading a device test.
            Log.i(FOCUS_TAG, "playWhenReady=$playWhenReady reason=$reason pausedForFocus=$pausedForFocus")
            // The pause time (for auto-rewind) is kept for audiobooks only, so it survives the process.
            if (playWhenReady) {
                pausedAt = 0L
                if (mode == Mode.BOOK) prefs.edit().remove(PlayerSettingsKeys.PAUSED_AT).apply()
            } else if (loaded) {
                pausedAt = now()
                if (mode == Mode.BOOK) prefs.edit().putLong(PlayerSettingsKeys.PAUSED_AT, pausedAt).apply()
                save(SaveReason.PAUSE)
            }
            updateTicker()
            refresh()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateTicker()
            refresh()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!loaded) return
            if (mode == Mode.MUSIC) {
                if (!editing) musicQueue?.select(player.currentMediaItemIndex)
                replayGain?.onMediaItemTransition(mediaItem?.mediaId)
            }
            checkChapter()
            save(SaveReason.TRACK)
            refresh()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (!loaded) return
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                checkChapter()
                save(SaveReason.SEEK)
            }
            refresh()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Playback error", error)
            // A missing or broken song: go on with the next one, unless every item has failed in a row (repeat all over
            // broken files would loop forever). Otherwise, and for an audiobook, stay where it is, paused, so play
            // tries again.
            errorRun++
            Log.i(FOCUS_TAG, "playback error ${error.errorCodeName}")
            if (mode == Mode.MUSIC && player.hasNextMediaItem() && errorRun < player.mediaItemCount) {
                player.seekToNextMediaItem()
                player.prepare()
            } else {
                player.pause()
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            effects.open(audioSessionId, speech = mode == Mode.BOOK)
            effects.setBoost(if (mode == Mode.BOOK) boostMb else 0)
        }
    }

    // pausedForFocus: we paused for a transient loss (or play was pressed during one), so GAIN plays. A user pause
    // in the meantime clears it; so does a permanent loss.
    private val focusListener = object : FocusPolicy.Listener {
        override fun onTransientLoss() {
            if (player.playWhenReady) {
                Log.i(FOCUS_TAG, "transient loss: pausing, will resume on gain")
                pausedForFocus = true
                player.pause()
            } else {
                Log.i(FOCUS_TAG, "transient loss: not playing, nothing to resume" + if (pausedForFocus) " (still resuming)" else "")
            }
        }

        override fun onLoss() {
            Log.i(FOCUS_TAG, "loss: pausing, staying paused")
            pausedForFocus = false
            player.pause()
        }

        override fun onGain() {
            duck = 1f
            applyVolume()
            if (pausedForFocus) {
                Log.i(FOCUS_TAG, "gain: resuming (volume ${player.volume})")
                pausedForFocus = false
                resume()
            } else {
                Log.i(FOCUS_TAG, "gain: not paused for focus, playWhenReady=${player.playWhenReady} (volume ${player.volume})")
            }
        }

        override fun onDuck() {
            duck = DUCK_VOLUME
            applyVolume()
            Log.i(FOCUS_TAG, "duck: volume ${player.volume}")
        }
    }

    // Held here: SharedPreferences keeps its listeners weakly.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // A new ReplayGain mode applies at once: the processor reads the gains per buffer.
        if (key == PlayerSettingsKeys.REPLAYGAIN_MODE) submit { updateGains() }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        player.addListener(listener)
        focus.listener = focusListener
        effects.open(player.audioSessionId, speech = false)
        scope.launch {
            for (command in commands) {
                try {
                    command()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Player command failed", e)
                }
            }
        }
    }

    private fun submit(command: suspend () -> Unit) { if (!released) commands.trySend(command) }

    // ---- restore and release (the service's lifecycle) ----

    /** Loads the last active player's saved state, paused (after the service is recreated). */
    fun restore() = submit {
        when (prefs.getString(PlayerSettingsKeys.LAST_MODE, null)) {
            MODE_BOOK -> prefs.getString(PlayerSettingsKeys.LAST_AUDIOBOOK, null)?.let { loadBook(it, null, play = false) }
            MODE_MUSIC -> playback.musicState()?.let { loadMusicState(it) }
        }
    }

    /** Writes the current position now and waits for it (task removed, service destroyed). */
    fun saveNow() {
        if (released) return
        runBlocking { withTimeoutOrNull(SAVE_TIMEOUT_MS) { saver.onEvent(SaveReason.STOP) } }
    }

    /** Saves (unless [save] is false, which tests use to stand for the process being killed), then releases all. */
    fun release(save: Boolean = true) {
        if (released) return
        if (save) saveNow()
        released = true
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        commands.close()
        ticker.removeCallbacksAndMessages(null)
        player.removeListener(listener)
        focus.listener = null
        focus.abandon()
        effects.close()
        scope.cancel()
        player.release()
    }

    /** A headset or Bluetooth device just connected. */
    fun onHeadsetConnected() {
        if (!prefs.getBoolean(PlayerSettingsKeys.HEADSET_AUTOPLAY, false)) return
        if (loaded && !player.playWhenReady) resume()
    }

    /** The current position as a deep-link address (spec §10), or null when nothing is loaded. */
    fun address(): String? {
        if (!loaded) return null
        return when (mode) {
            Mode.MUSIC -> player.currentMediaItem?.mediaId?.let { MediaAddress.track(it, player.currentPosition.coerceAtLeast(0)) }
            Mode.BOOK -> {
                val m = map ?: return null
                val c = m.chapterAt(player.currentMediaItemIndex, player.currentPosition)
                MediaAddress.audiobook(book!!.id, c.ordinal, m.inChapter(player.currentMediaItemIndex, player.currentPosition))
            }
            Mode.NONE -> null
        }
    }

    // ---- PlayerHub ----

    override fun playTracks(trackIds: List<String>, startIndex: Int, shuffle: Boolean, source: PlaySource) = submit {
        val found = trackIds.mapIndexedNotNull { i, id -> media.track(id)?.let { i to it } }
        if (found.isEmpty()) return@submit
        val start = found.indexOfFirst { it.first >= startIndex }.takeIf { it >= 0 } ?: 0
        val list = found.map { it.second }
        stopCurrent()
        val q = MusicQueue(list.map { it.id }, start, random)
        if (shuffle) q.setShuffle(true)
        sourceKey = source.key
        startMusic(q, list.associateBy { it.id }, positionMs = 0L)
        playback.upsertRecentPlay(RecentPlay(source.key, source.title, now()))
        resume()
    }

    override fun playAudiobook(id: String, at: AudioBookmark?) = submit { startBook(id, at) }

    private suspend fun startBook(id: String, at: AudioBookmark?) {
        val b = media.audiobook(id) ?: return
        stopCurrent()
        if (loadBook(id, at, play = true)) playback.upsertRecentPlay(RecentPlay(b.id, b.title, now()))
    }

    override val saved: StateFlow<SavedModes> by lazy {
        SavedModesReader(media, playback, prefs).flow().stateIn(scope, SharingStarted.Eagerly, SavedModes.UNKNOWN)
    }

    override fun activate(mode: PlayerMode) = submit {
        when (mode) {
            PlayerMode.MUSIC -> {
                if (this.mode != Mode.MUSIC) {
                    // Read before the switch: a queue with nothing left to play leaves the audiobook where it is.
                    val state = playback.musicState() ?: return@submit
                    val restored = restoreQueue(state, random) { media.track(it) } ?: return@submit
                    stopCurrent()                             // saves the audiobook (STOP) and waits for the write
                    loadMusicState(state, restored)
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
        if (!finished && player.playbackState == Player.STATE_ENDED) {
            // Left at its end, the book would end again at once: back to its first chapter, paused.
            player.pause()
            val first = m.start(m.chapters.first().ordinal)
            player.seekTo(first.fileIndex, first.positionMs)
        }
        saver.onEvent(SaveReason.STOP)
        refresh()
    }

    override fun unload(audiobookId: String, trackIds: Set<String>) = submit {
        val loadedHere = when (mode) {
            Mode.BOOK -> book?.id == audiobookId
            Mode.MUSIC -> musicQueue?.ids?.any { it in trackIds } == true
            Mode.NONE -> false
        }
        if (!loadedHere) return@submit
        stopCurrent()
        refresh()
    }

    override fun playPause() = submit { if (player.playWhenReady) userPause() else resume() }

    /** Play (from the session: notification, Bluetooth, lock screen). */
    fun play() = submit { resume() }

    fun pause() = submit { userPause() }

    /** The user's pause (app, notification, lock screen, Bluetooth): a call ending afterwards doesn't play. */
    private fun userPause() {
        if (pausedForFocus) Log.i(FOCUS_TAG, "user pause during a focus loss: won't resume on gain")
        pausedForFocus = false
        player.pause()
    }

    override fun next() = submit {
        when (mode) {
            Mode.MUSIC -> {
                // ExoPlayer navigates as if repeat-one were off (like Auxio); auto-advance still repeats.
                val target = player.nextMediaItemIndex
                if (target != C.INDEX_UNSET) player.seekTo(target, 0L)
            }
            Mode.BOOK -> { userMoved(); chapterStep(+1) }
            Mode.NONE -> Unit
        }
    }

    override fun previous() = submit {
        when (mode) {
            Mode.MUSIC -> {
                val q = musicQueue ?: return@submit
                player.seekTo(q.previous(player.currentPosition), 0L)
            }
            Mode.BOOK -> { userMoved(); chapterStep(-1) }
            Mode.NONE -> Unit
        }
    }

    override fun skipBack() = submit {
        if (mode != Mode.BOOK) return@submit
        userMoved()
        seekBookBy(-prefs.getInt(PlayerSettingsKeys.SKIP_BACK_S, DEFAULT_SKIP_S) * 1_000L)
    }

    override fun skipForward() = submit {
        if (mode != Mode.BOOK) return@submit
        userMoved()
        seekBookBy(prefs.getInt(PlayerSettingsKeys.SKIP_FORWARD_S, DEFAULT_SKIP_S) * 1_000L)
    }

    override fun seekTo(positionMs: Long) = submit {
        when (mode) {
            Mode.MUSIC -> player.seekTo(positionMs.coerceAtLeast(0L))
            Mode.BOOK -> {
                val m = map ?: return@submit
                val c = m.chapterAt(player.currentMediaItemIndex, player.currentPosition)
                userMoved()
                player.seekTo(m.fileIndexOf(c), c.startMs + positionMs.coerceIn(0L, c.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE))
                retargetSleep()
            }
            Mode.NONE -> Unit
        }
    }

    override fun seekToChapter(ordinal: Int) = submit {
        val m = map ?: return@submit
        if (mode != Mode.BOOK) return@submit
        val at = m.start(ordinal)
        userMoved()
        player.seekTo(at.fileIndex, at.positionMs)
        retargetSleep()
    }

    override fun setShuffle(on: Boolean) = submit {
        val q = musicQueue ?: return@submit
        if (mode != Mode.MUSIC) return@submit
        q.setShuffle(on)
        syncShuffle(q)
        updateGains()
        musicChanged()
    }

    override fun cycleRepeat() = submit {
        val q = musicQueue ?: return@submit
        if (mode != Mode.MUSIC) return@submit
        q.repeat = (q.repeat + 1) % 3
        player.repeatMode = exoRepeat(q.repeat)
        musicChanged()
    }

    override fun moveInQueue(from: Int, to: Int) = submit {
        val q = musicQueue ?: return@submit
        if (mode != Mode.MUSIC || from !in q.ids.indices || to !in q.ids.indices) return@submit
        if (!q.shuffle) player.moveMediaItem(from, to)   // with shuffle on only the play order changes
        q.move(from, to)
        syncShuffle(q)
        musicChanged()
    }

    override fun removeFromQueue(i: Int) = submit {
        val q = musicQueue ?: return@submit
        if (mode != Mode.MUSIC || i !in q.ids.indices) return@submit
        if (q.ids.size == 1) return@submit                     // the queue never empties itself; play something else
        val removedIndex = q.playOrder[i]
        q.remove(i)
        editing = true
        try {
            player.removeMediaItem(removedIndex)
            syncShuffle(q)
            // Removing the current song: the queue's rule (next in play order, wrap only with repeat all) decides.
            if (player.currentMediaItemIndex != q.index) player.seekTo(q.index, 0L)
        } finally {
            editing = false
        }
        musicChanged()
    }

    override fun playNext(trackIds: List<String>) = submit { insert(trackIds, next = true) }

    override fun addToQueue(trackIds: List<String>) = submit { insert(trackIds, next = false) }

    override fun setSpeed(speed: Float) = submit {
        if (mode != Mode.BOOK) return@submit
        this.speed = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        player.setPlaybackSpeed(this.speed)
        save(SaveReason.SEEK)
        refresh()
    }

    override fun setSkipSilence(on: Boolean) = submit {
        if (mode != Mode.BOOK) return@submit
        skipSilence = on
        player.skipSilenceEnabled = on
        save(SaveReason.SEEK)
        refresh()
    }

    override fun setVolumeBoost(mb: Int) = submit {
        if (mode != Mode.BOOK) return@submit
        boostMb = mb.coerceAtLeast(0)
        effects.setBoost(boostMb)
        save(SaveReason.SEEK)
        refresh()
    }

    override fun startSleepTimer(setting: SleepSetting) = submit {
        if (!loaded) return@submit
        fade = 1f
        retargetSleep(force = true)
        sleep.start(setting, ::untilSleepTargetEnds)
        applyVolume()
        updateTicker()
        refresh()
    }

    override fun cancelSleepTimer() = submit { cancelSleep() }

    override suspend fun addBookmark(title: String): Long {
        val b = book ?: return -1L
        val m = map ?: return -1L
        if (mode != Mode.BOOK || !loaded) return -1L
        val mark = AudioBookmark(0L, b.id, m.files[player.currentMediaItemIndex], player.currentPosition.coerceAtLeast(0L), title, "", now())
        return withContext(writerDispatcher) { playback.insertBookmark(mark) }
    }

    // ---- loading ----

    /** Saves the active mode (STOP) and empties the player, so the other mode can load. */
    private suspend fun stopCurrent() {
        if (mode != Mode.NONE) saver.onEvent(SaveReason.STOP)   // waits for the write, so a reload reads it
        cancelSleep()
        loaded = false
        player.pause()
        player.clearMediaItems()
        effects.setBoost(0)
        mode = Mode.NONE
        _queue.value = emptyList()
    }

    private fun startMusic(q: MusicQueue, byId: Map<String, Track>, positionMs: Long) {
        musicQueue = q
        tracks = byId
        mode = Mode.MUSIC
        book = null; map = null
        prefs.edit().putString(PlayerSettingsKeys.LAST_MODE, MODE_MUSIC).apply()
        player.setAudioAttributes(attributes(speech = false), false)
        player.setPlaybackSpeed(1f)
        player.skipSilenceEnabled = false
        effects.open(player.audioSessionId, speech = false)
        effects.setBoost(0)
        player.setMediaItems(q.ids.map { item(byId.getValue(it), null) }, q.index, positionMs)
        syncShuffle(q)
        player.repeatMode = exoRepeat(q.repeat)
        updateGains()
        awaitingReady = true
        saveWhenReady = true
        loaded = true
        player.prepare()
        _queue.value = q.playOrder.map { byId.getValue(q.ids[it]) }
        refresh()
    }

    private suspend fun loadMusicState(state: MusicState) {
        restoreQueue(state, random) { media.track(it) }?.let { loadMusicState(state, it) }
    }

    private fun loadMusicState(state: MusicState, restored: RestoredQueue) {
        sourceKey = state.sourceKey
        startMusic(restored.queue, restored.tracks, state.positionMs)
        saveWhenReady = false                     // restored as it was: nothing new to save
        pausedAt = 0L
    }

    /** Loads [id] at [at], or its saved position (the start if it was finished), or the start; true when it loaded. */
    private suspend fun loadBook(id: String, at: AudioBookmark?, play: Boolean): Boolean {
        val b = media.audiobook(id) ?: return false
        val chapters = media.chapters(id).first()
        if (chapters.isEmpty()) return false
        val m = ChapterMap(chapters)
        val files = m.files.map { media.track(it) ?: return false }
        val state = playback.audiobookState(id)
        // A finished book plays again from its first chapter (as in Voice); a bookmark or chapter starts where it says.
        val restart = at == null && state?.finished == true
        val first = m.start(m.chapters.first().ordinal)
        val startTrack = if (restart) m.files[first.fileIndex] else at?.trackId ?: state?.trackId
        val fileIndex = startTrack?.let { m.files.indexOf(it) }?.takeIf { it >= 0 }
        // A saved row whose file can't be found: kept as it is until the user picks a place.
        restoreUnresolved = fileIndex == null && state != null
        val position = when {
            fileIndex == null -> 0L
            restart -> first.positionMs
            else -> at?.positionMs ?: state?.positionMs ?: 0L
        }

        val lastPaused = prefs.getLong(PlayerSettingsKeys.PAUSED_AT, 0L)
        val sameBook = prefs.getString(PlayerSettingsKeys.LAST_AUDIOBOOK, null) == id
        book = b
        map = m
        speed = state?.speed ?: 1f
        skipSilence = state?.skipSilence ?: false
        boostMb = state?.volumeBoostMb ?: 0
        // Playing a book means listening to it again: it's no longer finished once its next save is written. A load
        // that doesn't play (a restore) keeps the stored mark until the user plays (see [resume]).
        finished = !play && state?.finished == true
        musicQueue = null; tracks = emptyMap()
        mode = Mode.BOOK
        prefs.edit().putString(PlayerSettingsKeys.LAST_MODE, MODE_BOOK).putString(PlayerSettingsKeys.LAST_AUDIOBOOK, id).apply()

        player.setAudioAttributes(attributes(speech = true), false)
        player.shuffleModeEnabled = false
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.setPlaybackSpeed(speed)
        player.skipSilenceEnabled = skipSilence
        replayGain?.setGains(emptyMap())          // ReplayGain is for music
        effects.open(player.audioSessionId, speech = true)
        effects.setBoost(boostMb)
        player.setMediaItems(files.map { item(it, b) }, fileIndex ?: 0, position)
        lastChapter = m.chapterAt(fileIndex ?: 0, position).ordinal
        awaitingReady = true
        saveWhenReady = play                      // a restore leaves the saved row (and its time) untouched
        loaded = true
        player.prepare()

        // When it was last paused, for auto-rewind: the recorded pause if it was this book, else its last save.
        pausedAt = when {
            at != null || restart -> 0L
            sameBook && lastPaused > 0 -> lastPaused
            else -> state?.updatedAt ?: 0L
        }
        refresh()
        if (play) resume()
        return true
    }

    private fun item(t: Track, b: Audiobook?): MediaItem = MediaItem.Builder()
        .setMediaId(t.id)
        .setUri(t.uri)
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(b?.title ?: t.title)
            .setArtist(b?.author ?: t.artists.replace("; ", ", "))
            .setAlbumTitle(b?.title ?: Albums.title(t))
            .setArtworkUri((b?.coverPath ?: t.coverPath)?.let { Uri.fromFile(File(it)) })
            .build())
        .build()

    // ---- playback ----

    /** Play, with focus; an audiobook steps back by [AutoRewind] for the time it was paused. */
    private fun resume() {
        if (!loaded) return
        if (sleep.expired()) cancelSleep()
        if (!focus.request(speech = mode == Mode.BOOK)) {
            // A call still has focus. Accepted as delayed, its end (GAIN) plays; refused, play stays the only way.
            if (focus.suspended) pausedForFocus = true
            Log.i(FOCUS_TAG, "play refused: focus ${if (focus.suspended) "delayed, will play on gain" else "not granted"}")
            return
        }
        pausedForFocus = false
        errorRun = 0
        if (mode == Mode.BOOK) finished = false
        if (mode == Mode.BOOK && pausedAt > 0L) {
            val rewind = AutoRewind.rewindMs(now() - pausedAt)
            pausedAt = 0L
            if (rewind > 0) seekBookBy(-rewind)
        }
        when (player.playbackState) {
            Player.STATE_IDLE -> player.prepare()
            Player.STATE_ENDED -> if (mode == Mode.MUSIC) {
                player.seekToDefaultPosition(player.currentTimeline.getFirstWindowIndex(player.shuffleModeEnabled))
            } else {
                player.seekTo(0, 0L)
            }
        }
        player.play()
    }

    /** The user picked a place in the book: its position is theirs to save again, even after an unresolved restore. */
    private fun userMoved() { restoreUnresolved = false }

    /** Moves an audiobook by [deltaMs], across file boundaries. */
    private fun seekBookBy(deltaMs: Long) {
        val count = player.mediaItemCount
        if (count == 0) return
        var index = player.currentMediaItemIndex
        var position = player.currentPosition + deltaMs
        while (position < 0 && index > 0) {
            index--
            position += fileDuration(index)
        }
        while (index < count - 1 && fileDuration(index).let { it > 0 && position >= it }) {
            position -= fileDuration(index)
            index++
        }
        val duration = fileDuration(index)
        position = position.coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
        player.seekTo(index, position)
    }

    private fun fileDuration(index: Int): Long {
        val t = player.currentTimeline
        if (index in 0 until t.windowCount) {
            val ms = t.getWindow(index, androidx.media3.common.Timeline.Window()).durationMs
            if (ms != C.TIME_UNSET) return ms
        }
        return map?.files?.getOrNull(index)?.let { tracks[it]?.durationMs } ?: 0L
    }

    /** Next/previous chapter; previous restarts the chapter after 3 s into it (as previous does for a song). */
    private fun chapterStep(step: Int) {
        val m = map ?: return
        val i = m.chapters.indexOf(m.chapterAt(player.currentMediaItemIndex, player.currentPosition))
        val target = when {
            step > 0 -> i + 1
            m.inChapter(player.currentMediaItemIndex, player.currentPosition) > RESTART_CHAPTER_MS -> i
            else -> i - 1
        }
        if (target !in m.chapters.indices) {
            if (target < 0) m.start(m.chapters[0].ordinal).let { player.seekTo(it.fileIndex, it.positionMs) }
            return
        }
        val at = m.start(m.chapters[target].ordinal)
        player.seekTo(at.fileIndex, at.positionMs)
        retargetSleep()
    }

    private fun checkChapter() {
        val m = map ?: return
        if (mode != Mode.BOOK || player.mediaItemCount == 0) return
        val c = m.chapterAt(player.currentMediaItemIndex, player.currentPosition).ordinal
        if (c != lastChapter) {
            val first = lastChapter == -1
            lastChapter = c
            if (!first) save(SaveReason.CHAPTER)
        }
    }

    // ---- music queue ----

    private suspend fun insert(trackIds: List<String>, next: Boolean) {
        val q = musicQueue
        if (mode != Mode.MUSIC || q == null) return
        val added = trackIds.mapNotNull { media.track(it) }
        if (added.isEmpty()) return
        tracks = tracks + added.associateBy { it.id }
        val at = if (next) q.index + 1 else q.ids.size
        if (next) q.playNext(added.map { it.id }) else q.add(added.map { it.id })
        player.addMediaItems(at, added.map { item(it, null) })
        syncShuffle(q)
        updateGains()
        musicChanged()
    }

    /** ExoPlayer's shuffle order is always the queue's play order (identity when shuffle is off). */
    private fun syncShuffle(q: MusicQueue) {
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(q.playOrder.toIntArray(), random.nextLong()))
        player.shuffleModeEnabled = q.shuffle
    }

    private fun musicChanged() {
        val q = musicQueue ?: return
        _queue.value = q.playOrder.map { tracks.getValue(q.ids[it]) }
        save(SaveReason.TRACK)
        refresh()
    }

    private fun updateGains() {
        val rg = replayGain ?: return
        val q = musicQueue
        if (mode != Mode.MUSIC || q == null) return rg.setGains(emptyMap())
        val mode = runCatching { ReplayGainMode.valueOf(prefs.getString(PlayerSettingsKeys.REPLAYGAIN_MODE, null) ?: "AUTO") }
            .getOrDefault(ReplayGainMode.AUTO)
        val albumInOrder = sourceKey.startsWith("album:") && !q.shuffle
        rg.setGains(tracks.mapValues { ReplayGainMath.factor(it.value, mode, albumInOrder) })
    }

    // ---- saving ----

    private fun save(reason: SaveReason) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { saver.onEvent(reason) }
    }

    private sealed interface Snapshot {
        data class Book(val state: AudiobookState) : Snapshot
        data class Music(val state: MusicState) : Snapshot
    }

    private fun snapshot(): Snapshot? {
        if (!loaded || awaitingReady || player.mediaItemCount == 0) return null
        val position = player.currentPosition.coerceAtLeast(0L)
        return when (mode) {
            Mode.BOOK -> {
                if (restoreUnresolved) return null
                val b = book ?: return null
                val m = map ?: return null
                val file = m.files.getOrNull(player.currentMediaItemIndex) ?: return null
                Snapshot.Book(AudiobookState(b.id, file, position, speed, skipSilence, boostMb, finished, now()))
            }
            Mode.MUSIC -> musicQueue?.let { Snapshot.Music(it.toState(position, sourceKey, now())) }
            Mode.NONE -> null
        }
    }

    private suspend fun write(snapshot: Snapshot) = try {
        when (snapshot) {
            is Snapshot.Book -> playback.upsertAudiobookState(snapshot.state)
            is Snapshot.Music -> playback.upsertMusicState(snapshot.state)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Couldn't save the playback position", e)
    }

    // ---- ticks, sleep timer, volume ----

    /** Ticks while playing, and while a sleep timer runs; every second, or every 200 ms through the fade. */
    private fun updateTicker() {
        val want = !released && loaded && (player.isPlaying || sleep.remainingMs != null)
        if (want == ticking) return
        ticking = want
        ticker.removeCallbacks(tickRunnable)
        if (want) ticker.postDelayed(tickRunnable, tickPeriod())
    }

    private fun tickPeriod(): Long = sleep.remainingMs?.takeIf { it <= FADE_WINDOW_MS }?.let { FADE_TICK_MS } ?: TICK_MS

    private fun tick() {
        if (!ticking || released) return
        if (player.isPlaying) {
            val interval = if (mode == Mode.BOOK) BOOK_SAVE_MS else MUSIC_SAVE_MS
            scope.launch(start = CoroutineStart.UNDISPATCHED) { saver.onTick(interval) }
            checkChapter()
        }
        if (sleep.remainingMs != null) {
            if (sleep.expired()) {
                if (player.playWhenReady) player.pause()   // pause, then cancel and restore the volume
                cancelSleep()
            } else {
                fade = sleep.volume() ?: 1f
                applyVolume()
            }
        }
        refresh()
        ticking = false
        updateTicker()
    }

    private fun cancelSleep() {
        sleep.cancel()
        sleepTarget = -1
        fade = 1f
        applyVolume()
        updateTicker()
        refresh()
    }

    /** Pins what an end-of-chapter timer waits for: the current chapter (book) or item (music). */
    private fun retargetSleep(force: Boolean = false) {
        if (!force && sleepTarget < 0) return
        sleepTarget = when (mode) {
            Mode.BOOK -> map?.chapterAt(player.currentMediaItemIndex, player.currentPosition)?.ordinal ?: -1
            Mode.MUSIC -> player.currentMediaItemIndex
            Mode.NONE -> -1
        }
    }

    /** Wall-clock ms until the pinned chapter (or song) ends: media time ÷ speed; 0 once past it. */
    private fun untilSleepTargetEnds(): Long? {
        if (!loaded || sleepTarget < 0) return null
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val mediaLeft = when (mode) {
            Mode.BOOK -> {
                val m = map ?: return null
                val current = m.chapterAt(index, position)
                if (current.ordinal > sleepTarget) return 0L
                if (current.ordinal < sleepTarget) sleepTarget = current.ordinal   // seeked back: wait for this one
                val end = current.startMs + current.durationMs.takeIf { it > 0 }.let { it ?: (fileDuration(index) - current.startMs) }
                end - position
            }
            Mode.MUSIC -> {
                if (index != sleepTarget) return 0L
                val d = player.duration.takeIf { it != C.TIME_UNSET } ?: return null
                d - position
            }
            Mode.NONE -> return null
        }
        return (mediaLeft / player.playbackParameters.speed).toLong()
    }

    private fun applyVolume() { player.volume = duck * fade }

    // ---- ui ----

    private fun refresh() {
        if (released) return
        val now: NowPlaying
        var position = player.currentPosition.coerceAtLeast(0L)
        var duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        when {
            !loaded || mode == Mode.NONE -> { _ui.value = PlayerUi.IDLE; return }
            mode == Mode.MUSIC -> {
                val q = musicQueue ?: return
                val track = tracks[player.currentMediaItem?.mediaId ?: q.ids[q.index]] ?: return
                if (duration == 0L) duration = track.durationMs
                now = NowPlaying.Music(track, q.playOrder.indexOf(q.index), q.ids.size, q.shuffle, q.repeat)
            }
            else -> {
                val m = map ?: return
                val c = m.chapterAt(player.currentMediaItemIndex, player.currentPosition)
                position = m.inChapter(player.currentMediaItemIndex, player.currentPosition)
                duration = c.durationMs.takeIf { it > 0 } ?: (fileDuration(player.currentMediaItemIndex) - c.startMs).coerceAtLeast(0L)
                now = NowPlaying.Book(book!!, c, m.size, speed, skipSilence, boostMb)
            }
        }
        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        _ui.value = PlayerUi(now, playing, position, duration, sleep.remainingMs)
    }

    private companion object {
        const val TAG = "PlayerEngine"
        const val FOCUS_TAG = FocusPolicy.TAG
        const val MODE_MUSIC = "music"
        const val MODE_BOOK = "audiobook"
        const val TICK_MS = 1_000L
        const val FADE_TICK_MS = 200L
        const val FADE_WINDOW_MS = 11_000L
        const val BOOK_SAVE_MS = 5_000L
        const val MUSIC_SAVE_MS = 15_000L
        const val SAVE_TIMEOUT_MS = 2_000L
        const val DEFAULT_SKIP_S = 30
        const val RESTART_CHAPTER_MS = 3_000L
        const val DUCK_VOLUME = 0.2f
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f

        fun exoRepeat(repeat: Int) = when (repeat) {
            1 -> Player.REPEAT_MODE_ALL
            2 -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }

        fun attributes(speech: Boolean): AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(if (speech) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
    }
}
