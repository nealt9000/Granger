package app.granger.music

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.granger.FakeShelves
import app.granger.core.library.AlbumSummary
import app.granger.core.library.Albums
import app.granger.core.library.Track
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerUi
import app.granger.player.FakePlayerHub
import app.granger.player.PlayerTags
import app.granger.player.QueueSheet
import app.granger.player.voiceUi
import app.granger.theme.GrangerTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Albums show what's playing (Plan U2 Decision 8, W10): the current song is marked with the playing bars and follows
 * Next from anywhere; the album's Play is Stop while its own queue plays, and Stop stops the player (not a pause).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp")
class AlbumPlayingTest {
    @get:Rule val compose = createComposeRule()

    private val songs = listOf(
        song("a1", title = "First", album = "Blue", trackNo = 1),
        song("a2", title = "Second", album = "Blue", trackNo = 2),
        song("a3", title = "Third", album = "Blue", trackNo = 3),
    )
    private val album: AlbumSummary = Albums.summaries(songs).single()
    private val albumSource = MusicSources.album(album).key
    private val hub = FakePlayerHub()

    private fun show() {
        val vm = MusicViewModel(null, FakeMediaLibrary(songs), FakeShelves(), FakePlaylists(FakeMediaLibrary(songs)), hub, compute = Dispatchers.Main)
        val nav = MusicNav({}, {}, {}, {}, {}, {})
        compose.setContent { GrangerTheme { AlbumScreen(vm, album.key, nav) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("First")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun playing(t: Track, heard: Boolean = true, source: String = albumSource, index: Int = 0) {
        hub.ui.value = PlayerUi(NowPlaying.Music(t, index, songs.size, false, 0, source), heard, 0L, t.durationMs, null)
        compose.waitForIdle()
    }

    private val mark = hasContentDescription("Playing") or hasContentDescription("Paused")

    /** The songs whose (merged) row carries the playing bars. */
    private fun marked(): List<String> =
        songs.map { it.title }.filter { compose.onAllNodes(hasText(it) and mark).fetchSemanticsNodes().isNotEmpty() }

    private fun heardCount(description: String) = compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().size

    @Test fun playBecomesStopWhileTheAlbumPlaysAndStopStops() {
        show()
        assertThat(marked()).isEmpty()
        compose.onNodeWithText("Play").performClick()
        assertThat(hub.calls).contains("playTracks")
        assertThat(hub.plays.last().source.key).isEqualTo(albumSource)

        playing(songs[0])
        compose.onNodeWithText("Stop").assertExists()
        compose.onAllNodes(hasText("Play")).assertCountEquals(0)
        assertThat(marked()).containsExactly("First")
        assertThat(heardCount("Playing")).isEqualTo(1)

        compose.onNodeWithText("Stop").performClick()
        assertThat(hub.calls.last()).isEqualTo("stop")
        assertThat(hub.calls).doesNotContain("playPause")
        hub.ui.value = PlayerUi.IDLE
        compose.waitForIdle()
        compose.onNodeWithText("Play").assertExists()
        assertThat(marked()).isEmpty()
    }

    @Test fun theMarkFollowsNextFromTheMiniPlayer() {
        show()
        playing(songs[0])
        assertThat(marked()).containsExactly("First")
        // Next from the mini player: only the hub's current track changes.
        playing(songs[1], index = 1)
        assertThat(marked()).containsExactly("Second")
        playing(songs[2], index = 2)
        assertThat(marked()).containsExactly("Third")
    }

    @Test fun pausedTheMarkStaysStillAndPlayIsBack() {
        show()
        playing(songs[1], heard = false, index = 1)
        assertThat(marked()).containsExactly("Second")
        assertThat(heardCount("Paused")).isEqualTo(1)
        compose.onNodeWithText("Play").assertExists()
        compose.onAllNodes(hasText("Stop")).assertCountEquals(0)
    }

    @Test fun aSongPlayedFromElsewhereIsMarkedButTheAlbumKeepsPlay() {
        show()
        playing(songs[2], source = "playlist:7", index = 0)
        assertThat(marked()).containsExactly("Third")
        compose.onNodeWithText("Play").assertExists()
        // Under the voice: marked, but Stop would end the reading too, so Play stays.
        val voice = voiceUi().now as NowPlaying.Voice
        val under = NowPlaying.Music(songs[0], 0, songs.size, false, 0, albumSource)
        hub.ui.value = PlayerUi(voice.copy(music = under, musicPlaying = true), true, 0L, 0L, null)
        compose.waitForIdle()
        assertThat(marked()).containsExactly("First")
        compose.onNodeWithText("Play").assertExists()
    }

    @Test fun theQueueMarksItsCurrentSong() {
        compose.setContent {
            GrangerTheme { QueueSheet(songs, current = 1, onMove = { _, _ -> }, onRemove = {}, onDismiss = {}, heard = true) }
        }
        compose.onAllNodesWithTag(PlayerTags.PLAYING, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodes(hasTestTag(PlayerTags.QUEUE_ROW) and mark).assertCountEquals(1)
        compose.onAllNodes(hasTestTag(PlayerTags.QUEUE_ROW) and hasText("Second") and hasContentDescription("Playing")).assertCountEquals(1)
    }
}
