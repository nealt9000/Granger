package app.granger.player

import androidx.compose.ui.test.assertContentDescriptionEquals
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
import app.granger.PlayerRoute
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
                composable<PlayerRoute> {}
            }
        }
        fun onPlayer() = nav.currentDestination?.hasRoute<PlayerRoute>() == true

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

    @Test fun miniPlayerPlacementFollowsTheReaderChrome() {
        // Non-reading screens: always there when something is loaded, pushing the screen down.
        assertThat(miniPlayerPlacement(loaded = true, onPlayer = false, reading = false, railVisible = true)).isEqualTo(MiniPlayerPlacement.PUSH)
        // Immersive reading: only with the reader's chrome (and the rail), drawn over the page.
        assertThat(miniPlayerPlacement(loaded = true, onPlayer = false, reading = true, railVisible = false)).isEqualTo(MiniPlayerPlacement.HIDDEN)
        assertThat(miniPlayerPlacement(loaded = true, onPlayer = false, reading = true, railVisible = true)).isEqualTo(MiniPlayerPlacement.OVER)
        // Nothing loaded, or the full-screen player showing instead.
        assertThat(miniPlayerPlacement(loaded = false, onPlayer = false, reading = false, railVisible = true)).isEqualTo(MiniPlayerPlacement.HIDDEN)
        assertThat(miniPlayerPlacement(loaded = true, onPlayer = true, reading = false, railVisible = true)).isEqualTo(MiniPlayerPlacement.HIDDEN)
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
