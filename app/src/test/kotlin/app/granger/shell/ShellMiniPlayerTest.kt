package app.granger.shell

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

    @Test fun readerWithItsChromeShowingFitsThePageBesideTheRailAndUnderTheMiniPlayer() {
        // Like every other screen: the page is laid out in the space the rail and the mini-player leave, not under them.
        compose.setContent { Shell(miniPlayer = true, railVisible = true, reading = true) }
        val mini = compose.onNodeWithTag("mini").getBoundsInRoot()
        val content = compose.onNodeWithTag("content").getBoundsInRoot()
        assertThat(content.left).isEqualTo(NarrowRailWidth)
        assertThat(mini.left).isEqualTo(NarrowRailWidth)
        assertThat(content.top).isEqualTo(mini.bottom)
    }

    @Test fun fullScreenReaderGetsTheWholeScreen() {
        // The chrome hidden by a middle tap: no rail, no mini-player, the page from edge to edge.
        compose.setContent { Shell(miniPlayer = false, railVisible = false, reading = true) }
        compose.onNodeWithTag("mini").assertDoesNotExist()
        val content = compose.onNodeWithTag("content").getBoundsInRoot()
        assertThat(content.top).isEqualTo(0.dp)
        assertThat(content.left).isEqualTo(0.dp)
    }

    @Test fun jvmTestsRunOnTheTestAppWhichSkipsTheEagerPlayerStart() {
        // GrangerApp.onCreate would bind a MediaController and start phone scans; the test app records the call instead.
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertThat(app).isInstanceOf(TestGrangerApp::class.java)
        assertThat((app as TestGrangerApp).mediaStarted).isTrue()
    }
}
