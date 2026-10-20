package app.granger.librivox

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.core.archive.LibriVoxShelf
import app.granger.sources.ItemStatus
import app.granger.sources.ItemStatusText

private const val FOOTER = "LibriVox recordings are in the public domain. Files are served by the Internet Archive."

/** Browse and search LibriVox, with a book sheet to Get its chapters (64 kbps) or open its LibriVox page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibriVoxScreen(viewModel: LibriVoxViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.notice) {
        val notice = ui.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice)
        viewModel.dismissNotice()
    }

    // Ask once for notifications so download progress can show; downloads work either way.
    val context = LocalContext.current
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun beforeDownload() {
        if (askedForNotifications || Build.VERSION.SDK_INT < 33) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        askedForNotifications = true
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    fun openPage(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            // No browser installed; nothing more to do.
        }
    }

    var confirmRemove by remember { mutableStateOf<Pair<String, String>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LibriVox") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "search") {
                OutlinedTextField(
                    value = viewModel.searchText,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text("Search LibriVox by title or author") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item(key = "tabs") {
                PrimaryTabRow(selectedTabIndex = ui.tab.ordinal) {
                    for (tab in LibriVoxTab.entries) {
                        Tab(selected = ui.tab == tab, onClick = { viewModel.setTab(tab) }, text = { Text(tab.title) })
                    }
                }
            }
            if (ui.tab == LibriVoxTab.BROWSE && ui.query.isBlank()) {
                item(key = "shelves") {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (shelf in LibriVoxShelf.entries) {
                            FilterChip(
                                selected = ui.shelf == shelf,
                                onClick = { viewModel.selectShelf(shelf) },
                                label = { Text(shelf.title) },
                            )
                        }
                    }
                }
            }
            ui.error?.let { problem ->
                item(key = "problem") {
                    Column(Modifier.padding(16.dp)) {
                        Text(problem, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::retry) { Text("Retry") }
                    }
                }
            }
            if (!ui.loading && ui.error == null && ui.rows.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (ui.tab == LibriVoxTab.INSTALLED) "Nothing downloaded from LibriVox yet." else "Nothing matches that search.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(ui.rows, key = { "row-${it.id}" }) { row ->
                BookRow(
                    row,
                    onOpen = { viewModel.openBook(row.id) },
                    onGet = {
                        beforeDownload()
                        viewModel.get(row.id)
                    },
                    onCancel = { viewModel.cancel(row.id) },
                    onRetry = { viewModel.retryDownload(row.id) },
                    onRemove = { confirmRemove = row.id to row.title },
                )
                HorizontalDivider()
            }
            if (ui.loading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
                }
            }
            if (ui.canLoadMore) {
                item(key = "more") {
                    TextButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text("Load more") }
                }
            }
            item(key = "footer") {
                Text(
                    FOOTER,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    ui.sheet?.let { sheet ->
        val rowTitle = ui.rows.firstOrNull { it.id == sheet.id }?.title
        LibriVoxBookSheet(
            sheet,
            fallbackTitle = rowTitle,
            onGet = {
                beforeDownload()
                viewModel.get(sheet.id)
            },
            onCancel = { viewModel.cancel(sheet.id) },
            onRetry = { viewModel.retryDownload(sheet.id) },
            onRemove = {
                confirmRemove = sheet.id to (sheet.book?.title?.let(::decodeEntities)?.ifBlank { null } ?: rowTitle ?: "this book")
            },
            onOpenPage = ::openPage,
            onDismiss = viewModel::closeBook,
        )
    }

    confirmRemove?.let { (id, title) ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove $title?") },
            text = { Text("This deletes its files. Your place and bookmarks are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(id)
                    confirmRemove = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun BookRow(
    row: LibriVoxRow,
    onOpen: () -> Unit,
    onGet: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = { Text(row.title) },
        supportingContent = {
            Column {
                if (row.details.isNotBlank()) Text(row.details, style = MaterialTheme.typography.bodySmall)
                ItemStatusText(row.status)
            }
        },
        trailingContent = {
            // A row doesn't know whether the book's files are all on archive.org: Get says why when they aren't.
            when (row.status) {
                ItemStatus.NotInstalled -> TextButton(onClick = onGet) { Text("Get") }
                is ItemStatus.Queued, is ItemStatus.Downloading -> TextButton(onClick = onCancel) { Text("Cancel") }
                is ItemStatus.Failed -> Row {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                ItemStatus.Installed, ItemStatus.UpdateAvailable -> TextButton(onClick = onRemove) { Text("Remove") }
                is ItemStatus.Partial -> Row {
                    TextButton(onClick = onRemove) { Text("Remove") }
                    TextButton(onClick = onGet) { Text("Get the rest") }
                }
                ItemStatus.NeedsMoreMemory, is ItemStatus.InLibrary -> Unit
            }
        },
    )
}
