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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
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
import app.granger.player.MiniPlayerHeight
import app.granger.theme.GrangerColors

/**
 * How far the shell has drawn something over the top of the content (the mini-player in a reader). A screen offsets
 * its top bars by this, so they show below it.
 */
val LocalTopOverlayInset = compositionLocalOf { 0.dp }

private val TopInsets @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)

/** The shell's own state, saved across rotation. */
class ShellState(railVisible: Boolean = true, expanded: Boolean = false) {
    /** Non-reader screens force this on; the reader sets it through a callback (immersive reading). */
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
 * The app frame: the side rail beside every screen. The narrow rail sits in the layout; the expanded one is
 * drawn over the content with a scrim. An edge swipe (from x < 24 dp, calling [onEdgeSwipe]) or tapping the rail's background expands
 * it; tapping the scrim, Back, or choosing an item collapses it. [miniPlayer] is drawn above [content] in the
 * content column, pushing it down, and takes the status bar's inset for itself. With [miniPlayerOverContent] it's
 * drawn over the top of the content instead (the reader: a middle tap mustn't reflow the page), beside the rail, and
 * [LocalTopOverlayInset] tells the content how far to move its top bars down so nothing is covered. With [railOverContent] the
 * narrow rail is drawn over the content instead of beside it, so showing it doesn't narrow the content (the reader,
 * whose page would otherwise reflow on every middle tap). [showResearch] is false on phones below the ASK tier, which
 * hides the Research tool.
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
    onCloseTab: (Long) -> Unit,
    onHistory: () -> Unit,
    onRecentSearch: (String) -> Unit,
    onRecentChat: (Long) -> Unit = {},
    showResearch: Boolean = false,
    railOverContent: Boolean = false,
    miniPlayer: (@Composable () -> Unit)? = null,
    miniPlayerOverContent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val open = expanded && railVisible
    // In the reader (rail over content) the swipe stays live while the rail is hidden: it is the way back to the chrome.
    val edgeSwipeActive = (railVisible || railOverContent) && !expanded
    BackHandler(enabled = open, onBack = onToggleExpanded)
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(if (edgeSwipeActive) Modifier.edgeSwipe(onEdgeSwipe) else Modifier),
    ) {
        val railBeside = railVisible && !railOverContent
        Row(Modifier.fillMaxSize()) {
            if (railBeside) {
                NarrowRail(rail, selected, showResearch, onToggleExpanded, onTool, onTab, onCloseTab, onHistory, onRecentSearch, onRecentChat)
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // The rail already pads for the start inset (landscape cutout); screens shouldn't again.
                    .then(if (railBeside) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)) else Modifier),
            ) {
                val pushing = miniPlayer != null && !miniPlayerOverContent
                if (miniPlayer != null && !miniPlayerOverContent) {
                    Box(Modifier.fillMaxWidth().background(GrangerColors.SurfaceVariant).windowInsetsPadding(TopInsets)) { miniPlayer() }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // The mini-player above already sits below the status bar; the screen's top bar shouldn't pad again.
                        .then(if (pushing) Modifier.consumeWindowInsets(TopInsets) else Modifier),
                ) {
                    val inset = if (miniPlayer != null && miniPlayerOverContent) MiniPlayerHeight else 0.dp
                    CompositionLocalProvider(LocalTopOverlayInset provides inset) { content() }
                }
            }
        }
        if (miniPlayer != null && miniPlayerOverContent) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = if (railVisible && railOverContent) NarrowRailWidth else 0.dp)
                    .background(GrangerColors.SurfaceVariant)
                    .windowInsetsPadding(TopInsets),
            ) { miniPlayer() }
        }
        if (railVisible && railOverContent) {
            NarrowRail(rail, selected, showResearch, onToggleExpanded, onTool, onTab, onCloseTab, onHistory, onRecentSearch, onRecentChat)
        }
        if (edgeSwipeActive) {
            // Gesture navigation claims the left edge for Back; Android lets an app take back up to 200 dp of it.
            Box(
                Modifier
                    .align(Alignment.CenterStart)
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
        AnimatedVisibility(open, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
            // Every choice in the expanded rail also collapses it.
            ExpandedRail(
                rail = rail,
                selected = selected,
                showResearch = showResearch,
                onTool = { onTool(it); onToggleExpanded() },
                onTab = { onTab(it); onToggleExpanded() },
                onCloseTab = onCloseTab,
                onHistory = { onHistory(); onToggleExpanded() },
                onRecentSearch = { onRecentSearch(it); onToggleExpanded() },
                onRecentChat = { onRecentChat(it); onToggleExpanded() },
            )
        }
    }
}

/**
 * Calls [onSwipe] once when a touch that starts within 24 dp of the left edge drags 24 dp rightwards. With gesture
 * navigation the system takes edge swipes outside the excluded 200 dp strip as Back.
 */
private fun Modifier.edgeSwipe(onSwipe: () -> Unit): Modifier = composed {
    val current by rememberUpdatedState(onSwipe)
    pointerInput(Unit) {
        val edge = 24.dp.toPx()
        val distance = 24.dp.toPx()
        awaitEachGesture {
            // Initial pass: see the touch before the screen underneath does, so the swipe wins over scrolling.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.x > edge) return@awaitEachGesture
            var fired = false
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (!fired && change.position.x - down.position.x > distance) {
                    fired = true
                    current()
                }
                if (fired) change.consume()
            }
        }
    }
}
