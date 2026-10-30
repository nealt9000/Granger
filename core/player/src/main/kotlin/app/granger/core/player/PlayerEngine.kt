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
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.session.MediaSession
import app.granger.core.library.Albums
import app.granger.core.library.AudioBookmark
import app.granger.core.library.Audiobook
import app.granger.core.library.AudiobookChapter
import app.granger.core.library.AudiobookState
import app.granger.core.library.MediaAddress
import app.granger.core.library.MediaLibrary
import app.granger.core.library.MusicState
import app.granger.core.library.PlaybackDao
import app.granger.core.library.RecentPlay
import app.granger.core.library.Track
import app.granger.core.library.VoiceState
import app.granger.core.player.PositionSaver.SaveReason
import app.granger.core.player.voice.AudioTrackSpeechOutput
import app.granger.core.player.voice.ReadAloudEngine
import app.granger.core.player.voice.SpeechOutput
import app.granger.core.player.voice.VoiceProgress
import app.granger.core.speech.InstalledVoices
import app.granger.core.speech.ReadAddress
import app.granger.core.speech.ReadSource
import app.granger.core.speech.Speech
import app.granger.core.speech.SpeechException
import app.granger.core.speech.Utterance
import app.granger.core.speech.VoiceId
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    /** A cover path as the items' artwork URI (the app passes its artwork provider's content:// URI). */
    private val artwork: (String) -> Uri = { Uri.fromFile(File(it)) },
    /** The voice (Plan V1). [speechOutput] null: an [AudioTrackSpeechOutput] made on first use (tests inject a fake). */
    private val speechOutput: SpeechOutput? = null,
    private val voices: InstalledVoices? = null,
    /** Reopens a saved source by its key and saved title for a restore; null when nothing can be reopened. */
    private val readSources: (suspend (key: String, title: String) -> ReadSource?)? = null,
    /** The voice the user chose (Settings, or the player's Reader tab): every read uses it while it is installed. */
    private val preferredVoice: () -> VoiceId? = { null },
    private val defaultVoiceSpeed: () -> Float = { 1f },
    /**
     * Makes the second player that plays music under the voice (Plan V2), with its gain processor when it has one.
     * Null (the default, and tests without one): starting the voice pauses music, as in V1.
     */
    private val musicUnderFactory: (() -> Pair<ExoPlayer, ReplayGainProcessor?>)? = null,
) : PlayerHub {
    private enum class Mode { NONE, MUSIC, BOOK, VOICE }

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

    // Voice (Plan V1). ExoPlayer plays a silent carrier item so the session, notification, focus and sleep timer behave
    // as for a book; the speech itself goes to [output]. The speech engine is opened on the first play, not on a restore.
    private var output: SpeechOutput? = null
    private val readAloud: ReadAloudEngine by lazy {
        val out = speechOutput ?: AudioTrackSpeechOutput()
        output = out
        ReadAloudEngine(out, scope).also { engine ->
            scope.launch { engine.progress.collect { if (mode == Mode.VOICE && it != null) onVoiceProgress() } }
            scope.launch { engine.ended.collect { if (mode == Mode.VOICE) onVoiceEnded() } }
            scope.launch { engine.held.collect { if (mode == Mode.VOICE) onChapterEndReached() } }
            engine.holdBefore = ::pastSleepChapter
            scope.launch { engine.errors.collect { Log.w(TAG, "Voice: ${it.message}") } }
            // One line per synthesized sentence: the screen-off run (Plan V1 Task 9) reads these from logcat.
            scope.launch {
                engine.stats.collect {
                    Log.i(VOICE_TAG, "Voice: synth chars=${it.chars} synthMs=${it.synthMs} audioMs=${it.audioMs} rtf=${"%.2f".format(java.util.Locale.ROOT, it.rtf)} screen=${screenState()}")
                }
            }
        }
    }
    private var voiceSource: ReadSource? = null
    private var voiceId: VoiceId? = null
    private var voiceSpeech: Speech? = null
    private var voiceSpeed = 1f
    private var voiceFrom: ReadAddress? = null      // where to start speaking: the restored place, or null for the start
    private var voiceOpen = false                   // the speech engine is loaded and the source is queued
    private var voiceEnded = false
    private var voiceSection: String? = null

    // The voice's End of chapter (Plan U2). [voiceSleepChapter] is the chapter of the source the timer waits for the
    // end of (-1: before the first chapter; null: no such timer). The voice stops at the real end: the read-aloud
    // engine is held before the first sentence of a later chapter ([pastSleepChapter]), or the source runs out. The
    // countdown is only an estimate: [voiceSleepLeft] holds, for each sentence to the chapter's end, how many are
    // left from it, and each takes [utteranceMs] (measured between sentences, at 1×).
    private var voiceSleepChapter: Int? = null
    private var voiceSleepLeft: Map<ReadAddress, Int> = emptyMap()
    private var voiceSleepCount: Job? = null
    private var utteranceMs = DEFAULT_UTTERANCE_MS
    private var lastUtteranceAt = 0L
    private var lastUtteranceIndex = -1

    // Music under the voice (Plan V2). A second ExoPlayer plays the music queue at [mix] while the main player keeps
    // the voice's carrier, so the session, the notification and the headset go on driving the voice. It is made when
    // music goes under the voice and released when the voice ends or stops, when the queue goes back to the main
    // player where it was. [musicUnderHeld]: paused together with the voice (a call, a headset unplug, the sleep
    // timer), so it plays again with it; the music row's own pause clears it.
    private var musicUnder: ExoPlayer? = null
    private var musicUnderGain: ReplayGainProcessor? = null
    private var musicUnderHeld = false
    private var mix = prefs.getFloat(PlayerSettingsKeys.MIX_LEVEL, PlayerSettingsKeys.DEFAULT_MIX).coerceIn(0f, 1f)
    private val voiceOverMusic: Boolean get() = mode == Mode.VOICE && musicUnder != null

    /** The voice is reading (or about to): music started now goes under it. A paused voice gives way to the music instead. */
    private val voiceReading: Boolean get() = mode == Mode.VOICE && loaded && player.playWhenReady && !voiceEnded

    /** The second player, for tests. */
    internal val musicUnderPlayer: ExoPlayer? get() = musicUnder

    private val musicUnderListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = musicUnder ?: return
            musicQueue?.select(p.currentMediaItemIndex)
            musicUnderGain?.onMediaItemTransition(mediaItem?.mediaId)
            refresh()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = refresh()
        override fun onPlaybackStateChanged(playbackState: Int) = refresh()

        // With the voice paused, the music's own play is all that keeps the ticker (and the Music tab's bar) going.
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateTicker()
            refresh()
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = musicUnder ?: return
            Log.w(TAG, "Music under the voice: playback error", error)
            if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare() } else p.pause()
        }
    }

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
            // The carrier drives the voice: whoever paused ExoPlayer (user, focus, headset, sleep timer) paused the voice.
            if (mode == Mode.VOICE && voiceOpen) {
                Log.i(VOICE_TAG, "Voice: ${if (playWhenReady) "play" else "pause"} at #${readAloud.progress.value?.index}")
                if (playWhenReady) readAloud.play() else readAloud.pause()
            }
            // A headset unplug pauses the main player by itself; the music under the voice goes with it (and comes back with it).
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) holdMusicUnder()
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
            holdMusicUnder()
        }

        override fun onLoss() {
            Log.i(FOCUS_TAG, "loss: pausing, staying paused")
            pausedForFocus = false
            player.pause()
            musicUnder?.pause()
            musicUnderHeld = false
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
            if (musicUnderHeld) musicUnderPlay()
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

    private fun submit(command: suspend () -> Unit) { trySubmit(command) }

    private fun trySubmit(command: suspend () -> Unit): Boolean = !released && commands.trySend(command).isSuccess

    // ---- restore and release (the service's lifecycle) ----

    /** Loads the last active player's saved state, paused (after the service is recreated). */
    fun restore() = submit { restoreLast() }

    private suspend fun restoreLast() {
        when (prefs.getString(PlayerSettingsKeys.LAST_MODE, null)) {
            MODE_BOOK -> prefs.getString(PlayerSettingsKeys.LAST_AUDIOBOOK, null)?.let { loadBook(it, null, play = false) }
            MODE_MUSIC -> playback.musicState()?.let { loadMusicState(it) }
            MODE_VOICE -> restoreVoice()
        }
    }

    /** The voice's last place, paused, if its source can still be opened; a source that is gone clears the row. */
    private suspend fun restoreVoice() {
        val key = prefs.getString(PlayerSettingsKeys.LAST_VOICE, null) ?: return
        val state = playback.voiceState(key) ?: return
        val source = runCatching { readSources?.invoke(key, state.title) }.getOrNull()
        if (source == null) {
            Log.i(TAG, "Voice: $key is gone; forgetting its place")
            withContext(writerDispatcher) { playback.deleteVoiceState(key) }
            prefs.edit().remove(PlayerSettingsKeys.LAST_VOICE).apply()
            return
        }
        Log.i(VOICE_TAG, "Voice: restoring $key at ${state.address.take(80)}")
        // The voice the user chose reads every text (the walkthrough's switch: a restore went back to the voice the
        // text was last read in); the text's own voice only stands in while the chosen one isn't installed.
        val saved = VoiceId.decode(state.voiceId)
        val voice = voices?.forResume(preferredVoice(), saved) ?: saved
        startVoice(source, ReadAddress.decode(state.address), voice, state.speed, play = false)
    }

    /**
     * Loads the last active player paused from its saved state if nothing is loaded; then its items, index and
     * position (null when nothing was ever saved). For a play press after the process died (the session's playback
     * resumption). Runs in turn with the hub's calls, so a restore already queued is waited for, not repeated; like
     * any restore it writes nothing before its seek is confirmed.
     */
    suspend fun resumption(): MediaSession.MediaItemsWithStartPosition? = inTurn(restore = true)

    /**
     * The loaded items, index and position, once every call sent before this one has run (null when nothing is
     * loaded then). For a controller's request ([LibraryCallback.onSetMediaItems]): the tree has just asked the hub to
     * play something, and this answers with what the engine loaded for it. Loads nothing itself.
     */
    suspend fun loadedItems(): MediaSession.MediaItemsWithStartPosition? = inTurn(restore = false)

    private suspend fun inTurn(restore: Boolean): MediaSession.MediaItemsWithStartPosition? {
        val result = CompletableDeferred<MediaSession.MediaItemsWithStartPosition?>()
        val sent = trySubmit {
            try {
                if (restore && !loaded) restoreLast()
                if (loaded && player.mediaItemCount > 0) {
                    val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                    result.complete(MediaSession.MediaItemsWithStartPosition(
                        items, player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0L),
                    ))
                }
            } finally {
                result.complete(null)
            }
        }
        return if (sent) result.await() else null
    }

    /** Writes the current position now and waits for it (task removed, service destroyed). */
    fun saveNow() {
        if (released) return
        runBlocking { withTimeoutOrNull(SAVE_TIMEOUT_MS) { saver.onEvent(SaveReason.STOP) } }
    }

    /** Saves (unless [save] is false, which tests use to stand for the process being killed), then releases all. */
    fun release(save: Boolean = true) {
        if (released) return
        if (save) {
            saveNow()
            if (voiceOverMusic) runBlocking { withTimeoutOrNull(SAVE_TIMEOUT_MS) { saveMusicUnder() } }
        }
        releaseMusicUnder()
        released = true
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        commands.close()
        ticker.removeCallbacksAndMessages(null)
        player.removeListener(listener)
        focus.listener = null
        focus.abandon()
        effects.close()
        scope.cancel()
        runCatching { voiceSpeech?.close() }
        runCatching { output?.close() }
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
            Mode.VOICE -> (readAloud.progress.value?.address ?: voiceFrom)?.encode()
            Mode.NONE -> null
        }
    }

    // ---- PlayerHub ----

    override fun playTracks(trackIds: List<String>, startIndex: Int, shuffle: Boolean, source: PlaySource) = submit {
        val found = trackIds.mapIndexedNotNull { i, id -> media.track(id)?.let { i to it } }
        if (found.isEmpty()) return@submit
        val start = found.indexOfFirst { it.first >= startIndex }.takeIf { it >= 0 } ?: 0
        val list = found.map { it.second }
        val q = MusicQueue(list.map { it.id }, start, random)
        if (shuffle) q.setShuffle(true)
        if (voiceReading && musicUnderFactory != null) {
            // Music started while the voice reads goes under it (Plan V2); the voice keeps reading.
            sourceKey = source.key
            startMusicUnder(q, list.associateBy { it.id }, positionMs = 0L, play = focus.request(speech = true))
            playback.upsertRecentPlay(RecentPlay(source.key, source.title, now()))
            return@submit
        }
        stopCurrent()
        sourceKey = source.key
        startMusic(q, list.associateBy { it.id }, positionMs = 0L)
        playback.upsertRecentPlay(RecentPlay(source.key, source.title, now()))
        resume()
    }

    override fun playAudiobook(id: String, at: AudioBookmark?) = submit { startBook(id, at) }

    // ---- the voice ----

    override val voiceProgress: StateFlow<VoiceProgress?> get() = readAloud.progress

    override fun readAloud(source: ReadSource, from: ReadAddress?, voice: VoiceId?) = submit {
        Log.i(VOICE_TAG, "Voice: read ${source.key} from ${from?.encode()?.take(80)}")
        // Music playing now goes under the voice (Plan V2): the queue moves to the second player where it was.
        // Music already under the voice stays there while the voice is replaced.
        val fromMain = mode == Mode.MUSIC && loaded && musicUnderFactory != null
        val q = musicQueue
        val byId = tracks
        val position = player.currentPosition.coerceAtLeast(0L)
        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        val kept = voiceOverMusic
        stopCurrent(keepMusicUnder = kept)
        if (!startVoice(source, from, voice, defaultVoiceSpeed(), play = true, keepMusicUnder = kept)) {
            if (kept) releaseMusicUnder()
            return@submit
        }
        if (fromMain && q != null) startMusicUnder(q, byId, position, play = playing)
        playback.upsertRecentPlay(RecentPlay("voice:${source.key}", source.title, now()))
    }

    override fun stopVoice() = submit {
        if (mode != Mode.VOICE) return@submit
        if (voiceOverMusic) handToMusic() else stopCurrent()
        refresh()
    }

    override fun stop() = submit {
        if (mode == Mode.NONE) return@submit
        stopCurrent()
        refresh()
    }

    override fun nextParagraph() = submit { if (mode == Mode.VOICE && voiceOpen) { Log.i(VOICE_TAG, "Voice: next paragraph"); readAloud.nextParagraph() } }
    override fun previousParagraph() = submit { if (mode == Mode.VOICE && voiceOpen) { Log.i(VOICE_TAG, "Voice: previous paragraph"); readAloud.previousParagraph() } }
    override fun nextSentence() = submit { if (mode == Mode.VOICE && voiceOpen) readAloud.nextSentence() }
    override fun previousSentence() = submit { if (mode == Mode.VOICE && voiceOpen) readAloud.previousSentence() }
    override fun jumpTo(address: ReadAddress) = submit {
        if (mode != Mode.VOICE) return@submit
        Log.i(VOICE_TAG, "Voice: jump to ${address.encode().take(80)}")
        if (voiceOpen) readAloud.jumpTo(address) else voiceFrom = address
        // A place picked moves the End of chapter timer with it, as a chapter picked in an audiobook does.
        if (voiceSleepChapter != null) aimVoiceSleep(address)
        refresh()
    }

    override fun jumpToChapter(index: Int) = submit {
        if (mode != Mode.VOICE) return@submit
        val address = voiceSource?.let { runCatching { it.chapterStart(index) }.getOrNull() } ?: return@submit
        jumpTo(address)
    }

    override suspend fun voiceChapters(): VoiceChapters? {
        if (mode != Mode.VOICE || !loaded) return null
        val source = voiceSource ?: return null
        val at = (if (voiceOpen) readAloud.progress.value?.address else null) ?: voiceFrom
        return runCatching { VoiceChapters(source.chapters(), at?.let { source.chapterAt(it) }) }.getOrNull()
    }

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
                // Music while the voice reads goes under it (Plan V2): the Music tab's play starts the saved queue there.
                if (voiceOverMusic) return@submit musicUnderPlay()
                if (voiceReading && musicUnderFactory != null) {
                    val state = playback.musicState() ?: return@submit
                    val restored = restoreQueue(state, random) { media.track(it) } ?: return@submit
                    sourceKey = state.sourceKey
                    startMusicUnder(restored.queue, restored.tracks, state.positionMs, play = focus.request(speech = true))
                    return@submit
                }
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
            Mode.VOICE -> voiceOverMusic && musicQueue?.ids?.any { it in trackIds } == true
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
            Mode.VOICE -> if (voiceOpen) readAloud.nextParagraph()
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
            Mode.VOICE -> if (voiceOpen) { Log.i(VOICE_TAG, "Voice: previous (session)"); readAloud.previousParagraph() }
            Mode.NONE -> Unit
        }
    }

    override fun skipBack() = submit {
        if (mode == Mode.VOICE) { if (voiceOpen) readAloud.previousSentence(); return@submit }
        if (mode != Mode.BOOK) return@submit
        userMoved()
        seekBookBy(-prefs.getInt(PlayerSettingsKeys.SKIP_BACK_S, DEFAULT_SKIP_S) * 1_000L)
    }

    override fun skipForward() = submit {
        if (mode == Mode.VOICE) { if (voiceOpen) readAloud.nextSentence(); return@submit }
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
            Mode.VOICE, Mode.NONE -> Unit
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

    override fun playQueueItem(position: Int) = submit {
        val q = musicQueue ?: return@submit
        if (mode != Mode.MUSIC || position !in q.ids.indices) return@submit
        playItem(q.playOrder[position], 0L)
    }

    /** A controller's seek to playlist item [index] (Auto's queue): plays it, as a tap on its queue row does. */
    internal fun playMediaItem(index: Int, positionMs: Long) = submit {
        val q = musicQueue ?: return@submit
        if (mode != Mode.MUSIC || index !in q.ids.indices) return@submit
        playItem(index, positionMs)
    }

    private fun playItem(index: Int, positionMs: Long) {
        // The transition re-points the queue at the item (select); the play order is left as it is.
        player.seekTo(index, positionMs.coerceAtLeast(0L))
        resume()
    }

    override fun playNext(trackIds: List<String>) = submit { insert(trackIds, next = true) }

    override fun addToQueue(trackIds: List<String>) = submit { insert(trackIds, next = false) }

    // ---- music under the voice (Plan V2) ----

    override fun setMixLevel(level: Float) = submit {
        mix = level.coerceIn(0f, 1f)
        prefs.edit().putFloat(PlayerSettingsKeys.MIX_LEVEL, mix).apply()
        applyVolume()
    }

    override fun playPauseMusic() = submit {
        val p = musicUnder ?: return@submit
        if (!voiceOverMusic) return@submit
        if (p.playWhenReady && p.playbackState != Player.STATE_ENDED) {
            musicUnderHeld = false
            p.pause()
        } else {
            musicUnderPlay()
        }
    }

    override fun seekMusic(positionMs: Long) = submit {
        val p = musicUnder ?: return@submit
        if (!voiceOverMusic) return@submit
        p.seekTo(positionMs.coerceAtLeast(0L))
        refresh()
    }

    override fun nextMusic() = submit {
        val p = musicUnder ?: return@submit
        val target = p.nextMediaItemIndex
        if (target != C.INDEX_UNSET) p.seekTo(target, 0L)
    }

    override fun setVoice(voice: VoiceId) = submit {
        if (mode != Mode.VOICE || voice == voiceId) return@submit
        val list = voices ?: return@submit
        if (list.voices.value.none { it.id == voice }) return@submit
        Log.i(VOICE_TAG, "Voice: switching to ${voice.encode()}")
        val wasPlaying = player.playWhenReady && !voiceEnded
        // Pause through the carrier (the voice follows), reopen with the new engine at this sentence, then play again
        // the same way, so the listener's play reaches the new engine.
        player.pause()
        voiceFrom = (if (voiceOpen) readAloud.progress.value?.address else null) ?: voiceFrom
        if (voiceOpen) readAloud.stop()
        voiceOpen = false
        voiceSpeech = null
        voiceId = voice
        save(SaveReason.SEEK)
        refresh()
        if (wasPlaying) resume()
    }

    /** Puts [q] on the second player at [positionMs], at the mix level, under the voice; false without a factory. */
    private fun startMusicUnder(q: MusicQueue, byId: Map<String, Track>, positionMs: Long, play: Boolean): Boolean {
        val make = musicUnderFactory ?: return false
        releaseMusicUnder()
        val (p, gain) = make()
        musicUnder = p
        musicUnderGain = gain
        musicQueue = q
        tracks = byId
        p.addListener(musicUnderListener)
        p.setAudioAttributes(attributes(speech = false), false)
        p.setHandleAudioBecomingNoisy(false)                  // the main player's unplug pauses both ([holdMusicUnder])
        p.setMediaItems(q.ids.map { item(byId.getValue(it), null) }, q.index, positionMs)
        p.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(q.playOrder.toIntArray(), random.nextLong()))
        p.shuffleModeEnabled = q.shuffle
        p.repeatMode = exoRepeat(q.repeat)
        gain?.setGains(gainsFor(q))
        applyVolume()
        p.prepare()
        if (play) p.play()
        _queue.value = q.playOrder.map { byId.getValue(q.ids[it]) }
        Log.i(VOICE_TAG, "Voice: music under the voice at ${"%.2f".format(java.util.Locale.ROOT, mix)}: ${q.ids.size} tracks from #${q.index}")
        refresh()
        return true
    }

    private fun releaseMusicUnder() {
        musicUnder?.let { it.removeListener(musicUnderListener); it.release() }
        musicUnder = null
        musicUnderGain = null
        musicUnderHeld = false
    }

    /** Pauses the music under the voice together with it; [musicUnderPlay] brings it back with the voice. */
    private fun holdMusicUnder() {
        val p = musicUnder ?: return
        if (p.playWhenReady) { musicUnderHeld = true; p.pause() }
    }

    private fun musicUnderPlay() {
        val p = musicUnder ?: return
        musicUnderHeld = false
        if (!focus.request(speech = true)) return
        if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition(p.currentTimeline.getFirstWindowIndex(p.shuffleModeEnabled))
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
        refresh()
    }

    /** Writes the music's place from the second player (the saver only follows the active mode, the voice). */
    private suspend fun saveMusicUnder() {
        val p = musicUnder ?: return
        val q = musicQueue ?: return
        val state = q.toState(p.currentPosition.coerceAtLeast(0L), sourceKey, now())
        withContext(writerDispatcher) { write(Snapshot.Music(state)) }
    }

    /** The voice ended or was stopped with music under it: the queue goes back to the main player where it was, at full volume, as the mode. */
    private suspend fun handToMusic() {
        val p = musicUnder ?: return
        val q = musicQueue ?: return
        Log.i(VOICE_TAG, "Voice: done; music comes back to the front")
        saver.onEvent(SaveReason.STOP)                          // the voice's place
        if (voiceOpen) readAloud.stop()
        voiceOpen = false; voiceEnded = false
        voiceSource = null; voiceSpeech = null; voiceFrom = null; voiceSection = null
        val position = p.currentPosition.coerceAtLeast(0L)
        val wasPlaying = p.playWhenReady && p.playbackState != Player.STATE_ENDED
        val byId = tracks
        p.pause()
        releaseMusicUnder()
        cancelSleep()
        player.pause()
        player.clearMediaItems()
        startMusic(q, byId, position)
        applyVolume()
        if (wasPlaying) resume()
        refresh()
    }

    /** The music queue's current track as the UI shows it, read from [p] (the main player or the one under the voice). */
    private fun musicNow(p: Player): NowPlaying.Music? {
        val q = musicQueue ?: return null
        val track = tracks[p.currentMediaItem?.mediaId ?: q.ids[q.index]] ?: return null
        return NowPlaying.Music(track, q.playOrder.indexOf(q.index), q.ids.size, q.shuffle, q.repeat, sourceKey)
    }

    override fun setSpeed(speed: Float) = submit {
        if (mode == Mode.VOICE) {
            voiceSpeed = speed.coerceIn(MIN_VOICE_SPEED, MAX_VOICE_SPEED)
            if (voiceOpen) readAloud.setSpeed(voiceSpeed)
            save(SaveReason.SEEK)
            refresh()
            return@submit
        }
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
        if (mode == Mode.VOICE && setting == SleepSetting.EndOfChapter) {
            sleepTarget = -1
            aimVoiceSleep((if (voiceOpen) readAloud.progress.value?.address else null) ?: voiceFrom)
            sleep.start(setting, estimated = true, chapterEndsInMs = ::untilSleepTargetEnds)
        } else {
            retargetSleep(force = true)
            sleep.start(setting, chapterEndsInMs = ::untilSleepTargetEnds)
        }
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
    private suspend fun stopCurrent(keepMusicUnder: Boolean = false) {
        if (voiceOverMusic && !keepMusicUnder) saveMusicUnder()
        if (mode != Mode.NONE) saver.onEvent(SaveReason.STOP)   // waits for the write, so a reload reads it
        cancelSleep()
        loaded = false
        player.pause()
        if (mode == Mode.VOICE) {
            if (voiceOpen) readAloud.stop()
            voiceOpen = false; voiceEnded = false
            voiceSource = null; voiceSpeech = null; voiceFrom = null; voiceSection = null
            if (!keepMusicUnder) releaseMusicUnder()
        }
        player.clearMediaItems()
        effects.setBoost(0)
        mode = Mode.NONE
        if (!keepMusicUnder) _queue.value = emptyList()
    }

    /**
     * Makes the voice the active mode for [source] at [from] with [wanted] when it is installed (else the chosen
     * voice, else the first): the carrier item is loaded and the state saved as "voice"; the speech engine itself
     * opens on the first play ([openVoice]), so a restore after process death costs nothing until the user presses
     * play. False when no voice can speak.
     */
    private suspend fun startVoice(
        source: ReadSource, from: ReadAddress?, wanted: VoiceId?, speed: Float, play: Boolean, keepMusicUnder: Boolean = false,
    ): Boolean {
        val list = voices ?: return false
        if (list.voices.value.isEmpty()) list.refresh()
        val voice = list.installed(wanted) ?: list.preferred(preferredVoice()) ?: run { Log.i(TAG, "Voice: nothing installed can speak"); return false }
        voiceSource = source
        voiceId = voice.id
        voiceSpeed = speed.coerceIn(MIN_VOICE_SPEED, MAX_VOICE_SPEED)
        voiceFrom = from
        voiceOpen = false
        voiceEnded = false
        voiceSection = from?.let { runCatching { source.sectionTitle(it) }.getOrNull() }
        if (!keepMusicUnder) { musicQueue = null; tracks = emptyMap() }
        book = null; map = null
        mode = Mode.VOICE
        prefs.edit().putString(PlayerSettingsKeys.LAST_MODE, MODE_VOICE).putString(PlayerSettingsKeys.LAST_VOICE, source.key).apply()

        player.setAudioAttributes(attributes(speech = true), false)
        player.shuffleModeEnabled = false
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.setPlaybackSpeed(1f)
        player.skipSilenceEnabled = false
        replayGain?.setGains(emptyMap())
        effects.setBoost(0)
        player.setMediaSource(carrier(source, voiceSection))
        awaitingReady = true
        saveWhenReady = play
        loaded = true
        player.prepare()
        pausedAt = 0L
        refresh()
        if (play) resume()
        return true
    }

    /** A long silent item carrying the voice's metadata, so the session and the notification show "Reading · <title>". */
    private fun carrier(source: ReadSource, section: String?): MediaSource {
        val item = MediaItem.Builder()
            .setMediaId("voice:${source.key}")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Reading · ${source.title}").setArtist(section).setAlbumTitle(source.title).build())
            .build()
        return CarrierSource(item, CARRIER_US)
    }

    /** Silence whose timeline window and media item are [item]'s, so `currentMediaItem` and the session see the voice's metadata. */
    private class CarrierSource(private val item: MediaItem, durationUs: Long) : WrappingMediaSource(SilenceMediaSource(durationUs)) {
        override fun getMediaItem(): MediaItem = item
        override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
            refreshSourceInfo(object : ForwardingTimeline(newTimeline) {
                override fun getWindow(windowIndex: Int, window: Timeline.Window, defaultPositionProjectionUs: Long): Timeline.Window =
                    super.getWindow(windowIndex, window, defaultPositionProjectionUs).also { it.mediaItem = item }
            })
        }
    }

    /** Loads the speech engine for the chosen voice and queues the source from its place, without speaking yet. */
    private suspend fun openVoice(): Boolean {
        val source = voiceSource ?: return false
        val list = voices ?: return false
        val id = voiceId ?: return false
        val voice = list.voices.value.firstOrNull { it.id == id } ?: list.preferred(preferredVoice())?.also { voiceId = it.id } ?: return false
        val speech = voiceSpeech ?: try {
            list.open(voice)
        } catch (e: SpeechException) {
            Log.w(TAG, "Voice: ${voice.title} didn't open", e)
            return false
        }
        voiceSpeech = speech
        readAloud.start(source, speech, voiceFrom, voiceSpeed, autoplay = false)
        voiceOpen = true
        voiceEnded = false
        return true
    }

    private fun onVoiceProgress() {
        val p = readAloud.progress.value ?: return
        val source = voiceSource ?: return
        Log.i(VOICE_TAG, "Voice: at #${p.index} ¶${p.utterance.paragraph} \"${p.utterance.text.take(40)}\"")
        measureUtterance(p.index)
        if (voiceSleepChapter != null && p.address !in voiceSleepLeft) countVoiceSleep(p.address)
        scope.launch {
            val section = runCatching { source.sectionTitle(p.address) }.getOrNull()
            if (section != voiceSection) { voiceSection = section; refresh() }
        }
        save(SaveReason.TRACK)
        refresh()
    }

    private fun onVoiceEnded() {
        voiceEnded = true
        if (voiceSleepChapter != null) {
            // The text ended inside the chapter End of chapter waited for: that was its end. Music under it sleeps too.
            Log.i(VOICE_TAG, "Voice: End of chapter: the text ended")
            holdMusicUnder()
            cancelSleep()
        }
        if (voiceOverMusic) {
            // The music under the voice becomes the mode again (Plan V2); the voice's place is saved on the way.
            submit { if (voiceOverMusic) handToMusic() }
            return
        }
        player.pause()
        save(SaveReason.STOP)
        refresh()
    }

    private fun startMusic(q: MusicQueue, byId: Map<String, Track>, positionMs: Long) {
        musicQueue = q
        tracks = byId
        mode = Mode.MUSIC
        book = null; map = null
        prefs.edit().putString(PlayerSettingsKeys.LAST_MODE, MODE_MUSIC).putString(PlayerSettingsKeys.LAST_MEDIA, MODE_MUSIC).apply()
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
        prefs.edit().putString(PlayerSettingsKeys.LAST_MODE, MODE_BOOK).putString(PlayerSettingsKeys.LAST_MEDIA, MODE_BOOK)
            .putString(PlayerSettingsKeys.LAST_AUDIOBOOK, id).apply()

        player.setAudioAttributes(attributes(speech = true), false)
        player.shuffleModeEnabled = false
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.setPlaybackSpeed(speed)
        player.skipSilenceEnabled = skipSilence
        replayGain?.setGains(emptyMap())          // ReplayGain is for music
        effects.open(player.audioSessionId, speech = true)
        effects.setBoost(boostMb)
        val startChapter = m.chapterAt(fileIndex ?: 0, position)
        // Each file is titled with the chapter it starts in (the start file with the chapter it starts at); a
        // chapter change within a file retitles it then ([showChapter]).
        player.setMediaItems(files.mapIndexed { i, f ->
            item(f, b, if (i == (fileIndex ?: 0)) startChapter else m.chapterAt(i, 0L))
        }, fileIndex ?: 0, position)
        lastChapter = startChapter.ordinal
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

    /** A song, or a book's file titled with [chapter] (the book as album, its author as artist). */
    private fun item(t: Track, b: Audiobook?, chapter: AudiobookChapter? = null): MediaItem = MediaItem.Builder()
        .setMediaId(t.id)
        .setUri(t.uri)
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(if (b == null) t.title else chapterTitle(b, chapter))
            .setArtist(b?.author ?: t.artists.replace("; ", ", "))
            .setAlbumTitle(b?.title ?: Albums.title(t))
            .setArtworkUri((b?.coverPath ?: t.coverPath)?.let(artwork))
            .build())
        .build()

    // ---- playback ----

    /** Play, with focus; an audiobook steps back by [AutoRewind] for the time it was paused. */
    private fun resume() {
        if (!loaded) return
        if (mode == Mode.VOICE && (!voiceOpen || voiceEnded)) {
            // First play (or play again after the end): open the engine in turn, then come back here.
            submit {
                if (voiceEnded && voiceOpen) { voiceFrom = null; voiceOpen = false }
                // A second play queued while the first was opening (Listen tapped twice as the voice loaded) finds it
                // open: opening again would restart the voice paused under a carrier already playing, and silent.
                if (voiceOpen || openVoice()) resume()
            }
            return
        }
        if (sleep.expired()) cancelSleep()
        if (!focus.request(speech = mode != Mode.MUSIC)) {
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
        // The carrier may already have been playing (so no play-when-ready change reaches the listener): the voice plays too.
        if (mode == Mode.VOICE && voiceOpen) readAloud.play()
        if (musicUnderHeld) musicUnderPlay()                 // paused with the voice: plays again with it
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
        val chapter = m.chapterAt(player.currentMediaItemIndex, player.currentPosition)
        val c = chapter.ordinal
        if (c != lastChapter) {
            val first = lastChapter == -1
            lastChapter = c
            if (!first) save(SaveReason.CHAPTER)
        }
        showChapter(chapter)
    }

    /**
     * The session's title is the chapter's: the current item's metadata is replaced in place when it names another
     * chapter. Same URI, so ExoPlayer updates the item without re-preparing it (no gap, no position jump; pinned by
     * `chapterChangeUpdatesTheTitleWithoutRepreparing`).
     */
    private fun showChapter(chapter: AudiobookChapter) {
        val b = book ?: return
        val index = player.currentMediaItemIndex
        val current = player.currentMediaItem ?: return
        val title = chapterTitle(b, chapter)
        if (current.mediaMetadata.title?.toString() == title) return
        player.replaceMediaItem(index, current.buildUpon().setMediaMetadata(current.mediaMetadata.buildUpon().setTitle(title).build()).build())
    }

    private fun chapterTitle(b: Audiobook, chapter: AudiobookChapter?): String = chapter?.title?.takeIf { it.isNotBlank() } ?: b.title

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
        rg.setGains(gainsFor(q))
    }

    /** Each track's ReplayGain factor for [q] under the chosen mode (album gain only for an album played in order). */
    private fun gainsFor(q: MusicQueue): Map<String, Float> {
        val mode = runCatching { ReplayGainMode.valueOf(prefs.getString(PlayerSettingsKeys.REPLAYGAIN_MODE, null) ?: "AUTO") }
            .getOrDefault(ReplayGainMode.AUTO)
        val albumInOrder = sourceKey.startsWith("album:") && !q.shuffle
        return tracks.mapValues { ReplayGainMath.factor(it.value, mode, albumInOrder) }
    }

    // ---- saving ----

    private fun save(reason: SaveReason) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { saver.onEvent(reason) }
    }

    private sealed interface Snapshot {
        data class Book(val state: AudiobookState) : Snapshot
        data class Music(val state: MusicState) : Snapshot
        data class Voice(val state: VoiceState) : Snapshot
    }

    private fun snapshot(): Snapshot? {
        if (!loaded || awaitingReady || player.mediaItemCount == 0) return null
        val position = player.currentPosition.coerceAtLeast(0L)
        return when (mode) {
            Mode.VOICE -> {
                val src = voiceSource ?: return null
                val id = voiceId ?: return null
                val address = (if (voiceOpen) readAloud.progress.value?.address else null) ?: voiceFrom ?: return null
                Snapshot.Voice(VoiceState(src.key, address.encode(), voiceSpeed, src.title, voiceSection, id.encode(), now()))
            }
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
            is Snapshot.Voice -> playback.upsertVoiceState(snapshot.state)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Couldn't save the playback position", e)
    }

    // ---- ticks, sleep timer, volume ----

    /** Ticks while playing, and while a sleep timer runs; every second, or every 200 ms through the fade. */
    private fun updateTicker() {
        val want = !released && loaded && (player.isPlaying || musicUnder?.isPlaying == true || sleep.remainingMs != null)
        if (want == ticking) return
        ticking = want
        ticker.removeCallbacks(tickRunnable)
        if (want) ticker.postDelayed(tickRunnable, tickPeriod())
    }

    private fun tickPeriod(): Long = sleep.remainingMs?.takeIf { it <= FADE_WINDOW_MS }?.let { FADE_TICK_MS } ?: TICK_MS

    private fun tick() {
        if (!ticking || released) return
        if (player.isPlaying) {
            val interval = if (mode == Mode.MUSIC) MUSIC_SAVE_MS else BOOK_SAVE_MS
            scope.launch(start = CoroutineStart.UNDISPATCHED) { saver.onTick(interval) }
            checkChapter()
        }
        if (sleep.remainingMs != null) {
            if (sleep.expired()) {
                if (player.playWhenReady) player.pause()   // pause, then cancel and restore the volume
                holdMusicUnder()                           // the music under the voice sleeps with it
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
        voiceSleepChapter = null
        voiceSleepLeft = emptyMap()
        voiceSleepCount?.cancel(); voiceSleepCount = null
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
            Mode.VOICE, Mode.NONE -> -1
        }
    }

    /** Wall-clock ms until the pinned chapter (or song) ends: media time ÷ speed; 0 once past it. The voice: [untilVoiceChapterEnds]. */
    private fun untilSleepTargetEnds(): Long? {
        if (loaded && mode == Mode.VOICE) return untilVoiceChapterEnds()
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
            Mode.VOICE, Mode.NONE -> return null
        }
        return (mediaLeft / player.playbackParameters.speed).toLong()
    }

    /**
     * Pins the voice's End of chapter to the chapter [at] is in (-1 before the first; a source without chapters waits
     * for its end) and counts the sentences to its end.
     */
    private suspend fun aimVoiceSleep(at: ReadAddress?) {
        val source = voiceSource ?: return
        voiceSleepChapter = at?.let { runCatching { source.chapterAt(it) }.getOrNull() } ?: -1
        Log.i(VOICE_TAG, "Voice: End of chapter waits for chapter $voiceSleepChapter")
        voiceSleepLeft = emptyMap()
        countVoiceSleep(at)
    }

    /**
     * The read-aloud engine asks before each sentence: true (stop here) for the first sentence of a chapter after
     * the one End of chapter waits for. Stepping back into an earlier chapter waits for that one's end instead.
     */
    private suspend fun pastSleepChapter(u: Utterance): Boolean {
        val target = voiceSleepChapter ?: return false
        val source = voiceSource ?: return false
        val chapter = runCatching { source.chapterAt(u.address) }.getOrNull() ?: return false
        if (chapter < target) { voiceSleepChapter = chapter; countVoiceSleep(u.address) }
        return chapter > target
    }

    /** The voice stopped itself at the chapter's end: pause there (the place saved is the next chapter's start) and end the timer. */
    private fun onChapterEndReached() {
        Log.i(VOICE_TAG, "Voice: End of chapter: stopped at the chapter's end")
        if (player.playWhenReady) player.pause()
        holdMusicUnder()
        cancelSleep()
    }

    /** Counts, in the background, the sentences from [from] to the end of the chapter End of chapter waits for. */
    private fun countVoiceSleep(from: ReadAddress?) {
        val source = voiceSource ?: return
        val target = voiceSleepChapter ?: return
        voiceSleepCount?.cancel()
        voiceSleepCount = scope.launch {
            val ahead = ArrayList<ReadAddress>()
            var at = from
            counting@ while (ahead.size < MAX_COUNTED) {
                val batch = runCatching { source.utterances(at, COUNT_BATCH) }.getOrDefault(emptyList())
                val fresh = if (ahead.isEmpty()) batch else batch.dropWhile { it.address == at }
                if (fresh.isEmpty()) break
                for (u in fresh) {
                    val chapter = runCatching { source.chapterAt(u.address) }.getOrNull()
                    if (chapter != null && chapter > target) break@counting
                    ahead += u.address
                }
                at = fresh.last().address
            }
            if (voiceSleepChapter != target) return@launch
            voiceSleepLeft = ahead.withIndex().associate { (i, a) -> a to ahead.size - i }
            refresh()
        }
    }

    /** The voice's time to the chapter's end, for the countdown only: sentences left × [utteranceMs] ÷ speed, less the current one's time so far. */
    private fun untilVoiceChapterEnds(): Long? {
        if (voiceSleepChapter == null) return null
        val at = (if (voiceOpen) readAloud.progress.value?.address else null) ?: voiceFrom ?: return null
        val left = voiceSleepLeft[at] ?: return null
        val into = if (player.playWhenReady && lastUtteranceAt > 0L) now() - lastUtteranceAt else 0L
        return (left * utteranceMs / voiceSpeed - into).toLong().coerceAtLeast(0L)
    }

    /** One more sentence started: the time since the last one, at 1×, goes into [utteranceMs] (a running average). */
    private fun measureUtterance(index: Int) {
        val t = now()
        if (player.playWhenReady && index == lastUtteranceIndex + 1 && lastUtteranceAt > 0L) {
            val sample = ((t - lastUtteranceAt) * voiceSpeed).toLong()
            if (sample in MIN_UTTERANCE_MS..MAX_UTTERANCE_MS) utteranceMs = (utteranceMs * 3 + sample) / 4
        }
        lastUtteranceAt = t
        lastUtteranceIndex = index
    }

    private fun applyVolume() {
        player.volume = duck * fade
        output?.volume = duck * fade
        musicUnder?.volume = mix * duck * fade
    }

    // ---- ui ----

    /** A book with chapters, or the voice (paragraphs): the session offers next and previous (see [SessionPlayer]). */
    internal val chapterSteps: Boolean get() = loaded && ((mode == Mode.BOOK && (map?.size ?: 0) > 1) || mode == Mode.VOICE)

    /** Told (on main) when [chapterSteps] changes. */
    internal var onChapterStepsChanged: (() -> Unit)? = null
    private var lastChapterSteps = false

    private fun refresh() {
        if (released) return
        if (chapterSteps != lastChapterSteps) {
            lastChapterSteps = chapterSteps
            onChapterStepsChanged?.invoke()
        }
        val now: NowPlaying
        var position = player.currentPosition.coerceAtLeast(0L)
        var duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        when {
            !loaded || mode == Mode.NONE -> { _ui.value = PlayerUi.IDLE; return }
            mode == Mode.VOICE -> {
                val src = voiceSource ?: return
                val id = voiceId ?: return
                val p = if (voiceOpen) readAloud.progress.value else null
                duration = 0L
                val under = musicUnder
                val underMusic = under?.let(::musicNow)
                now = NowPlaying.Voice(
                    src.key, src.title, voiceSection, p?.utterance?.text ?: "", p?.utterance?.paragraph ?: 0, voiceSpeed, id, voiceEnded,
                    music = underMusic,
                    musicPlaying = under != null && under.playWhenReady && under.playbackState != Player.STATE_ENDED,
                    musicPositionMs = under?.currentPosition?.coerceAtLeast(0L) ?: 0L,
                    musicDurationMs = under?.duration?.takeIf { it != C.TIME_UNSET && it > 0L } ?: underMusic?.track?.durationMs ?: 0L,
                )
            }
            mode == Mode.MUSIC -> {
                val music = musicNow(player) ?: return
                if (duration == 0L) duration = music.track.durationMs
                now = music
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

    /** "on"/"off" for the voice's log lines; the service has no display, so the power manager's word is used when at hand. */
    private var screenState: () -> String = { "?" }

    /** Lets the service say whether the screen is on, for the voice's per-sentence log lines. */
    fun setScreenState(state: () -> String) { screenState = state }

    private companion object {
        const val TAG = "PlayerEngine"
        const val VOICE_TAG = "GrangerVoice"
        const val FOCUS_TAG = FocusPolicy.TAG
        const val MODE_MUSIC = "music"
        const val MODE_BOOK = "audiobook"
        const val MODE_VOICE = "voice"
        const val CARRIER_US = 4 * 60 * 60 * 1_000_000L   // the silent item under the voice: four hours, then it ends
        const val MIN_VOICE_SPEED = 0.7f
        const val MAX_VOICE_SPEED = 2.0f
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
        /** End of chapter for the voice: a sentence's time before any is measured, the bounds of a sample, and the counting. */
        const val DEFAULT_UTTERANCE_MS = 4_000L
        const val MIN_UTTERANCE_MS = 300L
        const val MAX_UTTERANCE_MS = 30_000L
        const val COUNT_BATCH = 200
        const val MAX_COUNTED = 5_000

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
