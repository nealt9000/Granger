package app.granger.player

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.percentOffset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.granger.LibraryRoute
import app.granger.ListenPlayerRoute
import app.granger.SearchRoute
import app.granger.core.player.PlayerUi
import app.granger.togglePlayer
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MiniPlayerTest {
    @get:Rule val compose = createComposeRule()

    private val tags = listOf(
        MiniPlayerTags.ROOT, MiniPlayerTags.COVER, MiniPlayerTags.TITLE, MiniPlayerTags.SUBTITLE,
        MiniPlayerTags.PLAY_PAUSE, MiniPlayerTags.PROGRESS, MiniPlayerTags.SECONDARY,
    )

    @Test fun miniPlayerHiddenWhenIdle() {
        compose.setContent { MiniPlayer(PlayerUi.IDLE, onPlayPause = {}, onSecondary = {}, onOpen = {}) }
        tags.forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
    }

    @Test fun miniPlayerShowsReadingOverTitleAndSectionWithBackASentence() {
        var ui by mutableStateOf(voiceUi(section = "History"))
        compose.setContent { MiniPlayer(ui, onPlayPause = {}, onSecondary = {}, onOpen = {}) }
        tags.forEach { compose.onNodeWithTag(it, useUnmergedTree = true).assertExists() }
        compose.onNodeWithTag(MiniPlayerTags.TITLE, useUnmergedTree = true).assertTextEquals("Rosetta Stone")
        compose.onNodeWithTag(MiniPlayerTags.SUBTITLE, useUnmergedTree = true).assertTextEquals("Reading · History")
        compose.onNodeWithTag(MiniPlayerTags.SECONDARY).assertContentDescriptionEquals("Back a sentence")
        val height = compose.onNodeWithTag(MiniPlayerTags.ROOT).fetchSemanticsNode().size.height
        ui = musicUi()
        compose.waitForIdle()
        assertThat(compose.onNodeWithTag(MiniPlayerTags.ROOT).fetchSemanticsNode().size.height).isEqualTo(height)
        ui = voiceUi(ended = true)
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPlayerTags.SUBTITLE, useUnmergedTree = true).assertTextEquals("Finished reading")
    }

    @Test fun theMusicLineNamesTheMusicUnderTheVoice() {
        val music = musicUi().now as app.granger.core.player.NowPlaying.Music
        var ui by mutableStateOf(voiceUi(section = "History"))
        compose.setContent { MiniPlayer(ui, onPlayPause = {}, onSecondary = {}, onOpen = {}) }
        compose.onNodeWithTag(MiniPlayerTags.MUSIC, useUnmergedTree = true).assertDoesNotExist()
        val height = compose.onNodeWithTag(MiniPlayerTags.ROOT).fetchSemanticsNode().size.height
        ui = ui.copy(now = (ui.now as app.granger.core.player.NowPlaying.Voice).copy(music = music, musicPlaying = true))
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPlayerTags.MUSIC, useUnmergedTree = true).assert(androidx.compose.ui.test.hasText(music.track.title, substring = true))
        compose.onNodeWithTag(MiniPlayerTags.SUBTITLE, useUnmergedTree = true).assertTextEquals("Reading · History")
        assertThat(compose.onNodeWithTag(MiniPlayerTags.ROOT).fetchSemanticsNode().size.height).isEqualTo(height)
    }

    @Test fun miniPlayerSameLayoutForMusicAndBook() {
        val book = audiobook("b", title = "The Book")
        val c = chapter(book, 0, 60_000L, title = "Opening")
        var ui by mutableStateOf(musicUi())
        compose.setContent { MiniPlayer(ui, onPlayPause = {}, onSecondary = {}, onOpen = {}, skipBackS = 30) }

        tags.forEach { compose.onNodeWithTag(it, useUnmergedTree = true).assertExists() }
        val musicHeight = compose.onNodeWithTag(MiniPlayerTags.ROOT).fetchSemanticsNode().size.height
        compose.onNodeWithTag(MiniPlayerTags.SECONDARY).assertContentDescriptionEquals("Next track")

        ui = bookUi(book, c, chapterCount = 1, positionMs = 5_000L)
        compose.waitForIdle()
        tags.forEach { compose.onNodeWithTag(it, useUnmergedTree = true).assertExists() }
        assertThat(compose.onNodeWithTag(MiniPlayerTags.ROOT).fetchSemanticsNode().size.height).isEqualTo(musicHeight)
        compose.onNodeWithTag(MiniPlayerTags.SECONDARY).assertContentDescriptionEquals("Back 30 seconds")
    }

    @Test fun miniPlayerButtonsReachTheirCallbacks() {
        val calls = mutableListOf<String>()
        compose.setContent {
            MiniPlayer(musicUi(), onPlayPause = { calls += "playPause" }, onSecondary = { calls += "secondary" }, onOpen = { calls += "open" })
        }
        compose.onNodeWithTag(MiniPlayerTags.PLAY_PAUSE).performClick()
        compose.onNodeWithTag(MiniPlayerTags.SECONDARY).performClick()
        compose.onNodeWithTag(MiniPlayerTags.TITLE, useUnmergedTree = true).performClick()
        assertThat(calls).containsExactly("playPause", "secondary", "open").inOrder()
    }

    @Test fun playerToolOpensFullPlayerAndToggles() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = LibraryRoute()) {
                composable<LibraryRoute> {}
                composable<SearchRoute> {}
                composable<ListenPlayerRoute> {}
            }
        }
        fun onPlayer() = nav.currentDestination?.hasRoute<ListenPlayerRoute>() == true

        compose.runOnIdle { nav.togglePlayer() }
        compose.runOnIdle { assertThat(onPlayer()).isTrue() }
        // Tapping the tool again returns to where it was opened from (and the mini-player).
        compose.runOnIdle { nav.togglePlayer() }
        compose.runOnIdle {
            assertThat(onPlayer()).isFalse()
            assertThat(nav.currentDestination?.hasRoute<LibraryRoute>()).isTrue()
        }
        // Back does the same.
        compose.runOnIdle { nav.togglePlayer() }
        compose.runOnIdle { nav.popBackStack() }
        compose.runOnIdle { assertThat(nav.currentDestination?.hasRoute<LibraryRoute>()).isTrue() }
        // Opened again with the player under another screen: brought back up, not stacked twice.
        compose.runOnIdle { nav.togglePlayer(); nav.navigate(SearchRoute()) }
        compose.runOnIdle { nav.togglePlayer() }
        compose.runOnIdle {
            assertThat(onPlayer()).isTrue()
            assertThat(nav.previousBackStackEntry?.destination?.hasRoute<LibraryRoute>()).isTrue()
        }
    }

    @Test fun theTapOpensThePlayerOnTheLiveTab() {
        val hub = FakePlayerHub(voiceUi())
        val vm = PlayerViewModel(hub)
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            androidx.compose.foundation.layout.Column {
                MiniPlayer(voiceUi(), onPlayPause = {}, onSecondary = {}, onOpen = { nav.togglePlayer(vm) })
                NavHost(nav, startDestination = LibraryRoute()) {
                    composable<LibraryRoute> {}
                    composable<ListenPlayerRoute> { ListenPlayerScreen(vm, onClose = { nav.popBackStack() }) }
                }
            }
        }
        fun tap() = compose.onNodeWithTag(MiniPlayerTags.TITLE, useUnmergedTree = true).performClick()

        // The voice: Reader, never a sheet.
        tap()
        compose.onNodeWithTag(ListenTabTags.READER).assertIsSelected()
        compose.onNodeWithTag(ReaderTags.SENTENCE).assertExists()
        // Another tab picked, then closed and opened again: back on what's live.
        compose.onNodeWithTag(ListenTabTags.MEDIA).performClick()
        tap()
        compose.runOnIdle { assertThat(nav.currentDestination?.hasRoute<LibraryRoute>()).isTrue() }
        tap()
        compose.onNodeWithTag(ListenTabTags.READER).assertIsSelected()
        // Music live: the media tab.
        tap()
        hub.ui.value = musicUi()
        tap()
        compose.onNodeWithTag(ListenTabTags.MEDIA).assertIsSelected()
        compose.onNodeWithText("Song A").assertExists()
    }

    @Test fun miniPlayerPlacementPushesEverywhereButTheReaders() {
        // Non-reading screens: always there when something is loaded, pushing the screen down.
        assertThat(miniPlayerPlacement(loaded = true, onPlayer = false, reading = false)).isEqualTo(MiniPlayerPlacement.PUSH)
        // Reading: over the page, sliding with the reader's chrome (Plan U3c Decision 5), whether that shows or not.
        assertThat(miniPlayerPlacement(loaded = true, onPlayer = false, reading = true)).isEqualTo(MiniPlayerPlacement.OVERLAY)
        // Nothing loaded, or the full-screen player showing instead.
        assertThat(miniPlayerPlacement(loaded = false, onPlayer = false, reading = false)).isEqualTo(MiniPlayerPlacement.HIDDEN)
        assertThat(miniPlayerPlacement(loaded = false, onPlayer = false, reading = true)).isEqualTo(MiniPlayerPlacement.HIDDEN)
        assertThat(miniPlayerPlacement(loaded = true, onPlayer = true, reading = false)).isEqualTo(MiniPlayerPlacement.HIDDEN)
    }

    @Test fun seekBarSeeksOnReleaseNotWhileDragging() {
        val seeks = mutableListOf<Long>()
        compose.setContent { SeekBar(positionMs = 0L, durationMs = 100_000L, onSeek = { seeks += it }) }
        compose.onNodeWithTag(PlayerTags.SEEK).performTouchInput {
            down(percentOffset(0.1f, 0.5f))
            // Past the touch slop first, so the slider takes it as a drag.
            moveBy(androidx.compose.ui.geometry.Offset(viewConfiguration.touchSlop + 1f, 0f))
            moveTo(center)
            moveTo(percentOffset(0.75f, 0.5f))
        }
        compose.waitForIdle()
        assertThat(seeks).isEmpty()
        compose.onNodeWithTag(PlayerTags.SEEK).performTouchInput { up() }
        compose.waitForIdle()
        assertThat(seeks).hasSize(1)
        assertThat(seeks.single()).isIn(com.google.common.collect.Range.closed(60_000L, 90_000L))
    }
}
