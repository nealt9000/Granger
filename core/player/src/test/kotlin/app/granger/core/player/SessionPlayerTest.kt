package app.granger.core.player

import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What outside controllers (notification, lock screen, Bluetooth, Auto) see of the player, and where their commands go. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionPlayerTest {
    private val rig = PlayerTestRig()
    private val sessions = mutableListOf<MediaSession>()
    private val controllers = mutableListOf<MediaController>()

    @After fun tearDown() {
        controllers.forEach { it.release() }
        sessions.forEach { it.release() }
        rig.close()
    }

    /** A controller connected to the session, as the notification or Auto would be. */
    private fun controller(): MediaController {
        val future = MediaController.Builder(rig.context, sessions.last().token).buildAsync()
        rig.idle()
        return future.get().also { controllers += it }
    }

    private fun session(engine: PlayerEngine): SessionPlayer {
        val player = SessionPlayer(engine.player, engine)
        sessions += MediaSession.Builder(rig.context, player).setId("test-${sessions.size}").build()
        return player
    }

    @Test fun singleFileBookOffersNextAndPrevious() {
        val book = rig.chapterBook()
        val engine = rig.engine()
        val player = session(engine)
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isFalse()

        engine.playAudiobook(book.id)
        rig.idle()
        run(engine.player).untilState(Player.STATE_READY)
        assertThat(engine.player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isFalse()   // one file: ExoPlayer has no next
        listOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        ).forEach {
            assertThat(player.isCommandAvailable(it)).isTrue()
            assertThat(player.availableCommands.contains(it)).isTrue()
        }

        player.seekToNext()
        rig.idle()
        assertThat(engine.player.currentPosition).isEqualTo(1_000L)
        assertThat((engine.ui.value.now as NowPlaying.Book).chapter.title).isEqualTo("Two")
        assertThat(player.mediaMetadata.title.toString()).isEqualTo("Two")
    }

    @Test fun availableCommandsChangeIsAnnounced() {
        val book = rig.chapterBook()
        val engine = rig.engine()
        val player = session(engine)
        val announced = mutableListOf<Player.Commands>()
        val states = mutableListOf<Int>()
        var eventCount = 0
        player.addListener(object : Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) { announced += availableCommands }
            override fun onPlaybackStateChanged(playbackState: Int) { states += playbackState }
            override fun onEvents(player: Player, events: Player.Events) { eventCount++ }
        })

        engine.playAudiobook(book.id)
        rig.idle()
        run(engine.player).untilState(Player.STATE_READY)
        rig.idle()
        // Once the book is loaded, everything announced (ExoPlayer's own changes too) is the session's commands.
        val sinceBook = announced.dropWhile { !it.contains(Player.COMMAND_SEEK_TO_NEXT) }
        assertThat(sinceBook).isNotEmpty()
        assertThat(sinceBook.all { it.contains(Player.COMMAND_SEEK_TO_NEXT) }).isTrue()
        // Every other event still reaches the listener.
        assertThat(states).contains(Player.STATE_READY)
        assertThat(eventCount).isGreaterThan(0)

        engine.playTracks(rig.songs().take(1), 0, false, PlaySource("songs", "Songs"))
        rig.idle()
        assertThat(announced.last().contains(Player.COMMAND_SEEK_TO_NEXT)).isFalse()   // one song: nothing next
    }

    /** End to end: what a connected controller (the notification, Auto) sees and sends. */
    @Test fun aControllerSeesTheChapterAndCanStepIt() {
        val book = rig.chapterBook()
        val engine = rig.engine()
        session(engine)
        val controller = controller()
        assertThat(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isFalse()

        engine.playAudiobook(book.id)
        rig.idle()
        run(engine.player).untilState(Player.STATE_READY)
        engine.playPause()
        rig.idle()
        assertThat(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isTrue()
        assertThat(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)).isTrue()
        assertThat(controller.mediaMetadata.title.toString()).isEqualTo("One")
        assertThat(controller.mediaMetadata.artist.toString()).isEqualTo("Author")

        controller.seekToNext()
        rig.idle()
        assertThat(engine.player.currentPosition).isEqualTo(1_000L)
        assertThat(controller.mediaMetadata.title.toString()).isEqualTo("Two")
    }

    @Test fun seekToIndexRoutesToTheQueue() {
        val ids = rig.songs()
        val engine = rig.engine()
        val player = session(engine)
        engine.playTracks(ids, 0, true, PlaySource("songs", "Songs"))
        rig.idle()
        engine.playPause(); rig.idle()
        val queue = engine.queue.value.map { it.id }

        player.seekTo(3, 0L)
        rig.idle()
        assertThat(engine.player.currentMediaItemIndex).isEqualTo(3)
        assertThat(engine.player.playWhenReady).isTrue()
        assertThat((engine.ui.value.now as NowPlaying.Music).index).isEqualTo(queue.indexOf(ids[3]))

        player.seekToDefaultPosition(1)
        rig.idle()
        assertThat(engine.player.currentMediaItem!!.mediaId).isEqualTo(ids[1])
        assertThat((engine.ui.value.now as NowPlaying.Music).index).isEqualTo(queue.indexOf(ids[1]))
    }

    @Test fun theEnginesOwnItemsHandedBackAreNotReloaded() {
        val book = rig.chapterBook()
        val engine = rig.engine()
        val player = session(engine)
        engine.playAudiobook(book.id)
        rig.idle()
        run(engine.player).untilPosition(0, 1_500)
        engine.playPause(); rig.idle()
        val position = engine.player.currentPosition
        val items = (0 until engine.player.mediaItemCount).map { engine.player.getMediaItemAt(it) }

        player.setMediaItems(items, 0, 0L)              // as the session does with a resumption's items
        player.setMediaItems(items, true)
        rig.idle()
        assertThat(engine.player.currentPosition).isEqualTo(position)
        assertThat(engine.player.playbackState).isEqualTo(Player.STATE_READY)
    }
}
