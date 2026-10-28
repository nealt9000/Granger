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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
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

/** What the phone holds to read, by kind (Plan U3b Decision 4; music and audiobooks are Listen's, U2); getting more is in Get more, what's coming in Downloads. */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    packsFolder: String,
    /** Shown once as a snackbar, e.g. why the app didn't resume reading. */
    notice: String?,
    onOpenCollection: (collectionId: String) -> Unit,
    onSearch: () -> Unit,
    onReadiness: () -> Unit,
    /** What's coming or stopped in Downloads; the icon shows a badge while it isn't 0. */
    downloadsBadge: Int = 0,
    onDownloads: () -> Unit = {},
    /** Where sources live (Plan U3b Decision 2). */
    onGetMore: () -> Unit = {},
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.import(uris)
    }
    LibraryContent(
        ui, packsFolder, snackbar, onOpenCollection, onSearch, onReadiness,
        downloadsBadge, onDownloads, onGetMore, onImport = { picker.launch(IMPORT_TYPES) },
    )
}

/**
 * The header (Search, Downloads with its badge, Get more, and Readiness and Import books… in the overflow) and one row
 * per kind, each opening to its collections. Null [ui] is still loading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryContent(
    ui: LibraryUi?,
    packsFolder: String,
    snackbar: SnackbarHostState,
    onOpenCollection: (collectionId: String) -> Unit,
    onSearch: () -> Unit,
    onReadiness: () -> Unit,
    downloadsBadge: Int,
    onDownloads: () -> Unit,
    onGetMore: () -> Unit,
    onImport: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Kind row ids the user has collapsed; all start expanded.
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                // One line: on a narrow phone the actions leave it short, and a word broken in two reads worse.
                title = { Text("Library", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                    IconButton(onClick = onDownloads) {
                        BadgedBox(badge = { if (downloadsBadge > 0) Badge { Text("$downloadsBadge") } }) {
                            Icon(painterResource(R.drawable.ic_download), contentDescription = "Downloads")
                        }
                    }
                    IconButton(onClick = onGetMore) { Icon(Icons.Filled.Add, contentDescription = "Get more") }
                    // Readiness is in the overflow so the title fits at 360 dp (Plan U3b Decision 4).
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Readiness") },
                                onClick = { menuOpen = false; onReadiness() },
                            )
                            if (Features.IMPORT) {
                                DropdownMenuItem(
                                    text = { Text("Import books…") },
                                    onClick = { menuOpen = false; onImport() },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        val loaded = ui ?: return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (loaded.emptyLibrary) {
                item {
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("No packs installed yet.", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onGetMore) { Text("Get packs and books →") }
                        Text("Or copy .zim files into $packsFolder", style = MaterialTheme.typography.bodySmall)
                        if (Features.IMPORT) {
                            Text("Or import your own EPUB, PDF, TXT or Markdown books", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            for (kind in loaded.kinds) {
                kindRows(kind, open = kind.id !in collapsed, onToggle = { open ->
                    collapsed = if (open) collapsed + kind.id else collapsed - kind.id
                }, onOpenCollection = onOpenCollection)
            }
        }
    }
}

/**
 * One kind's row (a tap collapses or expands it; [open] is whether it's expanded), then, while open, its collections
 * and a divider. Music opens its own tabs: "All music" first, then each collection scoped to its albums. Audiobooks
 * does the same with its shelf: "All audiobooks", then each collection scoped to its audiobooks. The Library and
 * Listen share it (Plan U2 Decision 4).
 */
internal fun LazyListScope.kindRows(
    kind: KindRow,
    open: Boolean,
    onToggle: (open: Boolean) -> Unit,
    onOpenCollection: (collectionId: String) -> Unit,
    /** A Music collection, or "All music" (null). */
    onOpenMusic: (collectionId: String?) -> Unit = {},
    /** An Audiobooks collection's shelf, or "All audiobooks" (null). */
    onOpenAudiobooks: (collectionId: String?) -> Unit = {},
) {
    val id = kind.id
    item(key = "kind:$id") {
        ListItem(
            modifier = Modifier.clickable { onToggle(open) },
            headlineContent = { Text(kind.title, style = MaterialTheme.typography.titleMedium) },
            trailingContent = {
                Icon(
                    if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (open) "Collapse" else "Expand",
                )
            },
        )
    }
    val music = kind.mediaTypeId == MUSIC_TYPE_ID
    val audiobooks = kind.mediaTypeId == AUDIOBOOKS_TYPE_ID
    if (open && (music || audiobooks)) {
        item(key = if (music) "all-music" else "all-audiobooks") {
            ListItem(
                modifier = Modifier.clickable { if (music) onOpenMusic(null) else onOpenAudiobooks(null) }.padding(start = 16.dp),
                headlineContent = { Text(if (music) ALL_MUSIC else ALL_AUDIOBOOKS) },
                supportingContent = {
                    Text(countText(0, 0, kind.allCollections.sumOf { it.albums.size }, kind.allCollections.sumOf { it.audiobooks.size }))
                },
            )
        }
    }
    // A folder (Kiwix) is one row; it opens the folder screen, which lists its children.
    if (open) for (row in kind.collections) {
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
