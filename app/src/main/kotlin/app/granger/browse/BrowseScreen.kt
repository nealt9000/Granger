package app.granger.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.formatBytes
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.theme.MediaKind
import app.granger.theme.MediaTile

/** What Browse's home can open; the NavHost wires each to its route. */
class BrowseActions(
    /** Opens an installed pack at its main page: the NavHost's one way to (Plan U3b Decision 3). */
    val onOpenPack: (packId: String) -> Unit = {},
    /** Get more on Kiwix; [domain] filters it to a readiness domain (a suggestion's). */
    val onGetPacks: (domain: String?) -> Unit = {},
    val onDownloads: () -> Unit = {},
    val onReadiness: () -> Unit = {},
    val onRemove: (Pack) -> Unit = {},
)

const val NOTHING_TO_BROWSE = "Nothing to browse yet"

/** How many packs a section shows before its Show all. */
const val SECTION_PREVIEW = 4

object BrowseTags {
    /** A section's Show all / Show fewer. */
    fun more(sectionId: String) = "browse_more_$sectionId"
}

@Composable
fun BrowseScreen(viewModel: BrowseViewModel, packsFolder: String, downloadsBadge: Int, actions: BrowseActions) {
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    BrowseContent(ui, packsFolder, downloadsBadge, BrowseActions(actions.onOpenPack, actions.onGetPacks, actions.onDownloads, actions.onReadiness, viewModel::remove))
}

/**
 * Browse's home (Plan U3c Decision 1): the packs themselves, by knowledge area, then Other, then Maps, each a row
 * opening the pack's main page. With none, [NOTHING_TO_BROWSE], Pick a start's suggestion and Get packs. Null [ui] is still
 * loading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseContent(ui: BrowseUi?, packsFolder: String, downloadsBadge: Int, actions: BrowseActions) {
    var menuOpen by remember { mutableStateOf(false) }
    // Section ids the user has collapsed; all start expanded, as in the Library.
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    // Section ids showing every pack rather than the first [SECTION_PREVIEW].
    var showingAll by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var removing by remember { mutableStateOf<Pack?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Browse", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { actions.onGetPacks(null) }) { Icon(Icons.Filled.Add, contentDescription = "Get more") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            BadgedBox(badge = { if (downloadsBadge > 0) Badge { Text("$downloadsBadge") } }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                        }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (downloadsBadge > 0) "Downloads ($downloadsBadge)" else "Downloads") },
                                onClick = { menuOpen = false; actions.onDownloads() },
                            )
                            DropdownMenuItem(text = { Text("Readiness") }, onClick = { menuOpen = false; actions.onReadiness() })
                        }
                    }
                },
            )
        },
    ) { padding ->
        val loaded = ui ?: return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (loaded.empty) {
                item(key = "empty") {
                    Text(
                        NOTHING_TO_BROWSE,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                for (suggestion in loaded.suggestions) {
                    item(key = "suggestion:${suggestion.title}") {
                        ListItem(
                            modifier = Modifier.clickable { actions.onGetPacks(suggestion.domain) },
                            headlineContent = { Text(suggestion.title) },
                            supportingContent = {
                                Text("${if (suggestion.map) "A map of your region" else "An encyclopedia"} · ${formatBytes(suggestion.bytes)}")
                            },
                        )
                    }
                }
                item(key = "get-packs") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Button(onClick = { actions.onGetPacks(null) }) { Text("Get packs") }
                        Text(
                            "Or copy .zim files into $packsFolder",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            for (section in loaded.sections) {
                val open = section.id !in collapsed
                item(key = "section:${section.id}") {
                    ListItem(
                        modifier = Modifier.clickable {
                            collapsed = if (open) collapsed + section.id else collapsed - section.id
                        },
                        headlineContent = { Text(section.title, style = MaterialTheme.typography.titleMedium) },
                        trailingContent = {
                            Icon(
                                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (open) "Collapse" else "Expand",
                            )
                        },
                    )
                }
                if (open) {
                    val all = section.id in showingAll
                    val shown = if (all) section.packs else section.packs.take(SECTION_PREVIEW)
                    for (item in shown) {
                        item(key = "pack:${item.pack.id}") {
                            PackItemRow(item, map = section.id == MAPS_SECTION, onOpen = { actions.onOpenPack(item.pack.id) }, onRemove = { removing = item.pack })
                        }
                    }
                    if (section.packs.size > SECTION_PREVIEW) {
                        item(key = "more:${section.id}") {
                            TextButton(
                                onClick = { showingAll = if (all) showingAll - section.id else showingAll + section.id },
                                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp).testTag(BrowseTags.more(section.id)),
                            ) {
                                Text(if (all) "Show fewer" else "Show all ${section.packs.size}")
                            }
                        }
                    }
                }
                item(key = "divider:${section.id}") { HorizontalDivider() }
            }
        }
    }
    removing?.let { pack ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${pack.title}?") },
            text = { Text("${pack.title} will be deleted from this phone. You can download it again later.") },
            confirmButton = { TextButton(onClick = { removing = null; actions.onRemove(pack) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } },
        )
    }
}

/**
 * One pack: its tile, title, size and Ask line (maps have none). A tap opens it; a long press offers Remove from phone.
 * One that's missing or damaged says so instead of opening.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PackItemRow(item: PackItem, map: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    val pack = item.pack
    var menu by remember { mutableStateOf(false) }
    val problem = when (pack.status) {
        PackStatus.OK -> null
        PackStatus.MISSING -> "File not found. Reinsert the SD card or download the pack again."
        PackStatus.CORRUPT -> "This file is damaged and can't be opened."
    }
    Box {
        ListItem(
            modifier = Modifier.combinedClickable(
                onClickLabel = "Open",
                onClick = { if (pack.status == PackStatus.OK) onOpen() },
                onLongClickLabel = "Remove from phone",
                onLongClick = { menu = true },
            ),
            leadingContent = { PackTile(pack, map) },
            headlineContent = { Text(pack.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Column {
                    Text(listOfNotNull(formatBytes(pack.sizeBytes), item.ask?.text).joinToString(" · "))
                    if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error)
                }
            },
        )
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Remove from phone") }, onClick = { menu = false; onRemove() })
        }
    }
}

/**
 * A pack's tile: the themed tile (Plan U3c Decision 6) with its title's initial, and a globe for reference or a pin for
 * a map. The library keeps no pack illustrations yet.
 */
@Composable
private fun PackTile(pack: Pack, map: Boolean) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))) {
        MediaTile(pack.title, if (map) MediaKind.MAP else MediaKind.REFERENCE)
    }
}
