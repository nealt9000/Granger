package app.granger.core.player

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
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

    @After fun tearDown() {
        scope.cancel()
        sessions.forEach { it.release() }
        rig.close()
    }

    private fun session(engine: PlayerEngine): MediaSession =
        MediaSession.Builder(rig.context, SessionPlayer(engine.player, engine)).setId("cb-${sessions.size}").build().also { sessions += it }

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
