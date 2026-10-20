package app.granger.core.player

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.granger.core.library.MediaLibrary
import app.granger.core.library.PlaybackDao
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The playback service: owns the one [ExoPlayer] and its [PlayerEngine], and publishes them as a
 * [MediaLibrarySession] (notification, lock screen, Bluetooth and headset buttons; Android Auto browses it in M3,
 * so the browse tree is empty for now). A play press after the process died resumes through [LibraryCallback].
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
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
        session = MediaLibrarySession.Builder(this, SessionPlayer(player, e), LibraryCallback(e, callbackScope))
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
        callbackScope.cancel()
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

}

/**
 * The session's callback. The browse tree is still an empty root (Task 8 fills it for Android Auto).
 *
 * Playback resumption: a play press (headset, Bluetooth, Auto) that finds the player empty, typically after the
 * process died, asks [onPlaybackResumption]; the engine restores the last active player paused from its saved state
 * and answers with its items and position. The session then hands those items back to [SessionPlayer.setMediaItems]
 * (a no-op: they're already loaded) and presses play, which goes through the engine. Nothing saved: a failed future.
 */
internal class LibraryCallback(private val engine: PlayerEngine, private val scope: CoroutineScope) : MediaLibrarySession.Callback {
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

    override fun onPlaybackResumption(
        mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch {
            try {
                val resumed = engine.resumption()
                if (resumed != null) future.set(resumed) else future.setException(UnsupportedOperationException("Nothing to resume"))
            } catch (e: CancellationException) {
                future.cancel(false)
                throw e
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        return future
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = onPlaybackResumption(mediaSession, controller, true)
}

/**
 * The session's view of the player: transport commands from outside controllers go through the engine's rules, and
 * what it reports is the engine's truth.
 *
 * - Next and previous are offered whenever a book with more than one chapter is loaded (a one-file book has no next
 *   item for ExoPlayer); they step chapters. The change is announced to the listeners the session gave, and anything
 *   ExoPlayer announces reaches them as these commands, not its own.
 * - A seek to another playlist item (Auto's queue) plays it through the engine, as a queue row tap does.
 * - Items handed back that are already the engine's (a resumption's) aren't loaded again: the engine is the only thing
 *   that loads the player.
 */
internal class SessionPlayer(player: ExoPlayer, private val engine: PlayerEngine) : ForwardingPlayer(player) {
    private val listeners = LinkedHashMap<Player.Listener, Player.Listener>()   // as given → as registered

    init {
        engine.onChapterStepsChanged = { announceCommands() }
    }

    override fun play() = engine.play()
    override fun pause() = engine.pause()
    override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) engine.play() else engine.pause()
    override fun seekToNext() = engine.next()
    override fun seekToNextMediaItem() = engine.next()
    override fun seekToPrevious() = engine.previous()
    override fun seekToPreviousMediaItem() = engine.previous()
    override fun seekBack() = engine.skipBack()
    override fun seekForward() = engine.skipForward()

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (isMusic() && mediaItemIndex != currentMediaItemIndex) {
            engine.playMediaItem(mediaItemIndex, if (positionMs == C.TIME_UNSET) 0L else positionMs)
        } else {
            super.seekTo(mediaItemIndex, positionMs)
        }
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (isMusic()) engine.playMediaItem(mediaItemIndex, 0L) else super.seekToDefaultPosition(mediaItemIndex)
    }

    private fun isMusic() = engine.ui.value.now is NowPlaying.Music

    override fun getAvailableCommands(): Player.Commands = withChapterSteps(super.getAvailableCommands())

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    private fun withChapterSteps(commands: Player.Commands): Player.Commands =
        if (engine.chapterSteps) commands.buildUpon().addAll(*CHAPTER_STEPS).build() else commands

    override fun addListener(listener: Player.Listener) {
        val registered = CommandsListener(listener)
        listeners[listener] = registered
        super.addListener(registered)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let { super.removeListener(it) }
    }

    private fun announceCommands() {
        val commands = availableCommands
        listeners.values.toList().forEach { it.onAvailableCommandsChanged(commands) }
    }

    /**
     * Passes every event on, except that ExoPlayer's available commands are replaced by the session's. Each method is
     * written out: Kotlin's `by` doesn't delegate a Java interface's default methods, and all of Player.Listener's are.
     */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    private inner class CommandsListener(private val l: Player.Listener) : Player.Listener {
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) =
            l.onAvailableCommandsChanged(withChapterSteps(availableCommands))
        override fun onEvents(player: Player, events: Player.Events) = l.onEvents(player, events)
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = l.onTimelineChanged(timeline, reason)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = l.onMediaItemTransition(mediaItem, reason)
        override fun onTracksChanged(tracks: Tracks) = l.onTracksChanged(tracks)
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = l.onMediaMetadataChanged(mediaMetadata)
        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = l.onPlaylistMetadataChanged(mediaMetadata)
        override fun onIsLoadingChanged(isLoading: Boolean) = l.onIsLoadingChanged(isLoading)
        override fun onLoadingChanged(isLoading: Boolean) = l.onLoadingChanged(isLoading)
        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = l.onTrackSelectionParametersChanged(parameters)
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = l.onPlayerStateChanged(playWhenReady, playbackState)
        override fun onPlaybackStateChanged(playbackState: Int) = l.onPlaybackStateChanged(playbackState)
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = l.onPlayWhenReadyChanged(playWhenReady, reason)
        override fun onPlaybackSuppressionReasonChanged(reason: Int) = l.onPlaybackSuppressionReasonChanged(reason)
        override fun onIsPlayingChanged(isPlaying: Boolean) = l.onIsPlayingChanged(isPlaying)
        override fun onRepeatModeChanged(repeatMode: Int) = l.onRepeatModeChanged(repeatMode)
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = l.onShuffleModeEnabledChanged(shuffleModeEnabled)
        override fun onPlayerError(error: PlaybackException) = l.onPlayerError(error)
        override fun onPlayerErrorChanged(error: PlaybackException?) = l.onPlayerErrorChanged(error)
        override fun onPositionDiscontinuity(reason: Int) = l.onPositionDiscontinuity(reason)
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
            l.onPositionDiscontinuity(oldPosition, newPosition, reason)
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = l.onPlaybackParametersChanged(playbackParameters)
        override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = l.onSeekBackIncrementChanged(seekBackIncrementMs)
        override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) = l.onSeekForwardIncrementChanged(seekForwardIncrementMs)
        override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) =
            l.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
        override fun onAudioSessionIdChanged(audioSessionId: Int) = l.onAudioSessionIdChanged(audioSessionId)
        override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = l.onAudioAttributesChanged(audioAttributes)
        override fun onVolumeChanged(volume: Float) = l.onVolumeChanged(volume)
        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = l.onSkipSilenceEnabledChanged(skipSilenceEnabled)
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = l.onDeviceInfoChanged(deviceInfo)
        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = l.onDeviceVolumeChanged(volume, muted)
        override fun onVideoSizeChanged(videoSize: VideoSize) = l.onVideoSizeChanged(videoSize)
        override fun onSurfaceSizeChanged(width: Int, height: Int) = l.onSurfaceSizeChanged(width, height)
        override fun onRenderedFirstFrame() = l.onRenderedFirstFrame()
        override fun onCues(cues: List<Cue>) = l.onCues(cues)
        override fun onCues(cueGroup: CueGroup) = l.onCues(cueGroup)
        override fun onMetadata(metadata: Metadata) = l.onMetadata(metadata)
    }

    // The engine's own items handed back (a resumption's): already loaded, so nothing to do.
    override fun setMediaItems(mediaItems: List<MediaItem>) { if (!isEngines(mediaItems)) super.setMediaItems(mediaItems) }
    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        if (!isEngines(mediaItems)) super.setMediaItems(mediaItems, resetPosition)
    }
    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        if (!isEngines(mediaItems)) super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }
    override fun setMediaItem(mediaItem: MediaItem) { if (!isEngines(listOf(mediaItem))) super.setMediaItem(mediaItem) }
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        if (!isEngines(listOf(mediaItem))) super.setMediaItem(mediaItem, resetPosition)
    }
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        if (!isEngines(listOf(mediaItem))) super.setMediaItem(mediaItem, startPositionMs)
    }

    private fun isEngines(items: List<MediaItem>): Boolean =
        items.isNotEmpty() && items.size == mediaItemCount && items.indices.all { items[it].mediaId == getMediaItemAt(it).mediaId }

    private companion object {
        val CHAPTER_STEPS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
    }
}
