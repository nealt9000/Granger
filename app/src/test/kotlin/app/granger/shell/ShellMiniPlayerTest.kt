package app.granger.shell

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.granger.TestGrangerApp
import app.granger.player.MiniPlayerHeight
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShellMiniPlayerTest {
    @get:Rule val compose = createComposeRule()

    @Composable
    private fun Shell(miniPlayer: Boolean, railVisible: Boolean = true, reading: Boolean = false) {
        GrangerShell(
            rail = RailUi(emptyList()),
            expanded = false,
            railVisible = railVisible,
            selected = null,
            onToggleExpanded = {},
            onEdgeSwipe = {},
            onTool = {},
            onTab = {},
            onHistory = {},
            onSearch = {},
            reading = reading,
            miniPlayer = if (miniPlayer) {
                { Box(Modifier.fillMaxWidth().height(MiniPlayerHeight).testTag("mini")) }
            } else {
                null
            },
        ) {
            Box(Modifier.fillMaxSize().testTag("content"))
        }
    }

    @Test fun pushedMiniPlayerMovesTheScreenDown() {
        compose.setContent { Shell(miniPlayer = true) }
        val mini = compose.onNodeWithTag("mini").getBoundsInRoot()
        val content = compose.onNodeWithTag("content").getBoundsInRoot()
        assertThat(content.top).isEqualTo(mini.bottom)
    }

    @Test fun readerGetsTheWholeScreenWithTheRailAndTheMiniPlayerOverIt() {
        // Plan U3c Decision 5: the page isn't laid out around the chrome; the rail and the mini-player are drawn over it.
        compose.setContent { Shell(miniPlayer = true, railVisible = true, reading = true) }
        val mini = compose.onNodeWithTag("mini").getBoundsInRoot()
        val content = compose.onNodeWithTag("content").getBoundsInRoot()
        assertThat(content.top).isEqualTo(0.dp)
        assertThat(content.left).isEqualTo(0.dp)
        // The mini-player sits beside the rail, over the top of the page.
        assertThat(mini.left).isEqualTo(NarrowRailWidth)
        assertThat(mini.top).isEqualTo(0.dp)
        compose.onNodeWithContentDescription("Library").assertIsDisplayed()
    }

    @Test fun theReaderIsToldWhatIsOverItSoItsBarsKeepClear() {
        var overlay = WindowInsets(0, 0, 0, 0)
        var density = Density(1f)
        compose.setContent {
            density = LocalDensity.current
            GrangerShell(
                rail = RailUi(emptyList()), expanded = false, railVisible = true, selected = null,
                onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {},
                reading = true,
                miniPlayer = { Box(Modifier.fillMaxWidth().height(MiniPlayerHeight)) },
            ) { overlay = LocalShellOverlay.current }
        }
        compose.waitForIdle()
        with(density) {
            assertThat(overlay.getLeft(this, LayoutDirection.Ltr)).isEqualTo(NarrowRailWidth.roundToPx())
            assertThat(overlay.getTop(this)).isEqualTo(MiniPlayerHeight.roundToPx())
            assertThat(overlay.getRight(this, LayoutDirection.Ltr)).isEqualTo(0)
        }
    }

    @Test fun outsideTheReadersNothingIsOverTheScreen() {
        var overlay: WindowInsets? = null
        var density = Density(1f)
        compose.setContent {
            density = LocalDensity.current
            GrangerShell(
                rail = RailUi(emptyList()), expanded = false, railVisible = true, selected = null,
                onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {},
                miniPlayer = { Box(Modifier.fillMaxWidth().height(MiniPlayerHeight)) },
            ) { overlay = LocalShellOverlay.current }
        }
        compose.waitForIdle()
        with(density) {
            assertThat(overlay!!.getLeft(this, LayoutDirection.Ltr)).isEqualTo(0)
            assertThat(overlay!!.getTop(this)).isEqualTo(0)
        }
    }

    @Test fun theReadersChromeGoingSlidesTheRailAndTheMiniPlayerAwayWithoutMovingThePage() {
        var visible by mutableStateOf(true)
        compose.setContent { Shell(miniPlayer = true, railVisible = visible, reading = true) }
        val before = compose.onNodeWithTag("content").getBoundsInRoot()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            visible = false
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(ChromeSlideMs / 2L)
        // Half way: the mini-player is part way up off the top, and the page hasn't moved.
        val sliding = compose.onNodeWithTag("mini").getBoundsInRoot()
        assertThat(sliding.top).isLessThan(0.dp)
        assertThat(sliding.bottom).isGreaterThan(0.dp)
        assertThat(compose.onNodeWithTag("content").getBoundsInRoot()).isEqualTo(before)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("mini").assertDoesNotExist()
        compose.onNodeWithContentDescription("Library").assertDoesNotExist()
        assertThat(compose.onNodeWithTag("content").getBoundsInRoot()).isEqualTo(before)
    }

    @Test fun jvmTestsRunOnTheTestAppWhichSkipsTheEagerPlayerStart() {
        // GrangerApp.onCreate would bind a MediaController and start phone scans; the test app records the call instead.
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertThat(app).isInstanceOf(TestGrangerApp::class.java)
        assertThat((app as TestGrangerApp).mediaStarted).isTrue()
    }
}
