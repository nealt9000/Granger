package app.granger.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.granger.settings.RailSide
import app.granger.theme.GrangerColors

/**
 * [WindowInsets.safeDrawing], except that the status and navigation bars count where they would be even while
 * hidden. A reader hides them for full-screen reading; layouts padded by this don't follow the bars' show and hide
 * animation frame by frame, so a middle tap lays the page out once. Wherever the bars show it equals safeDrawing.
 */
@OptIn(ExperimentalLayoutApi::class)
val SafeDrawingIgnoringBars: WindowInsets
    @Composable get() = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout).union(WindowInsets.ime)

private val TopInsets @Composable get() = SafeDrawingIgnoringBars.only(WindowInsetsSides.Top)

/**
 * The inset side on the rail's edge. The app is always laid out left to right (no RTL support), so Start is the left
 * and End the right.
 */
internal val RailSide.insetSide: WindowInsetsSides
    get() = if (this == RailSide.RIGHT) WindowInsetsSides.End else WindowInsetsSides.Start

/** The shell's own state, saved across rotation. */
class ShellState(railVisible: Boolean = true, expanded: Boolean = false) {
    /** Non-reader screens force this on; the reader sets it through a callback (off for full-screen reading). */
    var railVisible by mutableStateOf(railVisible)

    /** The 200 dp rail is open over the content. */
    var expanded by mutableStateOf(expanded)

    companion object {
        val Saver: Saver<ShellState, Any> = listSaver(
            save = { listOf(it.railVisible, it.expanded) },
            restore = { ShellState(it[0], it[1]) },
        )
    }
}

@Composable
fun rememberShellState(): ShellState = rememberSaveable(saver = ShellState.Saver) { ShellState() }

/**
 * The app frame: the side rail beside every screen, on the left or the right ([side]; only the rail moves, the text
 * and icons don't flip). The narrow rail sits in the layout; the expanded one is drawn over the content with a scrim,
 * sliding in from the rail's edge. An edge swipe (within 24 dp of the rail's edge, inwards, calling [onEdgeSwipe]) or tapping the rail's background,
 * its tiles or History expands it (only the tools go straight to their screens from the narrow rail;
 * [onTab], [onHistory], [onSearch], [onChat] and [onPlay] are reached from the expanded one; a long press on any item
 * offers [actions]); tapping the scrim, Back, or choosing an item collapses it. [miniPlayer] is drawn above [content] in the
 * content column, pushing it down, and takes the status bar's inset for itself. In a reader ([reading]) the rail can
 * be hidden for full-screen reading; the edge swipe then stays live, as the way back to the chrome. [showResearch] is
 * false on phones below the ASK tier, which hides the Research tool.
 */
@Composable
fun GrangerShell(
    rail: RailUi,
    expanded: Boolean,
    railVisible: Boolean,
    selected: RailTool?,
    onToggleExpanded: () -> Unit,
    onEdgeSwipe: () -> Unit,
    onTool: (RailTool) -> Unit,
    onTab: (Long) -> Unit,
    onHistory: () -> Unit,
    onSearch: (String) -> Unit,
    onChat: (Long) -> Unit = {},
    onPlay: (RailEntry.Play) -> Unit = {},
    onReading: (RailEntry.Reading) -> Unit = {},
    actions: RailEntryActions = RailEntryActions(),
    side: RailSide = RailSide.LEFT,
    showResearch: Boolean = false,
    reading: Boolean = false,
    miniPlayer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val open = expanded && railVisible
    // In a reader the swipe stays live while the rail is hidden: it is the way back to the chrome.
    val edgeSwipeActive = (railVisible || reading) && !expanded
    BackHandler(enabled = open, onBack = onToggleExpanded)
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(if (edgeSwipeActive) Modifier.edgeSwipe(side, onEdgeSwipe) else Modifier),
    ) {
        val right = side == RailSide.RIGHT
        Row(Modifier.fillMaxSize()) {
            if (railVisible && !right) {
                NarrowRail(rail, selected, showResearch, onToggleExpanded, onTool, actions, side)
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // The rail already pads for its side's inset (landscape cutout); screens shouldn't again.
                    .then(if (railVisible) Modifier.consumeWindowInsets(SafeDrawingIgnoringBars.only(side.insetSide)) else Modifier),
            ) {
                if (miniPlayer != null) {
                    Box(Modifier.fillMaxWidth().background(GrangerColors.SurfaceVariant).windowInsetsPadding(TopInsets)) { miniPlayer() }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // The mini-player above already sits below the status bar; the screen's top bar shouldn't pad again.
                        .then(if (miniPlayer != null) Modifier.consumeWindowInsets(TopInsets) else Modifier),
                ) { content() }
            }
            if (railVisible && right) {
                NarrowRail(rail, selected, showResearch, onToggleExpanded, onTool, actions, side)
            }
        }
        if (edgeSwipeActive) {
            // Gesture navigation claims both edges for Back; Android lets an app take back up to 200 dp of the rail's.
            Box(
                Modifier
                    .align(if (right) Alignment.CenterEnd else Alignment.CenterStart)
                    .width(24.dp)
                    .height(200.dp)
                    .systemGestureExclusion(),
            )
        }
        AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(GrangerColors.Background.copy(alpha = 0.6f))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onToggleExpanded),
            )
        }
        // From the rail's edge: off to the left (-width) or to the right (+width).
        val offscreen = { width: Int -> if (right) width else -width }
        AnimatedVisibility(
            open,
            modifier = Modifier.align(if (right) Alignment.CenterEnd else Alignment.CenterStart),
            enter = slideInHorizontally(initialOffsetX = offscreen),
            exit = slideOutHorizontally(targetOffsetX = offscreen),
        ) {
            // Every choice in the expanded rail also collapses it.
            ExpandedRail(
                rail = rail,
                selected = selected,
                showResearch = showResearch,
                onTool = { onTool(it); onToggleExpanded() },
                onTab = { onTab(it); onToggleExpanded() },
                onHistory = { onHistory(); onToggleExpanded() },
                onSearch = { onSearch(it); onToggleExpanded() },
                onChat = { onChat(it); onToggleExpanded() },
                onPlay = { onPlay(it); onToggleExpanded() },
                onReading = { onReading(it); onToggleExpanded() },
                actions = actions,
                side = side,
            )
        }
    }
}

/**
 * Calls [onSwipe] once when a touch that starts within 24 dp of the rail's edge ([side]) drags 24 dp inwards:
 * rightwards from the left edge, leftwards from the right. With gesture navigation the system takes edge swipes
 * outside the excluded 200 dp strip as Back.
 */
private fun Modifier.edgeSwipe(side: RailSide, onSwipe: () -> Unit): Modifier = composed {
    val current by rememberUpdatedState(onSwipe)
    pointerInput(side) {
        val edge = 24.dp.toPx()
        val distance = 24.dp.toPx()
        // +1 when inwards is rightwards (the left rail), -1 when it is leftwards (the right rail).
        val inwards = if (side == RailSide.RIGHT) -1f else 1f
        awaitEachGesture {
            // Initial pass: see the touch before the screen underneath does, so the swipe wins over scrolling.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Measured from the rail's edge.
            val fromEdge = if (side == RailSide.RIGHT) size.width - down.position.x else down.position.x
            if (fromEdge > edge) return@awaitEachGesture
            var fired = false
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (!fired && (change.position.x - down.position.x) * inwards > distance) {
                    fired = true
                    current()
                }
                if (fired) change.consume()
            }
        }
    }
}
