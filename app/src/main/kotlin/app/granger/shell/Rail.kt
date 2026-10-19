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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.Features
import app.granger.R
import app.granger.theme.GrangerColors

/** The rail's tools. Settings is pinned to the bottom; the rest sit at the top, in this order. */
enum class RailTool(val label: String, val shown: Boolean) {
    SEARCH("Search", true),
    RESEARCH("Research", Features.RESEARCH),
    LIBRARY("Library", true),
    PLAYER("Player", Features.PLAYER),
    SETTINGS("Settings", true),
}

internal val NarrowRailWidth = 56.dp
internal val ExpandedRailWidth = 200.dp

/** The tools at the top; Research also needs a phone that can run it ([showResearch]). */
private fun topTools(showResearch: Boolean) =
    RailTool.entries.filter { it.shown && it != RailTool.SETTINGS && (it != RailTool.RESEARCH || showResearch) }
private val RailInsets @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)

@Composable
private fun RailTool.painter(): Painter = when (this) {
    RailTool.SEARCH -> rememberVectorPainter(Icons.Filled.Search)
    RailTool.RESEARCH -> painterResource(R.drawable.ic_research)
    RailTool.LIBRARY -> painterResource(R.drawable.ic_library)
    RailTool.PLAYER -> rememberVectorPainter(Icons.Filled.PlayArrow)
    RailTool.SETTINGS -> rememberVectorPainter(Icons.Filled.Settings)
}

/** A recent play's icon by what it played: a book, a playlist, an artist, a folder, or other music. */
@Composable
private fun RecentItem.Play.painter(): Painter = when {
    key.startsWith("audiobook:") -> painterResource(R.drawable.ic_headphones)
    key.startsWith("playlist:") -> painterResource(R.drawable.ic_queue_music)
    key.startsWith("artist:") -> rememberVectorPainter(Icons.Filled.Person)
    key.startsWith("folder:") -> painterResource(R.drawable.ic_library)
    else -> painterResource(R.drawable.ic_music_note) // album:, genre:, songs
}

/** The always-there 56 dp rail: tool icons, bookmark letter tiles, History, recent search, chat and play icons, Settings. */
@Composable
internal fun NarrowRail(
    rail: RailUi,
    selected: RailTool?,
    showResearch: Boolean,
    onExpand: () -> Unit,
    onTool: (RailTool) -> Unit,
    onTab: (Long) -> Unit,
    onCloseTab: (Long) -> Unit,
    onHistory: () -> Unit,
    onRecentSearch: (String) -> Unit,
    onRecentChat: (Long) -> Unit,
    onRecentPlay: (RecentItem.Play) -> Unit,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .background(GrangerColors.Surface)
            // Tapping the rail's background opens the expanded rail.
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onExpand)
            .windowInsetsPadding(RailInsets)
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
            rail.tabs.forEach { tab -> TabTile(tab, onTab, onCloseTab) }
            IconButton(onClick = onHistory) {
                Icon(painterResource(R.drawable.ic_history), contentDescription = "History", tint = GrangerColors.OnSurfaceVariant)
            }
            if (rail.recent.isNotEmpty()) RailDivider(Modifier.width(32.dp))
            rail.recent.forEach { item ->
                when (item) {
                    is RecentItem.Search -> IconButton(onClick = { onRecentSearch(item.query) }) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = "Recent search: ${item.query}",
                            tint = GrangerColors.Outline,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    is RecentItem.Chat -> IconButton(onClick = { onRecentChat(item.chatId) }) {
                        Icon(
                            painterResource(R.drawable.ic_research),
                            contentDescription = "Research chat: ${item.title}",
                            tint = GrangerColors.Outline,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    is RecentItem.Play -> IconButton(onClick = { onRecentPlay(item) }) {
                        Icon(
                            item.painter(),
                            contentDescription = "Play again: ${item.title}",
                            tint = GrangerColors.Outline,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        ToolIcon(RailTool.SETTINGS, selected == RailTool.SETTINGS) { onTool(RailTool.SETTINGS) }
    }
}

/** The 200 dp rail drawn over the content: the same sections with labels and titles. Every choice collapses it. */
@Composable
internal fun ExpandedRail(
    rail: RailUi,
    selected: RailTool?,
    showResearch: Boolean,
    onTool: (RailTool) -> Unit,
    onTab: (Long) -> Unit,
    onCloseTab: (Long) -> Unit,
    onHistory: () -> Unit,
    onRecentSearch: (String) -> Unit,
    onRecentChat: (Long) -> Unit,
    onRecentPlay: (RecentItem.Play) -> Unit,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .background(GrangerColors.SurfaceVariant)
            // Swallow taps on the panel so they don't reach the scrim beneath it.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .windowInsetsPadding(RailInsets)
            .width(ExpandedRailWidth)
            .padding(vertical = 8.dp),
    ) {
        topTools(showResearch).forEach { tool -> LabelledRow(tool.painter(), tool.label, selected == tool) { onTool(tool) } }
        RailDivider(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionLabel("Bookmarks")
            rail.tabs.forEach { tab -> TabListRow(tab, onTab, onCloseTab) }
            LabelledRow(painterResource(R.drawable.ic_history), "History", selected = false, onClick = onHistory)
            if (rail.recent.isNotEmpty()) {
                SectionLabel("Recent")
                rail.recent.forEach { item ->
                    when (item) {
                        is RecentItem.Search -> LabelledRow(rememberVectorPainter(Icons.Filled.Search), item.query, selected = false) {
                            onRecentSearch(item.query)
                        }
                        is RecentItem.Chat -> LabelledRow(painterResource(R.drawable.ic_research), item.title, selected = false) {
                            onRecentChat(item.chatId)
                        }
                        is RecentItem.Play -> LabelledRow(item.painter(), item.title, selected = false) { onRecentPlay(item) }
                    }
                }
            }
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

private const val UNAVAILABLE_MESSAGE = "This pack or book isn't available. Check that it's still on the phone or SD card."

/** Tap opens the tab (when its pack is available); long-press offers "Remove from bookmarks". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabTarget(
    tab: RailTabUi,
    onTab: (Long) -> Unit,
    onCloseTab: (Long) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Box(modifier) {
        Box(
            Modifier.combinedClickable(
                onClick = {
                    if (tab.available) onTab(tab.tabId)
                    else Toast.makeText(context, UNAVAILABLE_MESSAGE, Toast.LENGTH_LONG).show()
                },
                onLongClick = { menu = true },
                onLongClickLabel = "Remove from bookmarks",
            ),
        ) { content() }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Remove from bookmarks") },
                onClick = {
                    menu = false
                    onCloseTab(tab.tabId)
                },
            )
        }
    }
}

@Composable
private fun TabTile(tab: RailTabUi, onTab: (Long) -> Unit, onCloseTab: (Long) -> Unit) {
    val description = if (tab.available) tab.title else "${tab.title}, not available"
    TabTarget(tab, onTab, onCloseTab, Modifier.semantics { contentDescription = description }) { LetterTile(tab) }
}

@Composable
private fun TabListRow(tab: RailTabUi, onTab: (Long) -> Unit, onCloseTab: (Long) -> Unit) {
    TabTarget(tab, onTab, onCloseTab, Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LetterTile(tab, size = 32)
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
}
