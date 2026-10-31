package app.granger.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.Features
import app.granger.R
import app.granger.UiText
import app.granger.audiobooks.AUDIOBOOKS_TYPE_ID
import app.granger.core.library.CollectionRow
import app.granger.core.library.Filing
import app.granger.core.library.GutenbergNames
import app.granger.music.MUSIC_TYPE_ID
import app.granger.sources.GetMoreLink

/**
 * The books the phone holds, by kind (Plan U3b Decision 4; music and audiobooks are Listen's, U2; packs and maps are
 * Browse's, U3c); getting more is in Get more, what's coming in Downloads.
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    /** Shown once as a snackbar, e.g. why the app didn't resume reading. */
    notice: UiText?,
    onOpenCollection: (collectionId: String) -> Unit,
    onSearch: () -> Unit,
    onReadiness: () -> Unit,
    /** What's coming or stopped in Downloads; the icon shows a badge while it isn't 0. */
    downloadsBadge: Int = 0,
    onDownloads: () -> Unit = {},
    /** Where sources live (Plan U3b Decision 2). */
    onGetMore: () -> Unit = {},
    /** A book source the empty Library links to. */
    onOpenSource: (GetMoreLink) -> Unit = {},
    /** Get books' Import your own asked for the file picker; [onImportAsked] says it has opened. */
    importAsked: Boolean = false,
    onImportAsked: () -> Unit = {},
) {
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    var noticeShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(notice) {
        if (notice != null && !noticeShown) {
            noticeShown = true
            snackbar.showSnackbar(notice.resolve(context))
        }
    }
    val importNotice by viewModel.notice.collectAsStateWithLifecycle()
    LaunchedEffect(importNotice) {
        val text = importNotice ?: return@LaunchedEffect
        snackbar.showSnackbar(text.resolve(context))
        viewModel.dismissNotice()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.import(uris)
    }
    LaunchedEffect(importAsked) {
        if (!importAsked) return@LaunchedEffect
        onImportAsked()
        picker.launch(IMPORT_TYPES)
    }
    LibraryContent(
        ui, snackbar, onOpenCollection, onSearch, onReadiness,
        downloadsBadge, onDownloads, onGetMore, onImport = { picker.launch(IMPORT_TYPES) }, onOpenSource = onOpenSource,
    )
}

/**
 * The header (Search, Downloads with its badge, Get more, and Readiness and Import books… in the overflow) and one row
 * per kind, each opening to its collections. With no books, "No books yet" and the [BOOK_SOURCES]. Null [ui] is still
 * loading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryContent(
    ui: LibraryUi?,
    snackbar: SnackbarHostState,
    onOpenCollection: (collectionId: String) -> Unit,
    onSearch: () -> Unit,
    onReadiness: () -> Unit,
    downloadsBadge: Int,
    onDownloads: () -> Unit,
    onGetMore: () -> Unit,
    onImport: () -> Unit,
    onOpenSource: (GetMoreLink) -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Kind row ids the user has collapsed; all start expanded.
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                // One line: on a narrow phone the actions leave it short, and a word broken in two reads worse. It
                // shrinks a little rather than cut "Biblioteca" (Plan L2 Task 4).
                title = { app.granger.theme.FitText(stringResource(R.string.library_title), minSize = 16.sp) },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.common_search)) }
                    IconButton(onClick = onDownloads) {
                        BadgedBox(badge = { if (downloadsBadge > 0) Badge { Text("$downloadsBadge") } }) {
                            Icon(painterResource(R.drawable.ic_download), contentDescription = stringResource(R.string.library_downloads))
                        }
                    }
                    IconButton(onClick = onGetMore) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.sources_get_more)) }
                    // Readiness is in the overflow so the title fits at 360 dp (Plan U3b Decision 4).
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more)) }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_readiness)) },
                                onClick = { menuOpen = false; onReadiness() },
                            )
                            if (Features.IMPORT) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.library_import_books)) },
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
                item(key = "empty") {
                    Text(
                        stringResource(R.string.library_no_books),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                for (source in BOOK_SOURCES) {
                    item(key = "source:$source") {
                        ListItem(
                            modifier = Modifier.clickable { onOpenSource(source) },
                            headlineContent = { Text(stringResource(source.title)) },
                            supportingContent = { Text(stringResource(source.subtitle)) },
                        )
                    }
                }
                if (Features.IMPORT) {
                    item(key = "import") {
                        TextButton(onClick = onImport, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(stringResource(R.string.library_import_own))
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
            headlineContent = { Text(kind.title.resolve(), style = MaterialTheme.typography.titleMedium) },
            trailingContent = {
                Icon(
                    if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(if (open) R.string.library_collapse else R.string.library_expand),
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
                headlineContent = { Text(stringResource(if (music) R.string.music_all else R.string.audiobooks_all)) },
                supportingContent = {
                    Text(countText(0, 0, kind.allCollections.sumOf { it.albums.size }, kind.allCollections.sumOf { it.audiobooks.size }).resolve())
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
                headlineContent = { Text(row.collection.shownName().resolve()) },
                supportingContent = { Text(rowCountText(row).resolve()) },
            )
        }
    }
    item(key = "divider:$id") { HorizontalDivider() }
}


/** The book sources an empty Library links to: Get more's own rows (Plan U3c Decision 2). */
internal val BOOK_SOURCES = listOf(GetMoreLink.GRANGER_CLASSICS, GetMoreLink.GUTENBERG, GetMoreLink.KIWIX_BOOKS, GetMoreLink.ARCHIVE_ORG_BOOKS)

private val IMPORT_TYPES = arrayOf(
    "application/epub+zip", "application/pdf", "text/plain", "text/markdown", "text/x-markdown", "application/octet-stream",
)

/** What a collection row says it holds ("3 packs · 1 book"); a folder's is the total of its children. */
internal fun rowCountText(row: CollectionRow): UiText {
    val rows = row.withChildren
    val counts = countText(rows.sumOf { it.packs.size }, rows.sumOf { it.books.size }, rows.sumOf { it.albums.size }, rows.sumOf { it.audiobooks.size })
    // A book pack's collection is called by its subject or pack; the line under it says whose (Plan GB Decision 2, Plan
    // KB Decision 7), and, as the collection's header does, how many of its books are searchable in full once any are.
    val key = row.collection.defaultKey
    if (!Filing.isBookPackCollection(key)) return counts
    val searchable = rows.sumOf { r -> r.books.count { it.searchableInFull } }
    val books = rows.sumOf { it.books.size }
    val header = GutenbergHeader(pack = null, books = books, searchable = searchable)
    if (Filing.isKiwixBooksCollection(key)) {
        // "Kiwix library · 17 documents · 3 searchable in full" (Plan KB Decision 7).
        val kiwix = header.copy(kind = HeaderKind.KIWIX_LIBRARY)
        return dotted(listOfNotNull(if (books > 0) kiwix.provenance else kiwix.provider, kiwix.searchableText.takeIf { searchable > 0 }))
    }
    return dotted(listOfNotNull(header.provider, counts.takeIf { it != EMPTY }, header.searchableText.takeIf { searchable > 0 }))
}

private val EMPTY = UiText.Raw("")

/** [parts] in a line, a dot between each. */
private fun dotted(parts: List<UiText>): UiText =
    parts.drop(1).fold(parts.firstOrNull() ?: EMPTY) { line, part -> UiText.Res(R.string.library_dotted, line, part) }

private fun countText(packs: Int, books: Int, albums: Int = 0, audiobooks: Int = 0): UiText = dotted(
    listOfNotNull(
        if (packs > 0) UiText.Plural(R.plurals.sources_packs, packs, packs) else null,
        if (books > 0) UiText.Plural(R.plurals.common_books, books, books) else null,
        if (albums > 0) UiText.Plural(R.plurals.common_albums, albums, albums) else null,
        if (audiobooks > 0) UiText.Plural(R.plurals.library_audiobooks, audiobooks, audiobooks) else null,
    ),
)

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
