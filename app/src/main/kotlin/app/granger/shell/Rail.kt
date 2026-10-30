package app.granger.shell

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.granger.Features
import app.granger.R
import app.granger.settings.RailSide
import app.granger.theme.MediaKind
import app.granger.theme.MediaTile

/** The rail's tools. Settings is pinned to the bottom; the rest sit at the top, in this order. */
enum class RailTool(val label: String, val shown: Boolean) {
    HOME("Home", true),
    SEARCH("Search", true),
    RESEARCH("Ask", Features.RESEARCH),
    /** The wiki and Kiwix packs and the maps, browsed as linked sites (Plan U3c Decision 1). */
    BROWSE("Browse", true),
    /** Books: what you read through (Plan U3c Decision 2). */
    LIBRARY("Library", true),
    /** Your player and your audio collection (Plan U2 Decision 4). */
    LISTEN("Listen", Features.PLAYER),
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

/** A narrow-rail tile's height, and the gap below it: each open item or History takes a 56 dp slot. */
private val NarrowRailTile = 48.dp
private val NarrowRailGap = 8.dp

/** How many of the narrow rail's open items fit in [height], and whether History does; History is the first to fit. */
internal data class NarrowRailFit(val items: Int, val history: Boolean)

internal fun narrowRailItemsThatFit(height: Dp, entries: Int): NarrowRailFit {
    // n slots need n tiles and n - 1 gaps.
    val slots = ((height + NarrowRailGap) / (NarrowRailTile + NarrowRailGap)).toInt().coerceAtLeast(0)
    return NarrowRailFit(items = (slots - 1).coerceIn(0, entries), history = slots >= 1)
}

/** The tools at the top; Research also needs a phone that can run it ([showResearch]). */
private fun topTools(showResearch: Boolean) =
    RailTool.entries.filter { it.shown && it != RailTool.SETTINGS && (it != RailTool.RESEARCH || showResearch) }

/** The rail pads for the top and bottom and for its own edge (a landscape cutout on that side). */
@Composable
private fun railInsets(side: RailSide) = SafeDrawingIgnoringBars.only(WindowInsetsSides.Vertical + side.insetSide)

/**
 * The tool's own drawable; null for Search and Settings, which use Material's vectors. The owl in the G is Home (Plan
 * U3b Decision 5); an open book is the Library, and ic_library stays for articles. Listen is headphones: the player and
 * the collection (Plan U2 Decision 4). Browse is a globe, Material's public (Plan U3c Decision 1; ic_browse_alt, a
 * compass, is the candidate).
 */
@DrawableRes
internal fun RailTool.drawable(): Int? = when (this) {
    RailTool.HOME -> R.drawable.ic_owl
    RailTool.RESEARCH -> R.drawable.ic_research
    RailTool.BROWSE -> R.drawable.ic_browse
    RailTool.LIBRARY -> R.drawable.ic_library_books_open
    RailTool.LISTEN -> R.drawable.ic_headphones
    RailTool.SEARCH, RailTool.SETTINGS -> null
}

@Composable
private fun RailTool.painter(): Painter = drawable()?.let { painterResource(it) } ?: when (this) {
    RailTool.SETTINGS -> rememberVectorPainter(Icons.Filled.Settings)
    else -> rememberVectorPainter(Icons.Filled.Search)
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
        playKey.startsWith("voice:") -> painterResource(R.drawable.ic_voice)     // something read aloud
        else -> painterResource(R.drawable.ic_music_note) // album:, genre:, songs
    }
    is RailEntry.Reading -> painterResource(R.drawable.ic_voice)
}

/** What TalkBack reads for an item's tile in the narrow rail. */
private val RailEntry.description: String
    get() = when (this) {
        is RailEntry.Tab -> (if (tab.available) tab.title else "${tab.title}, not available") + if (tab.readingAloud) ", reading aloud" else ""
        is RailEntry.Search -> "Recent search: $query"
        is RailEntry.Chat -> "Ask chat: $title"
        is RailEntry.Play -> "Play again: $title"
        is RailEntry.Reading -> "Reading aloud: $title"
    }

/**
 * The always-there 56 dp rail: tool icons, one column of tiles for the rail's list (covers and letter tiles for
 * articles and books, icons for the rest; an accent dot on pinned ones), History, Settings. A tile opens its item and
 * History opens History (Plan U3b Decision 6: the four are few enough to know by sight). Tapping the rail's background
 * calls [onExpand]: the full rail, with titles. A long press on a tile offers [actions].
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
    onTab: (Long) -> Unit = {},
    onHistory: () -> Unit = {},
    onSearch: (String) -> Unit = {},
    onChat: (Long) -> Unit = {},
    onPlay: (RailEntry.Play) -> Unit = {},
    onReading: (RailEntry.Reading) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
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
        // The tools and Settings always show; the open items take whole slots of what's left, History first, and drop
        // from the end (Plan U3c Decision 3). No scrolling, so a short rail (the keyboard up) never cuts a tile in half.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val shown = narrowRailItemsThatFit(maxHeight, rail.entries.size)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NarrowRailGap),
            ) {
                rail.entries.take(shown.items).forEach { entry ->
                    Box(Modifier.size(NarrowRailTile), contentAlignment = Alignment.Center) {
                        EntryTile(entry, openAction(entry, onTab, onSearch, onChat, onPlay, onReading), actions)
                    }
                }
                if (shown.history) {
                    IconButton(onClick = onHistory) {
                        Icon(painterResource(R.drawable.ic_history), contentDescription = "History", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
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
    /** The live reading's row (Plan V2): opens the page being read. */
    onReading: (RailEntry.Reading) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainer)
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
            rail.entries.forEach { entry ->
                val open = openAction(entry, onTab, onSearch, onChat, onPlay, onReading)
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

/** What a tap on [entry] does in either rail; an unavailable tab says so instead of opening. */
@Composable
private fun openAction(
    entry: RailEntry,
    onTab: (Long) -> Unit,
    onSearch: (String) -> Unit,
    onChat: (Long) -> Unit,
    onPlay: (RailEntry.Play) -> Unit,
    onReading: (RailEntry.Reading) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    return when (entry) {
        is RailEntry.Tab -> {
            {
                if (entry.tab.available) onTab(entry.tab.tabId)
                else Toast.makeText(context, UNAVAILABLE_MESSAGE, Toast.LENGTH_LONG).show()
            }
        }
        is RailEntry.Search -> { { onSearch(entry.query) } }
        is RailEntry.Chat -> { { onChat(entry.chatId) } }
        is RailEntry.Play -> { { onPlay(entry) } }
        is RailEntry.Reading -> { { onReading(entry) } }
    }
}

@Composable
private fun ToolIcon(tool: RailTool, selected: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            tool.painter(),
            contentDescription = tool.label,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RailDivider(modifier: Modifier) {
    HorizontalDivider(modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun LabelledRow(icon: Painter, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The small accent dot on a pinned item's tile or icon, at its top corner. */
@Composable
private fun PinDot(modifier: Modifier) {
    Box(
        modifier
            .offset(x = 3.dp, y = (-3).dp)
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .testTag(PIN_DOT_TAG),
    )
}

/** [content] with the accent dot over its top end corner when [pinned]. */
@Composable
private fun WithPinDot(pinned: Boolean, content: @Composable () -> Unit) {
    Box {
        content()
        if (pinned) PinDot(Modifier.align(Alignment.TopEnd))
    }
}

/**
 * A tab's tile: its cover, or the themed tile (Plan U3c Decision 6). The current tab is outlined in the accent, an
 * unavailable one greyed out, and one the voice is reading wears a speaker badge at its bottom corner (Plan U3b,
 * Neal's walkthrough: one entry per book).
 */
@Composable
private fun LetterTile(tab: RailTabUi, size: Int = 36) {
    val shape = RoundedCornerShape(8.dp)
    Box {
        Box(
            Modifier
                .size(size.dp)
                .alpha(if (tab.available) 1f else 0.4f)
                .clip(shape)
                .then(if (tab.current) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val cover = if (tab.coverPath != null) rememberRailCover(tab.coverPath, size.dp) else null
            if (cover != null) {
                Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                MediaTile(tab.title, tab.mediaKind())
            }
        }
        if (tab.readingAloud) ReadingBadge(Modifier.align(Alignment.BottomEnd))
    }
}

/** An article tab's glyph is a page; a book's, a book. */
internal fun RailTabUi.mediaKind(): MediaKind = if (packId.isEmpty()) MediaKind.BOOK else MediaKind.ARTICLE

/** A play's glyph, by what it played. */
internal fun RailEntry.Play.mediaKind(): MediaKind = when {
    playKey.startsWith("audiobook:") -> MediaKind.AUDIOBOOK
    playKey.startsWith("playlist:") -> MediaKind.PLAYLIST
    playKey.startsWith("artist:") -> MediaKind.ARTIST
    playKey.startsWith("folder:") -> MediaKind.FOLDER
    playKey.startsWith("voice:") -> MediaKind.VOICE
    else -> MediaKind.MUSIC // album:, genre:, songs
}

/** The small speaker on a tab the voice is reading. */
@Composable
private fun ReadingBadge(modifier: Modifier) {
    Box(
        modifier
            .offset(x = 3.dp, y = 3.dp)
            .size(16.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .testTag(READING_BADGE_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_voice), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(10.dp))
    }
}

/**
 * A search's, chat's or play's tile in the narrow rail, on the themed tile (Plan U3c Decision 6): a play shows its
 * title's initial and what it played; a search, a chat or the voice its glyph alone.
 */
@Composable
private fun IconTile(entry: RailEntry) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))) {
        when (entry) {
            is RailEntry.Play -> MediaTile(entry.title, entry.mediaKind())
            is RailEntry.Search -> MediaTile(null, MediaKind.SEARCH)
            is RailEntry.Chat -> MediaTile(null, MediaKind.CHAT)
            is RailEntry.Reading -> MediaTile(null, MediaKind.VOICE)
            is RailEntry.Tab -> MediaTile(entry.tab.title, entry.tab.mediaKind())
        }
    }
}

private const val UNAVAILABLE_MESSAGE = "This pack or book isn't available. Check that it's still on the phone or SD card."

internal const val PIN_DOT_TAG = "railPinDot"

internal const val READING_BADGE_TAG = "railReadingBadge"

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

/** A narrow-rail tile; a tap opens its item ([onOpen]). */
@Composable
private fun EntryTile(entry: RailEntry, onOpen: () -> Unit, actions: RailEntryActions) {
    val description = entry.description
    val modifier = Modifier.semantics {
        contentDescription = description
        if (entry.pinned) stateDescription = "Pinned"
    }
    EntryTarget(entry, actions, onOpen, onClickLabel = "Open", semantics = modifier) {
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
                color = if (tab.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (if (tab.available) tab.subtitle else "${tab.subtitle} · not available") + if (tab.readingAloud) " · reading aloud" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        is RailEntry.Reading -> "Reading aloud: ${entry.title}"
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
            Icon(entry.painter(), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
