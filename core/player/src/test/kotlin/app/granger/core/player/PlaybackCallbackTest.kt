package app.granger.core.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The session callback, called as Media3 calls it (a test-only [MediaSession.ControllerInfo]). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackCallbackTest {
    private val rig = PlayerTestRig()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessions = mutableListOf<MediaSession>()
    private val controller = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        "com.example.headset", 0, 0, 0, 0, false, Bundle.EMPTY, false,
    )

    private val controllers = mutableListOf<MediaController>()

    @After fun tearDown() {
        scope.cancel()
        controllers.forEach { it.release() }
        sessions.forEach { it.release() }
        rig.close()
    }

    private fun session(engine: PlayerEngine): MediaSession =
        MediaSession.Builder(rig.context, SessionPlayer(engine.player, engine)).setId("cb-${sessions.size}").build().also { sessions += it }

    private fun librarySession(engine: PlayerEngine, callback: LibraryCallback): MediaLibrarySession =
        MediaLibrarySession.Builder(rig.context, SessionPlayer(engine.player, engine), callback)
            .setId("lib-${sessions.size}").build().also { sessions += it }

    /** A controller connected to [session], as Android Auto would be. */
    private fun controller(session: MediaSession): MediaController {
        val future = MediaController.Builder(rig.context, session.token).buildAsync()
        rig.idle()
        return future.get().also { controllers += it }
    }

    /** Plays "album:songs" (the rig's five songs from the second); records every id it was asked for. */
    private class FakeTree(private val engine: PlayerEngine, private val songs: List<String>) : MediaBrowseTree {
        val played = mutableListOf<Pair<String, String?>>()
        val folder: MediaItem = MediaItem.Builder().setMediaId("music")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Music").setIsBrowsable(true).setIsPlayable(false).build()).build()
        override fun root(): MediaItem = MediaItem.Builder().setMediaId("root")
            .setMediaMetadata(MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).build()).build()
        override suspend fun children(parentId: String, page: Int, pageSize: Int) = if (parentId == "root") listOf(folder) else null
        override suspend fun item(id: String) = if (id == "music") folder else null
        override suspend fun search(query: String) = if (query == "music") listOf(folder) else emptyList()
        override suspend fun play(id: String, searchQuery: String?): Boolean {
            played += id to searchQuery
            if (id != "album:songs") return false
            engine.playTracks(songs, 1, shuffle = false, source = PlaySource("album:songs", "Songs"))
            return true
        }
    }

    /** Counts the times the player's items were replaced (a load moves to an item of the new playlist). */
    private fun countLoads(engine: PlayerEngine): () -> Int {
        var loads = 0
        engine.player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) loads++
            }
        })
        return { loads }
    }

    @Test fun setMediaItemsFromAutoLoadsOnceThroughTheEngine() {
        val songs = rig.songs()
        val engine = rig.engine()
        val tree = FakeTree(engine, songs)
        val session = librarySession(engine, LibraryCallback(engine, scope, tree))
        val loads = countLoads(engine)
        val auto = controller(session)

        // Auto sends the item it browsed: an id and its own artwork, no URI of ours (here a URI of its own).
        auto.setMediaItem(MediaItem.Builder().setMediaId("album:songs").setUri("https://example.com/not-ours.mp3")
            .setMediaMetadata(MediaMetadata.Builder().setArtworkUri(android.net.Uri.parse("https://example.com/a.jpg")).build())
            .build())
        auto.prepare()
        auto.play()
        rig.idle()
        run(engine.player).untilState(Player.STATE_READY)
        rig.idle()

        assertThat(tree.played).containsExactly("album:songs" to null)
        assertThat(loads()).isEqualTo(1)                                       // the engine's setMediaItems, nothing after
        val player = engine.player
        assertThat((0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }).isEqualTo(songs)
        assertThat((0 until player.mediaItemCount).map { player.getMediaItemAt(it).localConfiguration!!.uri.scheme }.toSet())
            .containsExactly("fake")
        assertThat(player.currentMediaItemIndex).isEqualTo(1)
        assertThat(player.playWhenReady).isTrue()
        assertThat((engine.ui.value.now as NowPlaying.Music).track.id).isEqualTo(songs[1])

        // The session's own hand-back of the engine's items (what Media3 does after onSetMediaItems) changes nothing.
        val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val sessionPlayer = session.player
        sessionPlayer.setMediaItems(items.map { MediaItem.Builder().setMediaId(it.mediaId).build() }, 1, 0L)
        sessionPlayer.addMediaItems(items)
        rig.idle()
        assertThat(loads()).isEqualTo(1)
        assertThat(player.mediaItemCount).isEqualTo(songs.size)
        // A saved state of the album is what the engine wrote.
        assertThat(runBlocking { rig.playback.musicState() }!!.sourceKey).isEqualTo("album:songs")
    }

    @Test fun unknownSetMediaItemsFails() {
        val engine = rig.engine()
        val tree = FakeTree(engine, rig.songs())
        val callback = LibraryCallback(engine, scope, tree)
        val session = librarySession(engine, callback)
        val future = callback.onSetMediaItems(session, controller, mutableListOf(MediaItem.Builder().setMediaId("nope").build()), 0, 0L)
        rig.idle()
        assertThat(future.isDone).isTrue()
        assertThrows(ExecutionException::class.java) { future.get() }
        assertThat(engine.player.mediaItemCount).isEqualTo(0)

        // A voice "play …" reaches the tree as an empty id with the query.
        callback.onSetMediaItems(session, controller, mutableListOf(MediaItem.Builder().setMediaId("")
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("holst").build()).build()), 0, 0L)
        rig.idle()
        assertThat(tree.played.last()).isEqualTo("" to "holst")
    }

    @Test fun childrenOfAnUnknownParentIsBadValue() {
        val engine = rig.engine()
        val callback = LibraryCallback(engine, scope, FakeTree(engine, emptyList()))
        val session = librarySession(engine, callback)
        val unknown = callback.onGetChildren(session, controller, "nope", 0, 20, null)
        val root = callback.onGetChildren(session, controller, "root", 0, 20, null)
        val item = callback.onGetItem(session, controller, "nope")
        rig.idle()
        assertThat(unknown.get().resultCode).isEqualTo(SessionError.ERROR_BAD_VALUE)
        assertThat(item.get().resultCode).isEqualTo(SessionError.ERROR_BAD_VALUE)
        assertThat(root.get().resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
        assertThat(root.get().value!!.map { it.mediaId }).containsExactly("music")
        assertThat(callback.onGetLibraryRoot(session, controller, null).get().value!!.mediaId).isEqualTo("root")
    }

    @Test fun searchCountsThenPages() {
        val engine = rig.engine()
        val callback = LibraryCallback(engine, scope, FakeTree(engine, emptyList()))
        val session = librarySession(engine, callback)
        val search = callback.onSearch(session, controller, "music", null)
        val page = callback.onGetSearchResult(session, controller, "music", 0, 10, null)
        val past = callback.onGetSearchResult(session, controller, "music", 1, 10, null)
        rig.idle()
        assertThat(search.get().resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
        assertThat(page.get().value!!.map { it.mediaId }).containsExactly("music")
        assertThat(past.get().value!!).isEmpty()
        assertThat(LibraryCallback.pageOf((1..7).toList(), 1, 3)).containsExactly(4, 5, 6).inOrder()
    }

    @Test fun playbackResumptionReturnsTheEngineItems() {
        val book = rig.fakeBook("killed", 60_000, 60_000)
        val first = rig.engine()
        first.playAudiobook(book.id)
        rig.idle()
        run(first.player).untilPosition(1, 20_000)
        first.playPause()
        rig.idle()
        first.release(save = false)
        val saved = runBlocking { rig.playback.audiobookState(book.id) }!!

        val engine = rig.engine()
        val callback = LibraryCallback(engine, scope)
        val session = session(engine)
        val future = callback.onPlaybackResumption(session, controller, true)
        rig.idle()
        assertThat(future.isDone).isTrue()
        val r = future.get()
        assertThat(r.mediaItems.map { it.mediaId }).containsExactly("track:phone:killed-0", "track:phone:killed-1").inOrder()
        assertThat(r.startIndex).isEqualTo(1)
        assertThat(r.startPositionMs).isEqualTo(saved.positionMs)
        assertThat(engine.player.playWhenReady).isFalse()            // restored paused; the session plays it if asked
        assertThat(runBlocking { rig.playback.audiobookState(book.id) }).isEqualTo(saved)

        // The two-argument overload is the same call; the book is loaded now, so it answers from the player.
        run(engine.player).untilState(Player.STATE_READY)
        val again = callback.onPlaybackResumption(session, controller)
        rig.idle()
        assertThat(again.get().startPositionMs).isEqualTo(saved.positionMs)
    }

    @Test fun playbackResumptionWithNothingSavedFails() {
        val engine = rig.engine()
        val future = LibraryCallback(engine, scope).onPlaybackResumption(session(engine), controller, false)
        rig.idle()
        assertThat(future.isDone).isTrue()
        assertThrows(ExecutionException::class.java) { future.get() }
    }
}
