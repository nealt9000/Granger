package app.granger.audiobooks

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.filled.Check
import app.granger.library.shelf.ShelfCoverSize
import app.granger.library.shelf.ShelfGrid
import app.granger.library.shelf.ShelfHeader
import app.granger.library.shelf.ShelfList
import app.granger.library.shelf.ShelfRow
import app.granger.library.shelf.ShelfView
import app.granger.theme.readout
import kotlin.math.roundToInt
import app.granger.library.SourceLink
import app.granger.music.EmptyMedia
import app.granger.shell.rememberRailCover
import app.granger.theme.MediaKind
import app.granger.theme.MediaTile

internal const val UNKNOWN_AUTHOR = "Unknown author"

/** A square cover, or the themed tile with [title] and headphones when there's none (Plan U3c Decision 6). */
@Composable
fun AudiobookCover(path: String?, title: String, modifier: Modifier = Modifier) {
    val cover = rememberRailCover(path, 180.dp)
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        if (cover != null) {
            Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            MediaTile(title, MediaKind.AUDIOBOOK)
        }
    }
}

/**
 * The audiobook shelf (Plan LV): a Continue row of the books part-way through, then every book as an A–Z list (or the
 * cover grid) by Title, Author or Recent. A missing book is dimmed in both views; a finished one is ticked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookShelfScreen(
    vm: AudiobooksViewModel,
    onBook: (audiobookId: String) -> Unit,
    onBack: () -> Unit,
    /** An empty shelf's links: LibriVox and archive.org, and Settings → Music & audiobooks. Null: not offered. */
    onOpenSource: ((SourceLink) -> Unit)? = null,
    onMediaSettings: (() -> Unit)? = null,
) {
    val loaded by vm.shelf.collectAsStateWithLifecycle()
    val view by vm.view.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loaded?.title.orEmpty()) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        val ui = loaded ?: return@Scaffold
        if (ui.sections.isEmpty) {
            Box(Modifier.padding(padding)) {
                EmptyMedia(
                    "No audiobooks yet",
                    links = listOf(SourceLink.LIBRIVOX, SourceLink.ARCHIVE_ORG_SPOKEN),
                    onOpenSource = onOpenSource,
                    onMediaSettings = onMediaSettings,
                )
            }
            return@Scaffold
        }
        // The grid already pads its edges by 12 dp; the row lines up with the list's 16 either way.
        val continueRow: @Composable (inset: Dp) -> Unit = { inset -> ContinueRow(ui.continueRow, inset, onBook) }
        val header: @Composable () -> Unit = {
            ShelfHeader(ui.sort, AudiobookSort.entries, { it.label }, vm::setSort, view = view, onToggleView = vm::toggleView)
        }
        if (view == ShelfView.LIST) {
            ShelfList(
                ui.sections,
                Modifier.padding(padding).fillMaxSize(),
                contentType = "audiobook",
                header = {
                    if (ui.continueRow.isNotEmpty()) item(key = "continue", contentType = "continue") { continueRow(16.dp) }
                    item(key = "header", contentType = "header") { header() }
                },
            ) { item -> AudiobookRow(item) { onBook(item.book.id) } }
        } else {
            ShelfGrid(
                ui.sections,
                cellMin = 150.dp,
                modifier = Modifier.padding(padding).fillMaxSize(),
                // 12 dp edges and gaps, so two 150 dp columns still fit beside the rail and the letter strip on a 411 dp phone.
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
                verticalSpacing = 20.dp,
                headerIndent = 4.dp,
                contentType = "audiobook",
                header = {
                    if (ui.continueRow.isNotEmpty()) item(key = "continue", span = { GridItemSpan(maxLineSpan) }, contentType = "continue") { continueRow(4.dp) }
                    item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") { header() }
                },
            ) { item -> ShelfCell(item) { onBook(item.book.id) } }
        }
    }
}

/** "Continue": the books part-way through, last played first (Plan LV Decision 7). */
@Composable
private fun ContinueRow(books: List<ShelfBook>, inset: Dp, onBook: (String) -> Unit) {
    Column(Modifier.padding(top = 8.dp, bottom = 8.dp)) {
        Text(
            "Continue",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = inset, bottom = 8.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = inset), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(books, key = { it.book.id }) { item -> ShelfCell(item, Modifier.width(120.dp)) { onBook(item.book.id) } }
        }
    }
}

/** A book in the list: its cover, title, author; how far through once started, a tick once finished. */
@Composable
private fun AudiobookRow(item: ShelfBook, onClick: () -> Unit) {
    ShelfRow(
        title = item.book.title,
        subtitle = item.book.author.ifBlank { UNKNOWN_AUTHOR },
        cover = { AudiobookCover(item.book.coverPath, item.book.title, Modifier.size(ShelfCoverSize)) },
        onClick = onClick,
        dimmed = item.missing,
        detail = if (item.missing) {
            { Text("Missing", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        } else {
            null
        },
        trailing = when {
            item.missing -> null
            item.listening == Listening.FINISHED -> {
                {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text("Finished", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item.listening == Listening.IN_PROGRESS -> {
                {
                    Text(
                        "${(item.fraction * 100).roundToInt().coerceIn(1, 99)}%",
                        style = MaterialTheme.typography.labelMedium.readout, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> null
        },
    )
}

@Composable
private fun ShelfCell(item: ShelfBook, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AudiobookCover(item.book.coverPath, item.book.title, Modifier.fillMaxWidth().alpha(if (item.missing) 0.4f else 1f))
        LinearProgressIndicator(progress = { item.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
        Text(item.book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            item.book.author.ifBlank { UNKNOWN_AUTHOR }, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        val status = when {
            item.missing -> "Missing"
            item.listening == Listening.FINISHED -> "Finished"
            else -> null
        }
        if (status != null) {
            Text(
                status, style = MaterialTheme.typography.labelSmall,
                color = if (item.missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }
}
