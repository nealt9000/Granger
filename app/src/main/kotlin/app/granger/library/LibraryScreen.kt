package app.granger.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import app.granger.sources.CatalogScope

/** "Media" (media types, each expanding to its collections) and "Archive sources". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    packsFolder: String,
    /** Shown once as a snackbar, e.g. why the app didn't resume reading. */
    notice: String?,
    onOpenCollection: (collectionId: String) -> Unit,
    onOpenSource: (CatalogScope) -> Unit,
    onSearch: () -> Unit,
    onHistory: () -> Unit,
    onReadiness: () -> Unit,
) {
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var noticeShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(notice) {
        if (notice != null && !noticeShown) {
            noticeShown = true
            snackbar.showSnackbar(notice)
        }
    }
    // Media type ids the user has collapsed; all start expanded.
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                    IconButton(onClick = onHistory) { Icon(painterResource(R.drawable.ic_history), contentDescription = "History") }
                    IconButton(onClick = onReadiness) { Icon(Icons.Filled.CheckCircle, contentDescription = "Readiness") }
                },
            )
        },
    ) { padding ->
        val loaded = ui ?: return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { SectionHeader("Media") }
            if (loaded.emptyLibrary) {
                item {
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("No packs installed yet.", style = MaterialTheme.typography.titleMedium)
                        Text("Get packs from Granger or Kiwix", style = MaterialTheme.typography.bodyMedium)
                        Text("Or copy .zim files into $packsFolder", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            for (shelf in loaded.media) {
                val id = shelf.mediaType.id
                val open = id !in collapsed
                item(key = "type:$id") {
                    ListItem(
                        modifier = Modifier.clickable { collapsed = if (open) collapsed + id else collapsed - id },
                        headlineContent = { Text(shelf.mediaType.name, style = MaterialTheme.typography.titleMedium) },
                        trailingContent = {
                            Icon(
                                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (open) "Collapse" else "Expand",
                            )
                        },
                    )
                }
                if (open) for (row in shelf.collections) {
                    item(key = "collection:${row.collection.id}") {
                        val n = row.packs.size
                        ListItem(
                            modifier = Modifier.clickable { onOpenCollection(row.collection.id) }.padding(start = 16.dp),
                            headlineContent = { Text(row.collection.name) },
                            supportingContent = { Text(if (n == 1) "1 pack" else "$n packs") },
                        )
                    }
                }
                item(key = "divider:$id") { HorizontalDivider() }
            }
            item { SectionHeader("Archive sources") }
            for (source in loaded.sources) {
                item(key = "source:${source.name}") {
                    ListItem(
                        modifier = Modifier.clickable { onOpenSource(source) },
                        headlineContent = { Text(source.title) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
