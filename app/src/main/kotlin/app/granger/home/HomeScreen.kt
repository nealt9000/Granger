package app.granger.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import app.granger.core.library.FeedKind
import app.granger.shell.RailEntry
import app.granger.shell.rememberRailCover

/** What the screen can do for a feed. */
class FeedActions(
    val onAdd: (FeedScope, FeedKind, String) -> Unit = { _, _, _ -> },
    val onRemove: (Long) -> Unit = {},
    val onMove: (Long, Boolean) -> Unit = { _, _ -> },
)

/** [onOpenTab] shows a reading tab; [onPlay] plays a rail item again; [onOpenCard] opens a feed card's target. */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onReadiness: () -> Unit,
    onOpenTab: (Long) -> Unit,
    onPlay: (RailEntry.Play) -> Unit,
    onOpenCard: (Card) -> Unit,
) {
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val actions = remember(viewModel) { FeedActions(viewModel::addFeed, viewModel::removeFeed, viewModel::moveFeed) }
    HomeContent(ui, onReadiness, onOpenTab, onPlay, onOpenCard, actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    ui: HomeUi,
    onReadiness: () -> Unit,
    onOpenTab: (Long) -> Unit,
    onPlay: (RailEntry.Play) -> Unit,
    onOpenCard: (Card) -> Unit = {},
    actions: FeedActions = FeedActions(),
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Home") },
                actions = { IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = "Add feed") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("home-list")) {
            item(key = "strip") { ReadinessStrip(ui.strip, onReadiness) }
            if (adding) {
                item(key = "add-feed") {
                    AddFeedPanel(
                        ui.scopeChoices,
                        onAdd = { scope, kind, query ->
                            actions.onAdd(scope, kind, query)
                            adding = false
                        },
                        onCancel = { adding = false },
                    )
                }
            }
            if (ui.continueCards.isNotEmpty()) {
                item(key = "continue") { Heading("Continue") }
                item(key = "continue-row") {
                    CardRow(ui.continueCards, key = { it.key }, tag = "continue-row") { entry ->
                        when (entry) {
                            is RailEntry.Tab -> TabCard(entry) { onOpenTab(entry.tab.tabId) }
                            is RailEntry.Play -> PlayCard(entry) { onPlay(entry) }
                            else -> {}
                        }
                    }
                }
            }
            for ((i, row) in ui.feeds.withIndex()) {
                item(key = "feed-${row.feed.id}") {
                    FeedHeading(
                        title = row.drawn?.title ?: "${row.feed.kind.label} · ${row.scopeName}",
                        first = i == 0,
                        last = i == ui.feeds.lastIndex,
                        onRemove = { actions.onRemove(row.feed.id) },
                        onMove = { up -> actions.onMove(row.feed.id, up) },
                    )
                }
                item(key = "feed-row-${row.feed.id}") {
                    val drawn = row.drawn
                    if (drawn == null) {
                        Text(
                            "Drawing…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    } else {
                        CardRow(drawn.cards, key = { "${it.title}|${it.target}" }, tag = "feed-row-${row.feed.id}") { card ->
                            FeedCard(card) { onOpenCard(card) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> CardRow(items: List<T>, key: (T) -> Any, tag: String, card: @Composable (T) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.testTag(tag),
    ) {
        items(items, key = key) { card(it) }
    }
}

@Composable
internal fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** A feed's title; a long press offers Move up, Move down and Remove. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedHeading(title: String, first: Boolean, last: Boolean, onRemove: () -> Unit, onMove: (up: Boolean) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Heading(title, Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true }))
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (!first) DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; onMove(true) })
            if (!last) DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; onMove(false) })
            DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove() })
        }
    }
}

private val CardWidth = 132.dp
private val TileHeight = 88.dp

/** A reading tab: its cover when it has one, else a letter tile; greyed when its pack is gone. */
@Composable
private fun TabCard(entry: RailEntry.Tab, onClick: () -> Unit) {
    val tab = entry.tab
    Card(title = tab.title, subtitle = tab.subtitle, enabled = tab.available, onClick = onClick) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cover = rememberRailCover(tab.coverPath, maxWidth)
            if (cover != null) {
                Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                LetterTile(tab.initial.toString())
            }
        }
    }
}

@Composable
private fun PlayCard(entry: RailEntry.Play, onClick: () -> Unit) {
    Card(title = entry.title, subtitle = "Play again", enabled = true, onClick = onClick) {
        IconTile(rememberVectorPainter(Icons.Filled.PlayArrow))
    }
}

/** A feed card: its picture when it has one, else an icon for what it opens; the line; where it is from. */
@Composable
private fun FeedCard(card: Card, onClick: () -> Unit) {
    Card(title = card.title, subtitle = card.subtitle, enabled = true, onClick = onClick) {
        if (card.imagePath != null) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val image = rememberRailCover(card.imagePath, maxWidth)
                if (image != null) {
                    Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    TargetIcon(card)
                }
            }
        } else {
            TargetIcon(card)
        }
    }
}

@Composable
private fun TargetIcon(card: Card) {
    IconTile(
        when (card.target) {
            is CardTarget.Article -> painterResource(R.drawable.ic_library)
            is CardTarget.Book -> painterResource(R.drawable.ic_library)
            is CardTarget.Album -> painterResource(R.drawable.ic_music_note)
            is CardTarget.Audiobook -> painterResource(R.drawable.ic_headphones)
            is CardTarget.Search -> rememberVectorPainter(Icons.Filled.Search)
        },
    )
}

@Composable
private fun IconTile(painter: Painter) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(painter, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LetterTile(letter: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(letter, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A card in a row: a tile on top, a title and a subtitle under it. */
@Composable
internal fun Card(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit, tile: @Composable () -> Unit) {
    Column(
        Modifier
            .width(CardWidth)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(TileHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) { tile() }
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
