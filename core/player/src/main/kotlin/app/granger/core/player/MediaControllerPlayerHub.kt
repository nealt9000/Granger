package app.granger.core.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.granger.core.library.AudioBookmark
import app.granger.core.library.MediaLibrary
import app.granger.core.library.PlaybackDao
import app.granger.core.library.Track
import app.granger.core.player.voice.VoiceProgress
import app.granger.core.speech.InstalledVoices
import app.granger.core.speech.ReadAddress
import app.granger.core.speech.ReadSource
import app.granger.core.speech.VoiceId
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The app's [PlayerHub]. Create it once, on the main thread, in `Application.onCreate` (so a service restarted by the
 * system finds its [PlaybackService.Dependencies]); [scope] is an app-lifetime scope on the main dispatcher.
 *
 * It connects a [MediaController] to [PlaybackService], which starts the service and keeps it bound, then talks to the
 * service's engine directly (same process; see [PlaybackService]). [ui] and [queue] follow whichever engine is
 * running, so they carry on across a service being destroyed and recreated; while none is, [ui] is idle and calls
 * wait, in order, for the next one (the hub reconnects, which recreates the service and restores its state).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaControllerPlayerHub(
    context: Context,
    media: MediaLibrary,
    playback: PlaybackDao,
    private val scope: CoroutineScope,
    /** A cover path as the session items' artwork URI; see [PlaybackService.Dependencies]. */
    artwork: (String) -> Uri = { Uri.fromFile(File(it)) },
    /**
     * Android Auto's browse tree, made on first use: the app's tree plays through this hub, so it can't exist before
     * it. Null serves an empty root.
     */
    browse: (() -> MediaBrowseTree)? = null,
    /** The voice (Plan V1): what can speak, how a saved source is reopened after process death, and the settings. */
    voices: InstalledVoices? = null,
    readSources: (suspend (key: String, title: String) -> ReadSource?)? = null,
    preferredVoice: () -> VoiceId? = { null },
    voiceSpeed: () -> Float = { 1f },
) : PlayerHub {
    private val app = context.applicationContext
    private var controller: ListenableFuture<MediaController>? = null
    private val pending = ArrayDeque<(PlayerEngine) -> Unit>()

    init {
        PlaybackService.install(
            PlaybackService.Dependencies(media, playback, artwork, browse?.let(::LazyBrowseTree), voices, readSources, preferredVoice, voiceSpeed),
        )
        connect()
        scope.launch {
            PlaybackService.engine.filterNotNull().collect { engine ->
                while (pending.isNotEmpty()) pending.removeFirst()(engine)
            }
        }
    }

    override val ui: StateFlow<PlayerUi> = PlaybackService.engine
        .flatMapLatest { it?.ui ?: flowOf(PlayerUi.IDLE) }
        .stateIn(scope, SharingStarted.Eagerly, PlayerUi.IDLE)

    override val queue: StateFlow<List<Track>> = PlaybackService.engine
        .flatMapLatest { it?.queue ?: flowOf(emptyList()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val voiceProgress: StateFlow<VoiceProgress?> = PlaybackService.engine
        .flatMapLatest { it?.voiceProgress ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    // Read from the db, not the engine, so the saved modes show even while the service is down.
    override val saved: StateFlow<SavedModes> =
        SavedModesReader(media, playback, app.getSharedPreferences(PlayerSettingsKeys.PREFS, Context.MODE_PRIVATE))
            .flow()
            .stateIn(scope, SharingStarted.Eagerly, SavedModes.UNKNOWN)

    private fun connect() {
        if (controller != null) return
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    this@MediaControllerPlayerHub.controller = null
                    // The service went away (killed or stopped): bring it back, which restores it paused.
                    scope.launch { delay(RECONNECT_DELAY_MS); connect() }
                }
            })
            .buildAsync()
        controller = future
        future.addListener({
            if (runCatching { future.get() }.isFailure) {
                Log.w(TAG, "Couldn't connect to the playback service")
                if (controller === future) controller = null
            }
        }, app.mainExecutor)
    }

    /** Runs [block] on the running engine, or queues it (in order) until one is up. */
    private fun send(block: (PlayerEngine) -> Unit) {
        val engine = PlaybackService.engine.value
        if (engine != null && pending.isEmpty()) {
            block(engine)
        } else {
            pending += block
            connect()
        }
    }

    override fun playTracks(trackIds: List<String>, startIndex: Int, shuffle: Boolean, source: PlaySource) =
        send { it.playTracks(trackIds, startIndex, shuffle, source) }
    override fun playAudiobook(id: String, at: AudioBookmark?) = send { it.playAudiobook(id, at) }
    override fun readAloud(source: ReadSource, from: ReadAddress?, voice: VoiceId?) = send { it.readAloud(source, from, voice) }
    override fun stopVoice() { PlaybackService.engine.value?.stopVoice() }
    override fun nextParagraph() = send { it.nextParagraph() }
    override fun previousParagraph() = send { it.previousParagraph() }
    override fun nextSentence() = send { it.nextSentence() }
    override fun previousSentence() = send { it.previousSentence() }
    override fun jumpTo(address: ReadAddress) = send { it.jumpTo(address) }
    override fun setMixLevel(level: Float) = send { it.setMixLevel(level) }
    override fun playPauseMusic() = send { it.playPauseMusic() }
    override fun nextMusic() = send { it.nextMusic() }
    override fun activate(mode: PlayerMode) = send { it.activate(mode) }
    override fun setFinished(audiobookId: String, finished: Boolean) = send { it.setFinished(audiobookId, finished) }
    // No service running, nothing loaded: don't start one just to empty it.
    override fun unload(audiobookId: String, trackIds: Set<String>) { PlaybackService.engine.value?.unload(audiobookId, trackIds) }
    override fun playPause() = send { it.playPause() }
    override fun next() = send { it.next() }
    override fun previous() = send { it.previous() }
    override fun skipBack() = send { it.skipBack() }
    override fun skipForward() = send { it.skipForward() }
    override fun seekTo(positionMs: Long) = send { it.seekTo(positionMs) }
    override fun seekToChapter(ordinal: Int) = send { it.seekToChapter(ordinal) }
    override fun setShuffle(on: Boolean) = send { it.setShuffle(on) }
    override fun cycleRepeat() = send { it.cycleRepeat() }
    override fun moveInQueue(from: Int, to: Int) = send { it.moveInQueue(from, to) }
    override fun removeFromQueue(i: Int) = send { it.removeFromQueue(i) }
    override fun playQueueItem(position: Int) = send { it.playQueueItem(position) }
    override fun playNext(trackIds: List<String>) = send { it.playNext(trackIds) }
    override fun addToQueue(trackIds: List<String>) = send { it.addToQueue(trackIds) }
    override fun setSpeed(speed: Float) = send { it.setSpeed(speed) }
    override fun setSkipSilence(on: Boolean) = send { it.setSkipSilence(on) }
    override fun setVolumeBoost(mb: Int) = send { it.setVolumeBoost(mb) }
    override fun startSleepTimer(setting: SleepSetting) = send { it.startSleepTimer(setting) }
    override fun cancelSleepTimer() = send { it.cancelSleepTimer() }

    override suspend fun addBookmark(title: String): Long {
        val result = CompletableDeferred<Long>()
        send { engine -> scope.launch { result.complete(engine.addBookmark(title)) } }
        return result.await()
    }

    private companion object {
        const val TAG = "PlayerHub"
        const val RECONNECT_DELAY_MS = 500L
    }
}
