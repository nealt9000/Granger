package app.granger.library

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    packsFolder: String,
    onOpenPack: (packId: String) -> Unit,
    onSearch: () -> Unit,
    onBookmarks: () -> Unit,
) {
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val packs by viewModel.packs.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                    IconButton(onClick = onBookmarks) { Icon(Icons.Filled.Star, contentDescription = "Bookmarks") }
                },
            )
        },
    ) { padding ->
        val list = packs
        when {
            list == null -> Unit
            list.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize().padding(24.dp), Alignment.Center) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No packs installed yet.", style = MaterialTheme.typography.titleMedium)
                    Text("Copy .zim files into $packsFolder", style = MaterialTheme.typography.bodyMedium)
                }
            }
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(list, key = { it.id }) { pack ->
                    PackRow(pack, onClick = { onOpenPack(pack.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PackRow(pack: Pack, onClick: () -> Unit) {
    val context = LocalContext.current
    val details = listOf(pack.language, pack.date, Formatter.formatShortFileSize(context, pack.sizeBytes))
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    val problem = when (pack.status) {
        PackStatus.OK -> null
        PackStatus.MISSING -> "File not found. Reinsert the SD card or download the pack again."
        PackStatus.CORRUPT -> "This file is damaged and can't be opened."
    }
    ListItem(
        modifier = if (pack.status == PackStatus.OK) Modifier.clickable(onClick = onClick) else Modifier,
        headlineContent = { Text(pack.title) },
        supportingContent = {
            Column {
                Text(details)
                if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error)
            }
        },
    )
}
