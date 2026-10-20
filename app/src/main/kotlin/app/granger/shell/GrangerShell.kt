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
import androidx.compose.foundation.layout.safeDrawing
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
import app.granger.theme.GrangerColors

private val TopInsets @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)

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
 * The app frame: the side rail beside every screen. The narrow rail sits in the layout; the expanded one is
 * drawn over the content with a scrim. An edge swipe (from x < 24 dp, calling [onEdgeSwipe]) or tapping the rail's background,
 * its bookmark tiles, History or its recents expands it (only the tools go straight to their screens from the narrow rail;
 * [onTab], [onHistory] and the recent callbacks are reached from the expanded one); tapping the scrim, Back, or choosing an item collapses it. [miniPlayer] is drawn above [content] in the
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
    onCloseTab: (Long) -> Unit,
    onHistory: () -> Unit,
    onRecentSearch: (String) -> Unit,
    onRecentChat: (Long) -> Unit = {},
    onRecentPlay: (RecentItem.Play) -> Unit = {},
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
            .then(if (edgeSwipeActive) Modifier.edgeSwipe(onEdgeSwipe) else Modifier),
    ) {
        Row(Modifier.fillMaxSize()) {
            if (railVisible) {
                NarrowRail(rail, selected, showResearch, onToggleExpanded, onTool, onCloseTab)
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // The rail already pads for the start inset (landscape cutout); screens shouldn't again.
                    .then(if (railVisible) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)) else Modifier),
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
                onRecentPlay = { onRecentPlay(it); onToggleExpanded() },
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
