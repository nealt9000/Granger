package app.granger.core.player

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import app.granger.core.library.MediaLibrary
import app.granger.core.library.PlaybackDao
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The playback service: owns the one [ExoPlayer] and its [PlayerEngine], and publishes them as a
 * [MediaLibrarySession] (notification, lock screen, Bluetooth and headset buttons; Android Auto browses it in M3,
 * so the browse tree is empty for now).
 *
 * Talking to the app: the app is in the same process, so [MediaControllerPlayerHub] binds through a MediaController
 * (which starts the service and keeps it alive) and then calls the running engine directly through [engine]. Outside
 * controllers (notification, Bluetooth, lock screen) reach the engine through [SessionPlayer], so their play, next
 * and skip go through the same rules (focus, auto-rewind, chapters).
 *
 * On creation (including a restart by the system) the engine restores the last active player, paused.
 */
class PlaybackService : MediaLibraryService() {
    /** What the service needs from the app; installed by the hub before it connects. */
    data class Dependencies(val media: MediaLibrary, val playback: PlaybackDao)

    companion object {
        private const val TAG = "PlaybackService"

        @Volatile private var dependencies: Dependencies? = null
        private val current = MutableStateFlow<PlayerEngine?>(null)

        /** The running service's engine, or null while the service isn't running. */
        val engine: StateFlow<PlayerEngine?> = current

        fun install(dependencies: Dependencies) { this.dependencies = dependencies }
    }

    private var engine: PlayerEngine? = null
    private var session: MediaLibrarySession? = null
    private var headset: HeadsetAutoplay? = null

    override fun onCreate() {
        super.onCreate()
        val deps = dependencies ?: run {
            // The app installs the dependencies in Application.onCreate (via the hub); without them there's no library.
            Log.w(TAG, "Started without dependencies; stopping")
            stopSelf()
            return
        }
        val replayGain = ReplayGainProcessor()
        val player = buildPlayer(this, replayGain)
        val e = PlayerEngine(
            player = player,
            media = deps.media,
            playback = deps.playback,
            prefs = getSharedPreferences(PlayerSettingsKeys.PREFS, MODE_PRIVATE),
            focus = FocusPolicy(SystemFocusRequester(this)),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            effects = EffectsSession(this),
            replayGain = replayGain,
        )
        engine = e
        session = MediaLibrarySession.Builder(this, SessionPlayer(player, e), LibraryCallback)
            .apply { sessionActivity()?.let(::setSessionActivity) }
            .build()
        headset = HeadsetAutoplay(this, e::onHeadsetConnected).also { it.start() }
        e.restore()
        current.value = e
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        engine?.saveNow()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        current.value = null
        headset?.stop()
        engine?.release()          // saves first, then releases the player
        session?.release()
        engine = null
        session = null
        super.onDestroy()
    }

    private fun sessionActivity(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    /** An empty browse tree under one root; M3 fills it for Android Auto. */
    private object LibraryCallback : MediaLibrarySession.Callback {
        private val root = MediaItem.Builder()
            .setMediaId("root")
            .setMediaMetadata(MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).build())
            .build()

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(root, params))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int,
            pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
    }
}

/** The session's view of the player: transport commands from outside controllers go through the engine's rules. */
internal class SessionPlayer(player: ExoPlayer, private val engine: PlayerEngine) : ForwardingPlayer(player) {
    override fun play() = engine.play()
    override fun pause() = engine.pause()
    override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) engine.play() else engine.pause()
    override fun seekToNext() = engine.next()
    override fun seekToNextMediaItem() = engine.next()
    override fun seekToPrevious() = engine.previous()
    override fun seekToPreviousMediaItem() = engine.previous()
    override fun seekBack() = engine.skipBack()
    override fun seekForward() = engine.skipForward()
}
