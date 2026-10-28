package app.granger.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import app.granger.Features
import app.granger.R
import app.granger.audiobooks.ALL_AUDIOBOOKS
import app.granger.audiobooks.AUDIOBOOKS_TYPE_ID
import app.granger.core.library.CollectionRow
import app.granger.music.ALL_MUSIC
import app.granger.music.MUSIC_TYPE_ID

/** "Media" (media types, each expanding to its collections) and "Archive sources". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    packsFolder: String,
    /** Shown once as a snackbar, e.g. why the app didn't resume reading. */
    notice: String?,
    onOpenCollection: (collectionId: String) -> Unit,
    /** A Music collection, or "All music" (null). */
    onOpenMusic: (collectionId: String?) -> Unit,
    /** An Audiobooks collection's shelf, or "All audiobooks" (null). */
    onOpenAudiobooks: (collectionId: String?) -> Unit,
    onOpenSource: (SourceLink) -> Unit,
    onSearch: () -> Unit,
    onHistory: () -> Unit,
    onReadiness: () -> Unit,
    /** What's coming or stopped in Downloads; the icon shows a badge while it isn't 0. */
    downloadsBadge: Int = 0,
    onDownloads: () -> Unit = {},
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
    val importNotice by viewModel.notice.collectAsStateWithLifecycle()
    LaunchedEffect(importNotice) {
        val text = importNotice ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.dismissNotice()
    }
    var menuOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.import(uris)
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
                    IconButton(onClick = onDownloads) {
                        BadgedBox(badge = { if (downloadsBadge > 0) Badge { Text("$downloadsBadge") } }) {
                            Icon(painterResource(R.drawable.ic_download), contentDescription = "Downloads")
                        }
                    }
                    IconButton(onClick = onReadiness) { Icon(Icons.Filled.CheckCircle, contentDescription = "Readiness") }
                    if (Features.IMPORT) Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Import books…") },
                                onClick = { menuOpen = false; picker.launch(IMPORT_TYPES) },
                            )
                        }
                    }
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
                        if (Features.IMPORT) {
                            Text("Or import your own EPUB, PDF, TXT or Markdown books", style = MaterialTheme.typography.bodySmall)
                        }
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
                // Music opens its own tabs: "All music" first, then each collection scoped to its albums. Audiobooks
                // does the same with its shelf: "All audiobooks", then each collection scoped to its audiobooks.
                val music = id == MUSIC_TYPE_ID
                val audiobooks = id == AUDIOBOOKS_TYPE_ID
                if (open && (music || audiobooks)) {
                    item(key = if (music) "all-music" else "all-audiobooks") {
                        ListItem(
                            modifier = Modifier.clickable { if (music) onOpenMusic(null) else onOpenAudiobooks(null) }.padding(start = 16.dp),
                            headlineContent = { Text(if (music) ALL_MUSIC else ALL_AUDIOBOOKS) },
                            supportingContent = {
                                Text(countText(0, 0, shelf.allCollections.sumOf { it.albums.size }, shelf.allCollections.sumOf { it.audiobooks.size }))
                            },
                        )
                    }
                }
                // A folder (Kiwix) is one row; it opens the folder screen, which lists its children.
                if (open) for (row in shelf.collections) {
                    item(key = "collection:${row.collection.id}") {
                        ListItem(
                            modifier = Modifier
                                .clickable {
                                    when {
                                        row.isFolder -> onOpenCollection(row.collection.id)
                                        music -> onOpenMusic(row.collection.id)
                                        audiobooks -> onOpenAudiobooks(row.collection.id)
                                        else -> onOpenCollection(row.collection.id)
                                    }
                                }
                                .padding(start = 16.dp),
                            headlineContent = { Text(row.collection.name) },
                            supportingContent = { Text(rowCountText(row)) },
                        )
                    }
                }
                item(key = "divider:$id") { HorizontalDivider() }
            }
            item { SectionHeader("Archive sources") }
            for (group in loaded.sources) {
                item(key = "source-group:${group.title}") { GroupHeader(group.title) }
                for (source in group.links) {
                    item(key = "source:${source.name}") {
                        ListItem(
                            modifier = Modifier.clickable { onOpenSource(source) }.padding(start = 16.dp),
                            headlineContent = { Text(source.title) },
                        )
                    }
                }
            }
        }
    }
}

private val IMPORT_TYPES = arrayOf(
    "application/epub+zip", "application/pdf", "text/plain", "text/markdown", "text/x-markdown", "application/octet-stream",
)

/** What a collection row says it holds ("3 packs · 1 book"); a folder's is the total of its children. */
internal fun rowCountText(row: CollectionRow): String {
    val rows = row.withChildren
    return countText(rows.sumOf { it.packs.size }, rows.sumOf { it.books.size }, rows.sumOf { it.albums.size }, rows.sumOf { it.audiobooks.size })
}

private fun countText(packs: Int, books: Int, albums: Int = 0, audiobooks: Int = 0): String = listOfNotNull(
    if (packs > 0) (if (packs == 1) "1 pack" else "$packs packs") else null,
    if (books > 0) (if (books == 1) "1 book" else "$books books") else null,
    if (albums > 0) (if (albums == 1) "1 album" else "$albums albums") else null,
    if (audiobooks > 0) (if (audiobooks == 1) "1 audiobook" else "$audiobooks audiobooks") else null,
).joinToString(" · ")

/** The file's display name from the document provider, falling back to the last part of its URI. */
fun displayNameOf(context: Context, uri: Uri): String {
    val queried = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
    return queried?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
    )
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
