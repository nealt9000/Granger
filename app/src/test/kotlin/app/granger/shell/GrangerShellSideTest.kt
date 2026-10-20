package app.granger.shell

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.granger.settings.RailSide
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GrangerShellSideTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var view: View
    private lateinit var density: Density
    private var swipes = 0

    @Composable
    private fun Shell(side: RailSide, expanded: Boolean = false, reading: Boolean = false) {
        view = LocalView.current
        density = LocalDensity.current
        GrangerShell(
            rail = RailUi(emptyList()),
            expanded = expanded,
            railVisible = true,
            selected = null,
            onToggleExpanded = {},
            onEdgeSwipe = { swipes++ },
            onTool = {},
            onTab = {},
            onHistory = {},
            onSearch = {},
            side = side,
            reading = reading,
        ) {
            Box(Modifier.fillMaxSize().testTag("content")) {
                // A screen padding for the safe area, as the screens' top bars do.
                Box(Modifier.fillMaxSize().windowInsetsPadding(SafeDrawingIgnoringBars).testTag("padded"))
            }
        }
    }

    private val rootWidth get() = compose.onRoot().getBoundsInRoot().right

    @Test fun leftKeepsTheRailBeforeTheContent() {
        compose.setContent { Shell(RailSide.LEFT) }
        val content = compose.onNodeWithTag("content").getBoundsInRoot()
        assertThat(content.left).isEqualTo(NarrowRailWidth)
        assertThat(content.right).isEqualTo(rootWidth)
    }

    @Test fun rightPutsTheRailAfterTheContent() {
        compose.setContent { Shell(RailSide.RIGHT) }
        val content = compose.onNodeWithTag("content").getBoundsInRoot()
        assertThat(content.left).isEqualTo(0.dp)
        assertThat(content.right).isEqualTo(rootWidth - NarrowRailWidth)
    }

    @Test fun rightRailPadsTheEndInset() {
        // Landscape with the cutout on the right: the rail widens by the cutout, and the content isn't padded for it.
        compose.setContent { Shell(RailSide.RIGHT) }
        val cutoutPx = 48
        compose.runOnIdle {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 0, cutoutPx, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        compose.waitForIdle()
        val cutout = with(density) { cutoutPx.toDp() }
        val content = compose.onNodeWithTag("content").getBoundsInRoot()
        assertThat(content.left).isEqualTo(0.dp)
        assertThat(content.right).isEqualTo(rootWidth - NarrowRailWidth - cutout)
        // The rail took the cutout; the screen beside it doesn't pad for it again.
        assertThat(compose.onNodeWithTag("padded").getBoundsInRoot().right).isEqualTo(content.right)
    }

    @Test fun expandedRailSlidesFromTheRight() {
        var expanded by mutableStateOf(false)
        compose.setContent { Shell(RailSide.RIGHT, expanded = expanded) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            expanded = true
            // With the clock paused, nothing else sends the write to the composition.
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(64)
        // Part way in: the panel is still to the right of where it settles.
        val sliding = compose.onNodeWithText("Library").getBoundsInRoot()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val settled = compose.onNodeWithText("Library").getBoundsInRoot()
        assertThat(sliding.left).isGreaterThan(settled.left)
        // It settles against the right edge.
        assertThat(settled.left).isAtLeast(rootWidth - ExpandedRailWidth)
    }

    @Test fun edgeSwipeFromTheRightEdgeExpands() {
        compose.setContent { Shell(RailSide.RIGHT) }
        compose.onRoot().performTouchInput {
            down(centerRight.copy(x = width - 10f))
            moveBy(Offset(-40.dp.toPx(), 0f))
            up()
        }
        compose.runOnIdle { assertThat(swipes).isEqualTo(1) }
        // Dragging the wrong way, or from the far edge, does nothing.
        compose.onRoot().performTouchInput {
            down(centerRight.copy(x = width - 10f))
            moveBy(Offset(40.dp.toPx(), 0f))
            up()
        }
        compose.onRoot().performTouchInput {
            down(centerLeft.copy(x = 10f))
            moveBy(Offset(40.dp.toPx(), 0f))
            up()
        }
        compose.runOnIdle { assertThat(swipes).isEqualTo(1) }
    }

    @Test fun edgeSwipeFromTheLeftEdgeStillExpandsOnTheLeft() {
        compose.setContent { Shell(RailSide.LEFT) }
        compose.onRoot().performTouchInput {
            down(centerLeft.copy(x = 10f))
            moveBy(Offset(40.dp.toPx(), 0f))
            up()
        }
        compose.runOnIdle { assertThat(swipes).isEqualTo(1) }
    }

    @Test fun exclusionStripIsOnTheRailSide() {
        compose.setContent { Shell(RailSide.RIGHT) }
        compose.runOnIdle {
            val strip = with(density) { 24.dp.toPx() }
            val rects = view.systemGestureExclusionRects
            assertThat(rects).isNotEmpty()
            rects.forEach { assertThat(it.left.toFloat()).isAtLeast(view.width - strip - 1f) }
        }
    }

    @Test fun exclusionStripIsOnTheLeftByDefault() {
        compose.setContent { Shell(RailSide.LEFT) }
        compose.runOnIdle {
            val strip = with(density) { 24.dp.toPx() }
            val rects = view.systemGestureExclusionRects
            assertThat(rects).isNotEmpty()
            rects.forEach { assertThat(it.right.toFloat()).isAtMost(strip + 1f) }
        }
    }
}
