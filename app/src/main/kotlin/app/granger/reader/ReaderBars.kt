package app.granger.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.player.ListenButtons
import app.granger.player.VoiceButton
import app.granger.shell.SafeDrawingIgnoringBars

/** An entry in a reader's ⋯ menu. */
data class ReaderMenuItem(val label: String, val onClick: () -> Unit)

/**
 * What the reader bars offer (Plan U3c Decision 4), the same for the wiki, e-books and PDFs: a null hides its
 * button, and an empty [menu] hides ⋯.
 */
data class ReaderBarActions(
    val onBack: () -> Unit,
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

/**
 * A reader's top bar: ←, the title on one line (ellipsized when Listen and Stop need the room), Listen and ⋯.
 * Below the status bar's place even while the system bars animate.
 */
@Composable
fun ReaderTopBar(title: String, actions: ReaderBarActions, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth().testTag(ReaderBarTags.TOP), color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(ReaderTopBarInsets).height(64.dp).padding(horizontal = 4.dp),
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
 * slider) above them. Clear of the navigation bar, and in landscape of the cutout beside the rail.
 */
@Composable
fun ReaderBottomBar(actions: ReaderBarActions, modifier: Modifier = Modifier, progress: (@Composable () -> Unit)? = null) {
    Surface(modifier.fillMaxWidth().testTag(ReaderBarTags.BOTTOM), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.windowInsetsPadding(SafeDrawingIgnoringBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)).padding(vertical = 4.dp)) {
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
