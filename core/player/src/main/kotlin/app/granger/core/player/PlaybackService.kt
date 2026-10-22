package app.granger.core.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
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
import androidx.media3.session.SessionError
import app.granger.core.library.MediaLibrary
import app.granger.core.library.PlaybackDao
import app.granger.core.speech.InstalledVoices
import app.granger.core.speech.ReadSource
import app.granger.core.speech.VoiceId
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The playback service: owns the one [ExoPlayer] and its [PlayerEngine], and publishes them as a
 * [MediaLibrarySession] (notification, lock screen, Bluetooth and headset buttons, and Android Auto, which browses
 * the app's [MediaBrowseTree]). A play press after the process died resumes through [LibraryCallback].
 *
 * Talking to the app: the app is in the same process, so [MediaControllerPlayerHub] binds through a MediaController
 * (which starts the service and keeps it alive) and then calls the running engine directly through [engine]. Outside
 * controllers (notification, Bluetooth, lock screen) reach the engine through [SessionPlayer], so their play, next
 * and skip go through the same rules (focus, auto-rewind, chapters).
 *
 * On creation (including a restart by the system) the engine restores the last active player, paused.
 */
class PlaybackService : MediaLibraryService() {
    /**
     * What the service needs from the app; installed by the hub before it connects. [artwork] turns a cover path into
     * the URI the session's items carry (the app's artwork provider, which Android Auto and the launcher can read).
     */
    data class Dependencies(
        val media: MediaLibrary,
        val playback: PlaybackDao,
        val artwork: (String) -> Uri = { Uri.fromFile(File(it)) },
        /** What Android Auto browses and plays; null serves an empty root. */
        val browse: MediaBrowseTree? = null,
        /** The voice (Plan V1): the installed voices, and how a saved source is reopened for a restore; null: no voice. */
        val voices: InstalledVoices? = null,
        val readSources: (suspend (String) -> ReadSource?)? = null,
        val preferredVoice: () -> VoiceId? = { null },
        val voiceSpeed: () -> Float = { 1f },
    )

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
            artwork = deps.artwork,
            voices = deps.voices,
            readSources = deps.readSources,
            preferredVoice = deps.preferredVoice,
            defaultVoiceSpeed = deps.voiceSpeed,
        )
        engine = e
        session = MediaLibrarySession.Builder(this, SessionPlayer(player, e), LibraryCallback(e, callbackScope, deps.browse))
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
 * The session's callback: the browse tree (Android Auto), search, playing what a controller picks, and resumption.
 *
 * Browsing is [browse]'s; with none installed the root is empty. Lists are paged by the tree; search results are paged
 * here (the media index is in memory, so a page is a fresh search).
 *
 * A controller's items are routed by media id only, so a controller's URIs and artwork are never loaded:
 * - [onSetMediaItems]: the tree starts what the id names through the hub, then [PlayerEngine.loadedItems] answers, in
 *   turn after that call, with the engine's own items, index and position. Before answering, the callback tells the
 *   [SessionPlayer] to expect the session's hand-back of those items, which it drops (even if an engine command has
 *   replaced the playlist meanwhile), so the engine loads the player once. Nothing plays, or the engine doesn't
 *   answer within [PLAY_TIMEOUT_MS]: a failed future.
 * - [onAddMediaItems]: each id is appended through the tree ([MediaBrowseTree.add]; an audiobook plays instead) and
 *   the answer is no items, so the session adds nothing to the player itself. Nothing added: a failed future.
 *
 * Playback resumption: a play press (headset, Bluetooth, Auto) that finds the player empty, typically after the
 * process died, asks [onPlaybackResumption]; the engine restores the last active player paused from its saved state
 * and answers with its items and position. The session then hands those items back to [SessionPlayer.setMediaItems]
 * (dropped, as an expected hand-back: they're already loaded) and presses play, which goes through the engine. Nothing saved: a failed future.
 */
internal class LibraryCallback(
    private val engine: PlayerEngine,
    private val scope: CoroutineScope,
    private val browse: MediaBrowseTree? = null,
) : MediaLibrarySession.Callback {
    private val emptyRoot = MediaItem.Builder()
        .setMediaId("root")
        .setMediaMetadata(MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).build())
        .build()

    override fun onGetLibraryRoot(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(browse?.root() ?: emptyRoot, params))

    override fun onGetChildren(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int,
        pageSize: Int, params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val tree = browse ?: return Futures.immediateFuture(
            if (parentId == emptyRoot.mediaId) LibraryResult.ofItemList(ImmutableList.of(), params)
            else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE),
        )
        return future {
            tree.children(parentId, page, pageSize)?.let { LibraryResult.ofItemList(it, params) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }
    }

    override fun onGetItem(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val tree = browse ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        return future { tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) }
    }

    override fun onSearch(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        val tree = browse ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED))
        return future {
            val count = tree.search(query).size
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid(params)
        }
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val tree = browse ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED))
        return future { LibraryResult.ofItemList(pageOf(tree.search(query), page, pageSize), params) }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        startIndex: Int, startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        future { playRequested(mediaItems, startIndex).also { expectHandBack(mediaSession) } }

    override fun onAddMediaItems(
        mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = future {
        val tree = browse ?: throw UnsupportedOperationException("No browse tree")
        // Each by id, in order: songs are appended through the hub (an audiobook or recent plays instead).
        var any = false
        for (item in mediaItems) if (tree.add(item.mediaId)) any = true
        if (!any) throw IllegalArgumentException("Nothing to add for ${mediaItems.map { it.mediaId }}")
        mutableListOf()                       // nothing for the session to add to the player itself
    }

    /** Plays the requested item through the tree and answers with what the engine loaded; throws if nothing played. */
    private suspend fun playRequested(items: List<MediaItem>, startIndex: Int): MediaSession.MediaItemsWithStartPosition {
        val tree = browse ?: throw UnsupportedOperationException("No browse tree")
        val item = items.getOrNull(startIndex) ?: items.firstOrNull() ?: throw IllegalArgumentException("No items")
        if (!tree.play(item.mediaId, item.requestMetadata.searchQuery)) throw IllegalArgumentException("Nothing plays for ${item.mediaId}")
        return withTimeoutOrNull(PLAY_TIMEOUT_MS) { engine.loadedItems() }
            ?: throw IllegalStateException("The engine loaded nothing for ${item.mediaId}")
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        future { (engine.resumption() ?: throw UnsupportedOperationException("Nothing to resume")).also { expectHandBack(mediaSession) } }

    override fun onPlaybackResumption(
        mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = onPlaybackResumption(mediaSession, controller, true)

    /** The session hands the answer's items to its player next; the player drops them (see [SessionPlayer.expectHandBack]). */
    private fun expectHandBack(session: MediaSession) { (session.player as? SessionPlayer)?.expectHandBack() }

    /** [block] on [scope] as a future; an exception fails it, a cancellation cancels it. */
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        scope.launch {
            try {
                future.set(block())
            } catch (e: CancellationException) {
                future.cancel(false)
                throw e
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        return future
    }

    companion object {
        const val PLAY_TIMEOUT_MS = 5_000L

        /** [page] of [items] at [pageSize] a page; everything for a page size below 1. */
        fun <T> pageOf(items: List<T>, page: Int, pageSize: Int): List<T> =
            if (pageSize < 1) items else {
                val from = page.coerceAtLeast(0).toLong() * pageSize
                if (from >= items.size) emptyList() else items.drop(from.toInt()).take(pageSize)
            }
    }
}

/**
 * The session's view of the player: transport commands from outside controllers go through the engine's rules, and
 * what it reports is the engine's truth.
 *
 * - Next and previous are offered whenever a book with more than one chapter is loaded (a one-file book has no next
 *   item for ExoPlayer); they step chapters. The change is announced to the listeners the session gave, and anything
 *   ExoPlayer announces reaches them as these commands, not its own.
 * - A seek to another playlist item (Auto's queue) plays it through the engine, as a queue row tap does.
 * - The session's hand-back of [LibraryCallback]'s answers (a resumption's, a controller's request) is dropped
 *   ([expectHandBack]), and so are items that are already the engine's: the engine is the only thing that loads the
 *   player.
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
    private fun isVoice() = engine.ui.value.now is NowPlaying.Voice

    override fun getAvailableCommands(): Player.Commands = withChapterSteps(super.getAvailableCommands())

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    /** Chapter (or paragraph) steps when the engine offers them; no seeking within the voice's carrier item. */
    private fun withChapterSteps(commands: Player.Commands): Player.Commands {
        var c = commands
        if (engine.chapterSteps) c = c.buildUpon().addAll(*CHAPTER_STEPS).build()
        if (isVoice()) c = c.buildUpon().removeAll(*VOICE_NO_SEEK).build()
        return c
    }

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

    /**
     * One hand-back is coming: [LibraryCallback] has answered a controller's request (or a resumption) with the
     * engine's items, and the session passes them to [setMediaItems] next. That call is dropped whatever the playlist
     * is by then (an engine command may have replaced it meanwhile), so it never loads into ExoPlayer. One-shot: used
     * by the next set, and a newer answer just sets it again. Main thread, as the session's calls are.
     */
    fun expectHandBack() { handBackExpected = true }
    private var handBackExpected = false

    /** A hand-back to drop: the one expected, or the engine's own items, or nothing at all. */
    private fun dropped(items: List<MediaItem>): Boolean {
        if (handBackExpected) {
            handBackExpected = false
            return true
        }
        return items.isEmpty() || isEngines(items)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>) { if (!dropped(mediaItems)) super.setMediaItems(mediaItems) }
    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        if (!dropped(mediaItems)) super.setMediaItems(mediaItems, resetPosition)
    }
    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        if (!dropped(mediaItems)) super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }
    override fun setMediaItem(mediaItem: MediaItem) { if (!dropped(listOf(mediaItem))) super.setMediaItem(mediaItem) }
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        if (!dropped(listOf(mediaItem))) super.setMediaItem(mediaItem, resetPosition)
    }
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        if (!dropped(listOf(mediaItem))) super.setMediaItem(mediaItem, startPositionMs)
    }

    // A controller's add is appended by the engine ([LibraryCallback.onAddMediaItems] answers with no items): the
    // session's empty add, or the engine's own items, are nothing to do. The hand-back token is for sets only.
    override fun addMediaItems(mediaItems: List<MediaItem>) { if (!addDropped(mediaItems)) super.addMediaItems(mediaItems) }
    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
        if (!addDropped(mediaItems)) super.addMediaItems(index, mediaItems)
    }
    override fun addMediaItem(mediaItem: MediaItem) { if (!addDropped(listOf(mediaItem))) super.addMediaItem(mediaItem) }
    override fun addMediaItem(index: Int, mediaItem: MediaItem) {
        if (!addDropped(listOf(mediaItem))) super.addMediaItem(index, mediaItem)
    }

    private fun addDropped(items: List<MediaItem>) = items.isEmpty() || isEngines(items)

    private fun isEngines(items: List<MediaItem>): Boolean =
        items.isNotEmpty() && items.size == mediaItemCount && items.indices.all { items[it].mediaId == getMediaItemAt(it).mediaId }

    private companion object {
        val CHAPTER_STEPS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
        val VOICE_NO_SEEK = intArrayOf(
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
        )
    }
}
