package app.granger.player

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerMode
import app.granger.core.player.SavedBook
import app.granger.core.player.SavedModes
import app.granger.core.player.SavedMusic
import app.granger.theme.GrangerTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Player's Music | Audiobooks tabs: the active mode live, the other its saved state, paused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp")
class PlayerTabsTest {
    @get:Rule val compose = createComposeRule()

    private val book = audiobook("b", title = "The Hobbit")
    private val chapters = listOf(chapter(book, 0, 60_000L, "An Unexpected Party"), chapter(book, 1, 120_000L, "Roast Mutton"))
    private val savedBook = SavedBook(book, chapters, chapters[1], inChapterMs = 65_000L, speed = 1.2f, skipSilence = false, volumeBoostMb = 0, finished = false)
    private val songs = listOf(track("a", "Song A"), track("b", "Song B", album = "Second Album"), track("c", "Song C"))
    private val savedMusic = SavedMusic(songs, index = 1, positionMs = 42_000L, shuffle = true, repeat = 2)

    private fun show(hub: FakePlayerHub): PlayerViewModel {
        val vm = PlayerViewModel(hub)
        compose.setContent { GrangerTheme { PlayerScreen(vm, onClose = {}) } }
        return vm
    }

    @Test fun inactiveTabShowsTheSavedBookAndSwitchingTabsCallsNothing() {
        val hub = FakePlayerHub(musicUi()).apply { saved.value = SavedModes(savedMusic, savedBook, PlayerMode.MUSIC) }
        val vm = show(hub)
        compose.onNodeWithText("Song A").assertExists()          // the active mode, live

        compose.onNodeWithText("Audiobooks").performClick()
        compose.onNodeWithText("The Hobbit").assertExists()
        compose.onNodeWithText("Roast Mutton · 2 of 2").assertExists()
        compose.onNodeWithText("1:05").assertExists()              // its saved position in the chapter
        compose.onNodeWithText("Book: 2:05 of 3:00 · 69%").assertExists()
        assertThat(vm.tab()).isEqualTo(PlayerMode.AUDIOBOOK)

        // Back and forth: the live music, then the same saved book, unchanged; the hub was never called.
        compose.onNodeWithText("Music").performClick()
        compose.onNodeWithText("Song A").assertExists()
        compose.onNodeWithText("Audiobooks").performClick()
        compose.onNodeWithText("1:05").assertExists()
        assertThat(hub.calls).isEmpty()
    }

    @Test fun playOnTheInactiveTabActivatesThatMode() {
        val hub = FakePlayerHub(musicUi()).apply { saved.value = SavedModes(savedMusic, savedBook, PlayerMode.MUSIC) }
        show(hub)
        compose.onNodeWithText("Audiobooks").performClick()
        compose.onNodeWithContentDescription("Play").performClick()
        assertThat(hub.calls).containsExactly("activate:AUDIOBOOK")

        // Another control: switch first, then do it.
        hub.calls.clear()
        compose.onNodeWithContentDescription("Back 30 seconds").performClick()
        compose.onNodeWithContentDescription("Next chapter").assertExists()
        compose.onNodeWithContentDescription("Previous chapter").performClick()
        assertThat(hub.calls).containsExactly("activate:AUDIOBOOK", "skipBack", "activate:AUDIOBOOK", "seekToChapter:0").inOrder()
    }

    @Test fun inactiveMusicShowsTheSavedQueueAndActivatesOnPlay() {
        val hub = FakePlayerHub(bookUi(book, chapters[0], chapterCount = 2)).apply {
            saved.value = SavedModes(savedMusic, savedBook, PlayerMode.AUDIOBOOK)
        }
        show(hub)
        compose.onNodeWithText("The Hobbit").assertExists()         // opens on the active mode
        compose.onNodeWithText("Music").performClick()
        compose.onNodeWithText("Song B").assertExists()
        compose.onNodeWithText("Second Album").assertExists()
        compose.onNodeWithText("0:42").assertExists()
        compose.onNodeWithText("Paused · 2 of 3").assertExists()
        compose.onNodeWithContentDescription("Shuffle on").assertExists()
        compose.onNodeWithContentDescription("Repeat one").assertExists()
        compose.onNodeWithContentDescription("Shuffle on").performClick()   // shows the state only
        assertThat(hub.calls).isEmpty()

        compose.onNodeWithContentDescription("Next track").performClick()
        assertThat(hub.calls).containsExactly("activate:MUSIC", "next").inOrder()
    }

    @Test fun aModeNeverUsedSaysSo() {
        val hub = FakePlayerHub(musicUi()).apply { saved.value = SavedModes(null, null, PlayerMode.MUSIC) }
        show(hub)
        compose.onNodeWithText("Audiobooks").performClick()
        compose.onNodeWithText("Nothing played yet").assertExists()
        assertThat(hub.calls).isEmpty()
    }

    @Test fun idleOpensOnTheLastModeUsed() {
        val hub = FakePlayerHub().apply { saved.value = SavedModes(savedMusic, savedBook, PlayerMode.AUDIOBOOK) }
        show(hub)
        compose.onNodeWithText("The Hobbit").assertExists()
        compose.onNodeWithContentDescription("Play").performClick()
        assertThat(hub.calls).containsExactly("activate:AUDIOBOOK")
    }
}
