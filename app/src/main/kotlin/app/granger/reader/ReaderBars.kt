package app.granger.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.player.ListenButtons
import app.granger.player.VoiceButton
import app.granger.shell.ChromeSlideMs
import app.granger.shell.LocalShellOverlay
import app.granger.shell.SafeDrawingIgnoringBars

/**
 * What the wiki's main-page button says (TalkBack): the pack's main page, not Home, which is the app's (Plan U3b
 * Decision 5), though the icon is a house.
 */
const val MAIN_PAGE = "Main page"

/** An entry in a reader's ⋯ menu. */
data class ReaderMenuItem(val label: String, val onClick: () -> Unit)

/**
 * What the reader bars offer (Plan U3c Decision 4), the same for the wiki, e-books and PDFs: a null hides its
 * button, and an empty [menu] hides ⋯.
 */
data class ReaderBarActions(
    val onBack: () -> Unit,
    /**
     * The wiki's pack's main page, a top-bar button before Listen (Plan U3c walkthrough): one tap home in a wiki reopened
     * where it was left. Null leaves it out (e-books, PDFs, a pack with no main page).
     */
    val onMainPage: (() -> Unit)? = null,
    /** Starts the voice here; null leaves Listen out. */
    val onListen: (() -> Unit)? = null,
    /** Listen, or Pause / Play with Stop beside it while this text is read (Plan U2 Decision 6). */
    val voice: VoiceButton = VoiceButton.IDLE,
    val onPauseResume: () -> Unit = {},
    val onStop: () -> Unit = {},
    val menu: List<ReaderMenuItem> = emptyList(),
    val onContents: (() -> Unit)? = null,
    val onSearch: (() -> Unit)? = null,
    /** What TalkBack reads for Search: "Search Wikipedia", "Search in book". */
    val searchLabel: String = "Search",
    /** Aa: the text sheet. */
    val onTextSettings: (() -> Unit)? = null,
    val onBookmark: (() -> Unit)? = null,
    val bookmarked: Boolean = false,
)

object ReaderBarTags {
    const val TOP = "reader_top_bar"
    const val BOTTOM = "reader_bottom_bar"
    const val TITLE = "reader_title"
}

/** The top bar's own height, below its insets. */
val ReaderTopBarHeight: Dp = 64.dp

/** The bottom bar's own height without a book's progress row (the wiki's), above its insets: a 48 dp row padded 4 dp. */
val ReaderBottomBarHeight: Dp = 56.dp

/**
 * A reader's top bar: below the status bar's place even while the system bars animate, or below the mini-player when
 * the shell draws it over the page; beside the rail ([LocalShellOverlay]).
 */
internal val ReaderTopBarInsets @Composable get() =
    SafeDrawingIgnoringBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        .union(LocalShellOverlay.current.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))

/**
 * A reader's bottom bar: above the navigation bar's place, beside the rail. Not the keyboard's: the bar is over the
 * page, and the page's padding for it shouldn't change while the find bar types.
 */
@OptIn(ExperimentalLayoutApi::class)
internal val ReaderBottomBarInsets @Composable get() =
    WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        .union(LocalShellOverlay.current.only(WindowInsetsSides.Horizontal))

/** A reader's page: clear of a landscape cutout and the side navigation bar, the same with the chrome up or away. */
internal val ReaderPageInsets @Composable get() = SafeDrawingIgnoringBars.only(WindowInsetsSides.Horizontal)

/**
 * An e-book's or PDF's page: [ReaderPageInsets], and clear of the rail over it ([LocalShellOverlay]) so the lines'
 * starts aren't under it at rest. Constant: the shell keeps the rail's place while it's away, so Readium never lays the
 * book out again for it. (The wiki's page pads inside instead, in CSS: [chromePaddingScript].)
 */
internal val ReaderBookPageInsets @Composable get() = ReaderPageInsets.union(LocalShellOverlay.current.only(WindowInsetsSides.Horizontal))

/** A reader's panels and sheets over the page: inside what the shell draws over it ([LocalShellOverlay]). */
@Composable
fun ClearOfShell(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(LocalShellOverlay.current), content = content)
}

/**
 * A reader's bar over the page (Plan U3c Decision 5): along the [top] or bottom edge, sliding off it when [visible]
 * turns false and back when it turns true. The page under it isn't laid out again. [onHeight] reports its height
 * (in px, with its insets) whenever that changes, for the page's padding.
 */
@Composable
fun BoxScope.SlidingBar(visible: Boolean, top: Boolean, onHeight: (Int) -> Unit = {}, content: @Composable () -> Unit) {
    val offscreen = { height: Int -> if (top) -height else height }
    AnimatedVisibility(
        visible,
        modifier = Modifier.align(if (top) Alignment.TopCenter else Alignment.BottomCenter),
        enter = slideInVertically(tween(ChromeSlideMs), initialOffsetY = offscreen),
        exit = slideOutVertically(tween(ChromeSlideMs), targetOffsetY = offscreen),
    ) {
        Box(Modifier.onSizeChanged { onHeight(it.height) }) { content() }
    }
}

/**
 * A reader's top bar: ←, the title on one line (ellipsized when Listen and Stop need the room), the wiki's Main page,
 * Listen and ⋯.
 * Clear of the status bar's place, the mini-player and the rail ([ReaderTopBarInsets]).
 */
@Composable
fun ReaderTopBar(title: String, actions: ReaderBarActions, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth().testTag(ReaderBarTags.TOP), color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(ReaderTopBarInsets).height(ReaderTopBarHeight).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(
                title,
                Modifier.weight(1f).padding(horizontal = 4.dp).testTag(ReaderBarTags.TITLE),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            actions.onMainPage?.let { IconButton(onClick = it) { Icon(Icons.Filled.Home, contentDescription = MAIN_PAGE) } }
            actions.onListen?.let { ListenButtons(actions.voice, it, actions.onPauseResume, actions.onStop) }
            if (actions.menu.isNotEmpty()) Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    actions.menu.forEach { item ->
                        DropdownMenuItem(text = { Text(item.label) }, onClick = { menuOpen = false; item.onClick() })
                    }
                }
            }
        }
    }
}

/**
 * A reader's bottom bar: Contents, Search, Aa and the bookmark, spaced evenly, with [progress] (a book's place and
 * slider) above them. Clear of the navigation bar, of the rail, and in landscape of the cutout.
 */
@Composable
fun ReaderBottomBar(actions: ReaderBarActions, modifier: Modifier = Modifier, progress: (@Composable () -> Unit)? = null) {
    Surface(modifier.fillMaxWidth().testTag(ReaderBarTags.BOTTOM), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.windowInsetsPadding(ReaderBottomBarInsets).padding(vertical = 4.dp)) {
            if (progress != null) Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { progress() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                actions.onContents?.let { IconButton(onClick = it) { Icon(Icons.Filled.Menu, contentDescription = "Contents") } }
                actions.onSearch?.let { IconButton(onClick = it) { Icon(Icons.Filled.Search, contentDescription = actions.searchLabel) } }
                actions.onTextSettings?.let {
                    IconButton(onClick = it, Modifier.semantics { contentDescription = "Text settings" }) {
                        Text("Aa", style = MaterialTheme.typography.titleMedium)
                    }
                }
                actions.onBookmark?.let {
                    IconButton(onClick = it) {
                        if (actions.bookmarked) {
                            Icon(Icons.Filled.Star, contentDescription = "Remove bookmark", tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(painterResource(R.drawable.ic_star_border), contentDescription = "Add bookmark")
                        }
                    }
                }
            }
        }
    }
}
