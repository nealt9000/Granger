package app.granger.sources

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import app.granger.core.catalog.Shelves
import app.granger.theme.GroupLabel
import app.granger.theme.GroupedRow
import app.granger.theme.GroupedRows
import app.granger.theme.OutlineChip
import app.granger.theme.SectionHeader
import kotlinx.coroutines.launch

/** Test tags on the Books screen. */
internal object BooksTags {
    const val SEARCH_FIELD = "books-search-field"
}

/**
 * Get more → Books (Plan KB2 Task 4.1, Decision 4), in Explore's "Your sources" style: a block per shelf (Literature,
 * Children's books, Manuals & references, Learning), each row a title, one meta line and a chevron (it opens a list:
 * Granger Classics, Project Gutenberg's subjects) or its action (a Kiwix book pack: Get, Open, a download's progress).
 * Under the shelves, Find a book: Search all books (the catalog's titles across every book source), Archive.org books
 * (a search of the Internet Archive) and Import your own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BooksScreen(
    viewModel: SourceViewModel,
    onBack: () -> Unit,
    /** A folded row's list: Granger's screen (its sets' Get all) or Project Gutenberg's subjects. */
    onOpenScope: (CatalogScope) -> Unit,
    onOpenPack: (packId: String) -> Unit,
    onOpenBook: (bookId: String, title: String) -> Unit,
    onArchive: () -> Unit,
    /** Import your own; null leaves it out. */
    onImport: (() -> Unit)? = null,
    /** A catalog book set's screen (Plan KB2 Task 3.2: African Storybook · English). */
    onOpenSet: (setId: String) -> Unit = {},
    /** Opens a book without keeping it (Plan O2 Task 8); null leaves Read out. */
    onReadNow: ((itemId: String, title: String) -> Unit)? = null,
    /** Whether a row is a catalog book that can be read without keeping. */
    canReadNow: (itemId: String) -> Boolean = { false },
) {
    LifecycleResumeEffect(Unit) {
        viewModel.refreshVolumes()
        onPauseOrDispose {}
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(ui.notice) {
        val notice = ui.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice.resolve(context))
        viewModel.dismissNotice()
    }
    // Ask once for notifications so download progress can show; downloads work either way.
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun beforeDownload() {
        if (askedForNotifications || Build.VERSION.SDK_INT < 33) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        askedForNotifications = true
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    var confirmRemove by remember { mutableStateOf<Pair<ItemRow, RemoveKind>?>(null) }
    val scope = rememberCoroutineScope()
    var searching by rememberSaveable { mutableStateOf(viewModel.searchText.isNotEmpty()) }
    fun stopSearching() {
        searching = false
        viewModel.setQuery("")
    }
    BackHandler(enabled = searching) { stopSearching() }

    @Composable
    fun ItemLine(row: ItemRow) {
        val scopeOpened = row.opensScope
        if (scopeOpened != null) {
            GroupedRow(row.title, row.meta?.resolve(), onClick = { onOpenScope(scopeOpened) })
            return
        }
        val setOpened = row.opensSet
        if (setOpened != null) {
            GroupedRow(row.title, row.meta?.resolve(), onClick = { onOpenSet(setOpened) })
            return
        }
        val open = { row.packId?.let(onOpenPack) ?: row.bookId?.let { onOpenBook(it, row.title) } ?: Unit }
        GroupedRow(
            title = row.title,
            meta = (row.meta ?: row.details).resolve(),
            onClick = if (row.opens) open else null,
            // Installed is said by Open; a download's progress, trouble or an update is said here.
            below = if (row.status == ItemStatus.Installed || row.status == ItemStatus.NotInstalled) null else {
                {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                        Column { ItemStatusText(row.status) }
                    }
                }
            },
            trailing = {
                // Read it now, saved for now, before choosing to keep it (Plan O2 Task 8).
                if (onReadNow != null && row.status == ItemStatus.NotInstalled && canReadNow(row.id) && app.granger.online.isOnlineNow()) {
                    androidx.compose.material3.TextButton(onClick = { onReadNow(row.id, row.title) }, modifier = Modifier.testTag("read_now_${row.id}")) {
                        Text(stringResource(R.string.sources_read_now))
                    }
                }
                RowActions(
                    row,
                    onGet = {
                        beforeDownload()
                        viewModel.get(row.id)
                    },
                    onCancel = { viewModel.cancel(row.id) },
                    onRetry = { viewModel.retry(row.id) },
                    onRemove = { scope.launch { confirmRemove = row to viewModel.removeKind(row.id) } },
                    onOpen = open,
                    preview = false,
                )
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sources_get_books)) },
                navigationIcon = {
                    IconButton(onClick = { if (searching) stopSearching() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshCatalog, enabled = !ui.refreshing) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.sources_check_updates))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (searching) {
                item(key = "search") {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    OutlinedTextField(
                        value = viewModel.searchText,
                        onValueChange = viewModel::setQuery,
                        placeholder = { Text(stringResource(R.string.sources_search_all_books)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = ::stopSearching) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close)) }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus).testTag(BooksTags.SEARCH_FIELD),
                    )
                }
            } else {
                storageSummary(ui)?.let { summary ->
                    item(key = "storage") {
                        Text(
                            summary.resolve(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                if (ui.languageChip) {
                    // Plan L3: the shelves show the library's languages; this shows every language's books.
                    item(key = "languages") {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            OutlineChip(stringResource(R.string.sources_all_languages), ui.allLanguages, onClick = { viewModel.setAllLanguages(!ui.allLanguages) })
                        }
                    }
                }
            }
            ui.catalogProblem?.let { problem ->
                item(key = "problem") { Text(problem.resolve(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            }
            if (ui.loading) {
                item(key = "loading") { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() } }
            }
            if (searching) {
                val results = ui.sections.firstOrNull { it.title == SourceViewModel.BOOK_RESULTS }
                if (results != null) {
                    item(key = "results-label") { GroupLabel(results.heading.resolve()) }
                    item(key = "results") { GroupedRows(results.rows.map { r -> @Composable { ItemLine(r) } }) }
                } else if (ui.query.isNotBlank() && !ui.loading) {
                    item(key = "no-match") {
                        Text(stringResource(R.string.sources_no_match), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    }
                }
            } else {
                for (section in ui.sections) {
                    val presets = if (section.title == Shelves.LITERATURE) ui.presets else emptyList()
                    if (section.rows.isEmpty() && presets.isEmpty()) continue
                    item(key = "label-${section.title}") { GroupLabel(section.heading.resolve()) }
                    item(key = "block-${section.title}") {
                        GroupedRows(
                            presets.map { p ->
                                @Composable { GroupedRow(BookShelves.presetName(p), BookShelves.presetMeta(p).resolve(), onClick = { onOpenScope(CatalogScope.GRANGER) }) }
                            } + section.rows.map { r -> @Composable { ItemLine(r) } },
                        )
                    }
                }
                item(key = "find-header") { SectionHeader(stringResource(R.string.sources_books_find)) }
                item(key = "find") {
                    GroupedRows(
                        listOfNotNull(
                            @Composable {
                                GroupedRow(
                                    stringResource(R.string.sources_search_all_books), stringResource(R.string.sources_search_all_books_sub),
                                    onClick = { searching = true },
                                )
                            },
                            @Composable {
                                GroupedRow(
                                    stringResource(R.string.sources_link_archive_books), stringResource(R.string.sources_link_archive_books_sub),
                                    onClick = onArchive,
                                )
                            },
                            onImport?.let { import ->
                                @Composable {
                                    GroupedRow(stringResource(R.string.sources_import_own), stringResource(R.string.sources_import_own_sub), onClick = import)
                                }
                            },
                        ),
                    )
                }
            }
            item(key = "foot") { Spacer(Modifier.height(24.dp)) }
        }
    }

    ui.sizeCheck?.let { check ->
        SizeCheckDialog(
            check,
            onGet = { id ->
                beforeDownload()
                viewModel.confirmGet(id)
            },
            onDismiss = viewModel::dismissSizeCheck,
        )
    }
    confirmRemove?.let { (row, kind) ->
        RemoveDialog(row, kind, onRemove = { viewModel.remove(row.id) }, onDismiss = { confirmRemove = null })
    }
}
