package app.granger.archive

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.OutlinedButton
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
import app.granger.core.archive.ArchiveShelf
import app.granger.sources.ItemStatus
import app.granger.sources.ItemStatusText

private const val DISCLAIMER = "Archive.org content is provided by the Internet Archive. Granger doesn't host or vouch for it."

/** Browse and search archive.org texts (public domain until the waiver), with an item sheet to Get, Borrow or View. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(viewModel: ArchiveViewModel, detailsUrl: (String) -> String, onBack: () -> Unit) {
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
    fun openInBrowser(identifier: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(detailsUrl(identifier))))
        } catch (e: ActivityNotFoundException) {
            // No browser installed; nothing more to do.
        }
    }

    var confirmRemove by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showWaiver by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Archive.org") },
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
                    placeholder = { Text("Search archive.org texts") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item(key = "tabs") {
                PrimaryTabRow(selectedTabIndex = ui.tab.ordinal) {
                    for (tab in ArchiveTab.entries) {
                        Tab(selected = ui.tab == tab, onClick = { viewModel.setTab(tab) }, text = { Text(tab.title) })
                    }
                }
            }
            item(key = "note") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        if (ui.filtered) "Showing public-domain texts only: published 1930 or earlier."
                        else "Showing everything archive.org serves openly. You're responsible for what you download.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (ui.filtered) OutlinedButton(onClick = { showWaiver = true }) { Text("Show everything") }
                }
            }
            if (ui.tab == ArchiveTab.BROWSE && ui.query.isBlank()) {
                item(key = "shelves") {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (shelf in ArchiveShelf.entries) {
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
                        when {
                            ui.tab == ArchiveTab.INSTALLED -> "Nothing downloaded from archive.org yet."
                            else -> "Nothing matches that search."
                        },
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(ui.rows, key = { "row-${it.identifier}" }) { row ->
                ArchiveResultRow(
                    row,
                    onOpen = { viewModel.openItem(row.identifier) },
                    onGet = {
                        beforeDownload()
                        viewModel.get(row.identifier)
                    },
                    onCancel = { viewModel.cancel(row.identifier) },
                    onRetry = { viewModel.retryDownload(row.identifier) },
                    onRemove = { confirmRemove = row.identifier to row.title },
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
            item(key = "disclaimer") {
                Text(
                    DISCLAIMER,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    ui.sheet?.let { sheet ->
        ArchiveItemSheet(
            sheet,
            onGet = {
                beforeDownload()
                viewModel.get(sheet.identifier)
            },
            onCancel = { viewModel.cancel(sheet.identifier) },
            onRetry = { viewModel.retryDownload(sheet.identifier) },
            onRemove = { confirmRemove = sheet.identifier to (sheet.item?.title?.ifBlank { null } ?: sheet.identifier) },
            onOpenInBrowser = { openInBrowser(sheet.identifier) },
            onDismiss = viewModel::closeItem,
        )
    }

    confirmRemove?.let { (identifier, title) ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove $title?") },
            text = { Text("This deletes the file. Your place and highlights are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(identifier)
                    confirmRemove = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Keep") } },
        )
    }

    if (showWaiver) {
        WaiverDialog(
            onAccept = {
                viewModel.acceptWaiver()
                showWaiver = false
            },
            onDismiss = { showWaiver = false },
        )
    }
}

@Composable
private fun ArchiveResultRow(
    row: ArchiveRow,
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
                offerLabel(row.preview)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                ItemStatusText(row.status)
            }
        },
        trailingContent = {
            when (row.status) {
                ItemStatus.NotInstalled -> if (row.preview is app.granger.core.archive.Offer.Download) {
                    TextButton(onClick = onGet) { Text("Get") }
                }
                is ItemStatus.Queued, is ItemStatus.Downloading -> TextButton(onClick = onCancel) { Text("Cancel") }
                is ItemStatus.Failed -> Row {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                ItemStatus.Installed, ItemStatus.UpdateAvailable -> TextButton(onClick = onRemove) { Text("Remove") }
                ItemStatus.NeedsMoreMemory -> Unit
            }
        },
    )
}
