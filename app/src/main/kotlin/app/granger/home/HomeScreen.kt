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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import app.granger.shell.mediaKind
import app.granger.shell.rememberRailCover
import app.granger.theme.MediaTile

/** What Home's downloads card can do (Plan DL Decision 7); a tap on it opens Downloads. */
class DownloadsCardActions(
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onRetry: (DownloadsCard.Stopped) -> Unit = {},
    val onDismiss: (DownloadsCard.Stopped) -> Unit = {},
    /** A tidy offer's Remove and Keep both (Plan WF Decision 5). */
    val onTidyRemove: (app.granger.sources.TidyOffer) -> Unit = {},
    val onTidyKeep: (app.granger.sources.TidyOffer) -> Unit = {},
)

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
    /** The downloads card opens Downloads, where the whole queue is (Plan DL Decision 7). */
    onOpenDownloads: () -> Unit = {},
) {
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val actions = remember(viewModel) { FeedActions(viewModel::addFeed, viewModel::removeFeed, viewModel::moveFeed) }
    val cardActions = remember(viewModel) {
        DownloadsCardActions(
            viewModel::pauseDownloads, viewModel::resumeDownloads, viewModel::retryStopped, viewModel::dismissStopped,
            viewModel::removeSmaller, viewModel::keepBoth,
        )
    }
    HomeContent(ui, onReadiness, onOpenTab, onPlay, onOpenCard, actions, onOpenDownloads, cardActions)
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
    /** The downloads card opens Downloads, where the whole queue is (Plan DL Decision 7). */
    onOpenDownloads: () -> Unit = {},
    downloadsActions: DownloadsCardActions = DownloadsCardActions(),
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
            ui.downloads?.let { card -> item(key = "downloads") { DownloadsCardView(card, onOpenDownloads, downloadsActions) } }
            for (offer in ui.tidy) {
                item(key = "tidy-${offer.key}") {
                    app.granger.sources.TidyOfferCard(offer, { downloadsActions.onTidyRemove(offer) }, { downloadsActions.onTidyKeep(offer) })
                }
            }
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

/** A reading tab: its cover when it has one, else the themed tile (Plan U3c Decision 6); greyed when its pack is gone. */
@Composable
private fun TabCard(entry: RailEntry.Tab, onClick: () -> Unit) {
    val tab = entry.tab
    Card(title = tab.title, subtitle = tab.subtitle, enabled = tab.available, onClick = onClick) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cover = rememberRailCover(tab.coverPath, maxWidth)
            if (cover != null) {
                Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                MediaTile(tab.title, tab.mediaKind())
            }
        }
    }
}

/** Something played: the themed tile with its title and what it played (Continue keeps no covers for plays). */
@Composable
private fun PlayCard(entry: RailEntry.Play, onClick: () -> Unit) {
    Card(title = entry.title, subtitle = entry.kind, enabled = true, onClick = onClick) {
        MediaTile(entry.title, entry.mediaKind())
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

/**
 * Home's one card for downloads, where the setup strip was: the file downloading, the round's bar and figures, and
 * Pause or Resume; or "2 stopped · Retry" with a close. A tap opens Downloads.
 */
@Composable
private fun DownloadsCardView(card: DownloadsCard, onOpen: () -> Unit, actions: DownloadsCardActions) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .testTag("downloads-card"),
    ) {
        when (card) {
            is DownloadsCard.Active -> {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(
                        homeDownloadsTitle(card),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator(progress = { card.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    Text(
                        homeDownloadsText(card),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (card.paused) {
                    IconButton(onClick = actions.onResume) { Icon(Icons.Filled.PlayArrow, contentDescription = "Resume downloads") }
                } else {
                    IconButton(onClick = actions.onPause) { Icon(painterResource(R.drawable.ic_pause), contentDescription = "Pause downloads") }
                }
            }
            is DownloadsCard.Stopped -> {
                Text(
                    "${card.count} stopped",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                if (card.retriable.isNotEmpty()) TextButton(onClick = { actions.onRetry(card) }) { Text("Retry") }
                IconButton(onClick = { actions.onDismiss(card) }) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
        }
    }
}

/** The file downloading now; "Downloads paused" while paused. */
fun homeDownloadsTitle(card: DownloadsCard.Active): String = when {
    card.paused -> "Downloads paused"
    else -> card.title?.let { "Downloading $it" } ?: "Downloading"
}

/** "3 of 14 · 1.2 GB of 9.8 GB · about 25 min left", or "Paused · 11 waiting". */
fun homeDownloadsText(card: DownloadsCard.Active): String {
    if (card.paused) return "Paused · ${card.waiting} waiting"
    val parts = mutableListOf(
        "${card.filesDone} of ${card.files}", "${app.granger.formatBytes(card.bytesDone)} of ${app.granger.formatBytes(card.bytes)}",
    )
    card.timeLeftMillis?.let { parts += "${app.granger.core.downloads.QueueText.timeLeft(it)} left" }
    return parts.joinToString(" · ")
}
