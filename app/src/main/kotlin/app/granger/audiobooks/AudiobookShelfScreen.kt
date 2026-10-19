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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import app.granger.shell.rememberRailCover
import app.granger.theme.GrangerColors

internal const val UNKNOWN_AUTHOR = "Unknown author"

/** A square cover, or headphones on a plain tile when there's none. */
@Composable
fun AudiobookCover(path: String?, modifier: Modifier = Modifier) {
    val cover = rememberRailCover(path, 180.dp)
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(GrangerColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                painterResource(R.drawable.ic_headphones), contentDescription = null, tint = GrangerColors.OnSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

/** Voice-style shelf: covers with progress, part-way books first by last played, then unplayed, then finished. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookShelfScreen(vm: AudiobooksViewModel, onBook: (audiobookId: String) -> Unit, onBack: () -> Unit) {
    val loaded by vm.shelf.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loaded?.title.orEmpty()) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        val ui = loaded ?: return@Scaffold
        if (ui.books.isEmpty()) {
            Text(
                "No audiobooks here yet", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(padding).padding(24.dp),
            )
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(ui.books, key = { it.book.id }) { item -> ShelfCell(item) { onBook(item.book.id) } }
        }
    }
}

@Composable
private fun ShelfCell(item: ShelfBook, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AudiobookCover(item.book.coverPath, Modifier.fillMaxWidth().alpha(if (item.missing) 0.4f else 1f))
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
