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
import androidx.compose.ui.unit.Dp
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

    private var overlayInset: Dp = (-1).dp

    @Composable
    private fun Shell(miniPlayer: Boolean, over: Boolean, railVisible: Boolean = true, railOverContent: Boolean = false) {
        GrangerShell(
            rail = RailUi(emptyList(), emptyList()),
            expanded = false,
            railVisible = railVisible,
            selected = null,
            onToggleExpanded = {},
            onEdgeSwipe = {},
            onTool = {},
            onTab = {},
            onCloseTab = {},
            onHistory = {},
            onRecentSearch = {},
            railOverContent = railOverContent,
            miniPlayer = if (miniPlayer) {
                { Box(Modifier.fillMaxWidth().height(MiniPlayerHeight).testTag("mini")) }
            } else {
                null
            },
            miniPlayerOverContent = over,
        ) {
            overlayInset = LocalTopOverlayInset.current
            Box(Modifier.fillMaxSize().testTag("content"))
        }
    }

    @Test fun pushedMiniPlayerMovesTheScreenDown() {
        compose.setContent { Shell(miniPlayer = true, over = false) }
        val mini = compose.onNodeWithTag("mini").getBoundsInRoot()
        val content = compose.onNodeWithTag("content").getBoundsInRoot()
        assertThat(content.top).isEqualTo(mini.bottom)
        assertThat(overlayInset).isEqualTo(0.dp)
    }

    @Test fun readerMiniPlayerIsDrawnOverThePageWithoutReflowingIt() {
        // The reader: rail and mini-player both over the page, which keeps its full size either way.
        compose.setContent { Shell(miniPlayer = true, over = true, railOverContent = true) }
        val mini = compose.onNodeWithTag("mini").getBoundsInRoot()
        val content = compose.onNodeWithTag("content").getBoundsInRoot()
        assertThat(content.top).isEqualTo(0.dp)
        assertThat(content.left).isEqualTo(0.dp)
        // Beside the rail, not under it; the reader moves its top bars down by this much.
        assertThat(mini.left).isEqualTo(NarrowRailWidth)
        assertThat(overlayInset).isEqualTo(MiniPlayerHeight)
    }

    @Test fun readerWithoutMiniPlayerHasNoOverlayInset() {
        compose.setContent { Shell(miniPlayer = false, over = false, railVisible = false, railOverContent = true) }
        compose.onNodeWithTag("mini").assertDoesNotExist()
        assertThat(compose.onNodeWithTag("content").getBoundsInRoot().top).isEqualTo(0.dp)
        assertThat(overlayInset).isEqualTo(0.dp)
    }

    @Test fun jvmTestsRunOnTheTestAppWhichSkipsTheEagerPlayerStart() {
        // GrangerApp.onCreate would bind a MediaController and start phone scans; the test app records the call instead.
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertThat(app).isInstanceOf(TestGrangerApp::class.java)
        assertThat((app as TestGrangerApp).mediaStarted).isTrue()
    }
}
