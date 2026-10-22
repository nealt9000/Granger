package app.granger.shell

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.Features
import app.granger.R
import app.granger.settings.RailSide
import app.granger.theme.GrangerColors

/** The rail's tools. Settings is pinned to the bottom; the rest sit at the top, in this order. */
enum class RailTool(val label: String, val shown: Boolean) {
    SEARCH("Search", true),
    RESEARCH("Research", Features.RESEARCH),
    LIBRARY("Library", true),
    PLAYER("Player", Features.PLAYER),
    SETTINGS("Settings", true),
}

/** What a long press on a rail item offers: Pin to top or Unpin, then Remove from rail. */
class RailEntryActions(
    val onPin: (RailEntry) -> Unit = {},
    val onUnpin: (RailEntry) -> Unit = {},
    val onRemove: (RailEntry) -> Unit = {},
)

internal val NarrowRailWidth = 56.dp
internal val ExpandedRailWidth = 200.dp

/** The tools at the top; Research also needs a phone that can run it ([showResearch]). */
private fun topTools(showResearch: Boolean) =
    RailTool.entries.filter { it.shown && it != RailTool.SETTINGS && (it != RailTool.RESEARCH || showResearch) }

/** The rail pads for the top and bottom and for its own edge (a landscape cutout on that side). */
@Composable
private fun railInsets(side: RailSide) = SafeDrawingIgnoringBars.only(WindowInsetsSides.Vertical + side.insetSide)

@Composable
private fun RailTool.painter(): Painter = when (this) {
    RailTool.SEARCH -> rememberVectorPainter(Icons.Filled.Search)
    RailTool.RESEARCH -> painterResource(R.drawable.ic_research)
    RailTool.LIBRARY -> painterResource(R.drawable.ic_library)
    RailTool.PLAYER -> rememberVectorPainter(Icons.Filled.PlayArrow)
    RailTool.SETTINGS -> rememberVectorPainter(Icons.Filled.Settings)
}

/**
 * The icon for an item without a tile of its own: a search, a chat, or a play by what it played (a book, a playlist,
 * an artist, a folder, or other music).
 */
@Composable
private fun RailEntry.painter(): Painter = when (this) {
    is RailEntry.Search -> rememberVectorPainter(Icons.Filled.Search)
    is RailEntry.Chat -> painterResource(R.drawable.ic_research)
    is RailEntry.Tab -> painterResource(R.drawable.ic_library)
    is RailEntry.Play -> when {
        playKey.startsWith("audiobook:") -> painterResource(R.drawable.ic_headphones)
        playKey.startsWith("playlist:") -> painterResource(R.drawable.ic_queue_music)
        playKey.startsWith("artist:") -> rememberVectorPainter(Icons.Filled.Person)
        playKey.startsWith("folder:") -> painterResource(R.drawable.ic_library)
        playKey.startsWith("voice:") -> painterResource(R.drawable.ic_library)   // something read aloud
        else -> painterResource(R.drawable.ic_music_note) // album:, genre:, songs
    }
}

/** What TalkBack reads for an item's tile in the narrow rail. */
private val RailEntry.description: String
    get() = when (this) {
        is RailEntry.Tab -> if (tab.available) tab.title else "${tab.title}, not available"
        is RailEntry.Search -> "Recent search: $query"
        is RailEntry.Chat -> "Research chat: $title"
        is RailEntry.Play -> "Play again: $title"
    }

/**
 * The always-there 56 dp rail: tool icons, one column of tiles for the rail's list (covers and letter tiles for
 * articles and books, icons for the rest; a flame dot on pinned ones), History, Settings. Only the tools go straight to
 * their screens. The tiles and History are too little to tell apart here, so tapping them (or the rail's background)
 * calls [onExpand]: the full rail, where they have titles and open. A long press on a tile offers [actions].
 */
@Composable
internal fun NarrowRail(
    rail: RailUi,
    selected: RailTool?,
    showResearch: Boolean,
    onExpand: () -> Unit,
    onTool: (RailTool) -> Unit,
    actions: RailEntryActions,
    side: RailSide = RailSide.LEFT,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .background(GrangerColors.Surface)
            // Tapping the rail's background opens the expanded rail.
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onExpand)
            .windowInsetsPadding(railInsets(side))
            // After the insets: in landscape the cutout widens the rail rather than squeezing its 56 dp.
            .width(NarrowRailWidth)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        topTools(showResearch).forEach { tool -> ToolIcon(tool, selected == tool) { onTool(tool) } }
        RailDivider(Modifier.width(32.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rail.entries.forEach { entry -> EntryTile(entry, onExpand, actions) }
            IconButton(onClick = onExpand) {
                Icon(painterResource(R.drawable.ic_history), contentDescription = "History", tint = GrangerColors.OnSurfaceVariant)
            }
        }
        ToolIcon(RailTool.SETTINGS, selected == RailTool.SETTINGS) { onTool(RailTool.SETTINGS) }
    }
}

/**
 * The 200 dp rail drawn over the content: the tools with labels, then the rail's one list with titles (a thin "Pinned"
 * label above the pins when there are any), then History. Every choice but the long-press menu's collapses it.
 */
@Composable
internal fun ExpandedRail(
    rail: RailUi,
    selected: RailTool?,
    showResearch: Boolean,
    onTool: (RailTool) -> Unit,
    onTab: (Long) -> Unit,
    onHistory: () -> Unit,
    onSearch: (String) -> Unit,
    onChat: (Long) -> Unit,
    onPlay: (RailEntry.Play) -> Unit,
    actions: RailEntryActions,
    side: RailSide = RailSide.LEFT,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .background(GrangerColors.SurfaceVariant)
            // Swallow taps on the panel so they don't reach the scrim beneath it.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .windowInsetsPadding(railInsets(side))
            .width(ExpandedRailWidth)
            .padding(vertical = 8.dp),
    ) {
        topTools(showResearch).forEach { tool -> LabelledRow(tool.painter(), tool.label, selected == tool) { onTool(tool) } }
        RailDivider(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (rail.entries.any { it.pinned }) SectionLabel("Pinned")
            val context = LocalContext.current
            rail.entries.forEach { entry ->
                val open: () -> Unit = when (entry) {
                    is RailEntry.Tab -> {
                        {
                            if (entry.tab.available) onTab(entry.tab.tabId)
                            else Toast.makeText(context, UNAVAILABLE_MESSAGE, Toast.LENGTH_LONG).show()
                        }
                    }
                    is RailEntry.Search -> { { onSearch(entry.query) } }
                    is RailEntry.Chat -> { { onChat(entry.chatId) } }
                    is RailEntry.Play -> { { onPlay(entry) } }
                }
                EntryTarget(entry, actions, open, onClickLabel = "Open", Modifier.fillMaxWidth()) {
                    if (entry is RailEntry.Tab) TabRowContent(entry) else EntryRowContent(entry)
                }
            }
            LabelledRow(painterResource(R.drawable.ic_history), "History", selected = false, onClick = onHistory)
        }
        LabelledRow(RailTool.SETTINGS.painter(), RailTool.SETTINGS.label, selected == RailTool.SETTINGS) {
            onTool(RailTool.SETTINGS)
        }
    }
}

@Composable
private fun ToolIcon(tool: RailTool, selected: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            tool.painter(),
            contentDescription = tool.label,
            tint = if (selected) GrangerColors.Flame else GrangerColors.OnSurfaceVariant,
        )
    }
}

@Composable
private fun RailDivider(modifier: Modifier) {
    HorizontalDivider(modifier.padding(vertical = 8.dp), color = GrangerColors.OutlineVariant)
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = GrangerColors.Outline,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun LabelledRow(icon: Painter, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) GrangerColors.Flame else GrangerColors.OnSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            label,
            color = if (selected) GrangerColors.Flame else GrangerColors.OnSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The small flame dot on a pinned item's tile or icon, at its top corner. */
@Composable
private fun PinDot(modifier: Modifier) {
    Box(
        modifier
            .offset(x = 3.dp, y = (-3).dp)
            .size(8.dp)
            .clip(CircleShape)
            .background(GrangerColors.Flame)
            .testTag(PIN_DOT_TAG),
    )
}

/** [content] with the flame dot over its top end corner when [pinned]. */
@Composable
private fun WithPinDot(pinned: Boolean, content: @Composable () -> Unit) {
    Box {
        content()
        if (pinned) PinDot(Modifier.align(Alignment.TopEnd))
    }
}

/** A tab's letter tile; the current tab is outlined in flame, an unavailable one greyed out. */
@Composable
private fun LetterTile(tab: RailTabUi, size: Int = 36) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(size.dp)
            .alpha(if (tab.available) 1f else 0.4f)
            .clip(shape)
            .background(GrangerColors.SurfaceHigh)
            .then(if (tab.current) Modifier.border(2.dp, GrangerColors.Flame, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val cover = if (tab.coverPath != null) rememberRailCover(tab.coverPath, size.dp) else null
        if (cover != null) {
            Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(tab.initial.toString(), color = GrangerColors.OnSurface, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A search's, chat's or play's tile in the narrow rail: its icon on the same rounded square as a letter tile. */
@Composable
private fun IconTile(entry: RailEntry) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(GrangerColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(entry.painter(), contentDescription = null, tint = GrangerColors.OnSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

private const val UNAVAILABLE_MESSAGE = "This pack or book isn't available. Check that it's still on the phone or SD card."

internal const val PIN_DOT_TAG = "railPinDot"

/** Tap calls [onClick]; a long press offers Pin to top (or Unpin), then Remove from rail. Both are labelled for TalkBack. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryTarget(
    entry: RailEntry,
    actions: RailEntryActions,
    onClick: () -> Unit,
    onClickLabel: String,
    modifier: Modifier = Modifier,
    /** Semantics for the clickable node itself, so TalkBack reads them with its actions. */
    semantics: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier
                .combinedClickable(
                    onClickLabel = onClickLabel,
                    onClick = onClick,
                    onLongClick = { menu = true },
                    onLongClickLabel = if (entry.pinned) "Unpin or remove from rail" else "Pin to top or remove from rail",
                )
                .then(semantics),
        ) { content() }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (entry.pinned) "Unpin" else "Pin to top") },
                onClick = {
                    menu = false
                    if (entry.pinned) actions.onUnpin(entry) else actions.onPin(entry)
                },
            )
            DropdownMenuItem(
                text = { Text("Remove from rail") },
                onClick = {
                    menu = false
                    actions.onRemove(entry)
                },
            )
        }
    }
}

/** A narrow-rail tile; a tap opens the full rail (which says if a tab is unavailable). */
@Composable
private fun EntryTile(entry: RailEntry, onExpand: () -> Unit, actions: RailEntryActions) {
    val description = entry.description
    val modifier = Modifier.semantics {
        contentDescription = description
        if (entry.pinned) stateDescription = "Pinned"
    }
    EntryTarget(entry, actions, onExpand, onClickLabel = "Show the full rail", semantics = modifier) {
        WithPinDot(entry.pinned) {
            if (entry is RailEntry.Tab) LetterTile(entry.tab) else IconTile(entry)
        }
    }
}

@Composable
private fun TabRowContent(entry: RailEntry.Tab) {
    val tab = entry.tab
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { if (entry.pinned) stateDescription = "Pinned" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        WithPinDot(entry.pinned) { LetterTile(tab, size = 32) }
        Column(Modifier.alpha(if (tab.available) 1f else 0.5f)) {
            Text(
                tab.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (tab.current) GrangerColors.Flame else GrangerColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (tab.available) tab.subtitle else "${tab.subtitle} · not available",
                style = MaterialTheme.typography.bodySmall,
                color = GrangerColors.OnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A search's, chat's or play's row in the full rail: its icon and its query or title. */
@Composable
private fun EntryRowContent(entry: RailEntry) {
    val label = when (entry) {
        is RailEntry.Search -> entry.query
        is RailEntry.Chat -> entry.title
        is RailEntry.Play -> entry.title
        is RailEntry.Tab -> entry.tab.title
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { if (entry.pinned) stateDescription = "Pinned" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WithPinDot(entry.pinned) {
            Icon(entry.painter(), contentDescription = null, tint = GrangerColors.OnSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Text(
            label,
            color = GrangerColors.OnSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
