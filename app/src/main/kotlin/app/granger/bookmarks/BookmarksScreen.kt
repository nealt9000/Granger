package app.granger.bookmarks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.core.library.Bookmark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(viewModel: BookmarksViewModel, onOpen: (Bookmark) -> Unit, onBack: () -> Unit) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bookmarks") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        val list = rows
        when {
            list == null -> Unit
            list.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize().padding(24.dp), Alignment.Center) {
                Text("No bookmarks yet. Tap the star while reading to add one.")
            }
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(list, key = { "${it.bookmark.packId}/${it.bookmark.path}" }) { row ->
                    ListItem(
                        modifier = if (row.available) Modifier.clickable { onOpen(row.bookmark) } else Modifier,
                        headlineContent = { Text(row.bookmark.title) },
                        supportingContent = {
                            Text(
                                if (row.available) row.packTitle else "${row.packTitle} · not available",
                                color = if (row.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { viewModel.remove(row.bookmark) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove bookmark")
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
