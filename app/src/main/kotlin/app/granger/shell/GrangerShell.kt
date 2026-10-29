package app.granger.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import app.granger.settings.RailSide

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
 * What the shell draws over a reader's page (Plan U3c Decision 5): the narrow rail on its side and the mini-player
 * along the top, with their insets. A reader's bars and panels keep clear of it; it is the same whether the chrome
 * shows or not, so they don't move when it slides. Empty outside the readers, where the shell lays both out.
 */
val LocalShellOverlay = compositionLocalOf { WindowInsets(0, 0, 0, 0) }

/** How long the reading chrome takes to slide away or back. */
const val ChromeSlideMs = 200

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
 * sliding in from the rail's edge. An edge swipe (within 24 dp of the rail's edge, inwards, calling [onEdgeSwipe]) or
 * tapping the rail's background expands it. Either rail's items open with [onTab], [onHistory], [onSearch], [onChat],
 * [onPlay] and [onReading] (Plan U3b Decision 6); a long press on any item offers [actions]. Tapping the scrim, Back,
 * or choosing an item collapses the expanded rail. [miniPlayer] is drawn above [content] in the
 * content column, pushing it down, and takes the status bar's inset for itself. In a reader ([reading]) the page
 * keeps the whole screen: the rail and the mini-player are drawn over it ([LocalShellOverlay]) and slide away with the
 * reader's chrome ([railVisible]); the edge swipe then stays live, as the way back to the chrome. [showResearch] is
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
        // A reader's page has the whole screen; the rail and the mini-player go over it (below), not beside it.
        val inLayout = railVisible && !reading
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        // The rail's width with its side's inset, as the narrow rail lays itself out; the mini-player's as measured.
        val railInset = SafeDrawingIgnoringBars.only(side.insetSide)
        val railWidth = with(density) { NarrowRailWidth.roundToPx() } +
            (if (right) railInset.getRight(density, direction) else railInset.getLeft(density, direction))
        var miniHeight by remember { mutableIntStateOf(0) }
        val overlay = if (!reading) WindowInsets(0, 0, 0, 0) else WindowInsets(
            left = if (right) 0 else railWidth,
            top = if (miniPlayer != null) miniHeight else 0,
            right = if (right) railWidth else 0,
            bottom = 0,
        )
        Row(Modifier.fillMaxSize()) {
            if (inLayout && !right) {
                NarrowRail(rail, selected, showResearch, onToggleExpanded, onTool, actions, side, onTab, onHistory, onSearch, onChat, onPlay, onReading)
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // The rail already pads for its side's inset (landscape cutout); screens shouldn't again.
                    .then(if (inLayout) Modifier.consumeWindowInsets(SafeDrawingIgnoringBars.only(side.insetSide)) else Modifier),
            ) {
                if (miniPlayer != null && !reading) {
                    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).windowInsetsPadding(TopInsets)) { miniPlayer() }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // The mini-player above already sits below the status bar; the screen's top bar shouldn't pad again.
                        .then(if (miniPlayer != null && !reading) Modifier.consumeWindowInsets(TopInsets) else Modifier),
                ) { CompositionLocalProvider(LocalShellOverlay provides overlay) { content() } }
            }
            if (inLayout && right) {
                NarrowRail(rail, selected, showResearch, onToggleExpanded, onTool, actions, side, onTab, onHistory, onSearch, onChat, onPlay, onReading)
            }
        }
        if (reading) {
            // Over the page: they slide off their edges with the reader's bars and back with them (Plan U3c Decision 5).
            if (miniPlayer != null) {
                val railDp = with(density) { railWidth.toDp() }
                AnimatedVisibility(
                    railVisible,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = if (right) 0.dp else railDp, end = if (right) railDp else 0.dp),
                    enter = slideInVertically(tween(ChromeSlideMs)) { -it },
                    exit = slideOutVertically(tween(ChromeSlideMs)) { -it },
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { miniHeight = it.height }
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .windowInsetsPadding(TopInsets),
                    ) { miniPlayer() }
                }
            }
            val offscreenRail = { width: Int -> if (right) width else -width }
            AnimatedVisibility(
                railVisible,
                modifier = Modifier.align(if (right) Alignment.CenterEnd else Alignment.CenterStart),
                enter = slideInHorizontally(tween(ChromeSlideMs), initialOffsetX = offscreenRail),
                exit = slideOutHorizontally(tween(ChromeSlideMs), targetOffsetX = offscreenRail),
            ) {
                NarrowRail(rail, selected, showResearch, onToggleExpanded, onTool, actions, side, onTab, onHistory, onSearch, onChat, onPlay, onReading)
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
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
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
