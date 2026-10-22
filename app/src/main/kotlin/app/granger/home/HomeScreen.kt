package app.granger.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.shell.RailEntry
import app.granger.shell.rememberRailCover

/** [onOpenTab] shows a reading tab; [onPlay] plays a rail item again. */
@Composable
fun HomeScreen(viewModel: HomeViewModel, onReadiness: () -> Unit, onOpenTab: (Long) -> Unit, onPlay: (RailEntry.Play) -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    HomeContent(ui, onReadiness, onOpenTab, onPlay)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(ui: HomeUi, onReadiness: () -> Unit, onOpenTab: (Long) -> Unit, onPlay: (RailEntry.Play) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Home") }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("home-list")) {
            item(key = "strip") { ReadinessStrip(ui.strip, onReadiness) }
            if (ui.continueCards.isNotEmpty()) {
                item(key = "continue") { Heading("Continue") }
                item(key = "continue-row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.testTag("continue-row"),
                    ) {
                        items(ui.continueCards, key = { it.key }) { entry ->
                            when (entry) {
                                is RailEntry.Tab -> TabCard(entry) { onOpenTab(entry.tab.tabId) }
                                is RailEntry.Play -> PlayCard(entry) { onPlay(entry) }
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
    )
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
