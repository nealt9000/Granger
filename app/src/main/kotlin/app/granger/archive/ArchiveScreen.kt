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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import app.granger.core.archive.ArchiveKind
import app.granger.core.archive.ArchiveShelf
import app.granger.core.archive.AudioOffer
import app.granger.core.archive.Offer
import app.granger.sources.ItemStatus
import app.granger.sources.ItemStatusText

/** The filter note for [ui]'s view: public-domain texts, public-domain recordings, or the spoken-word note before the waiver. */
@Composable
internal fun filterNote(ui: ArchiveUi): String = when {
    !ui.filtered -> stringResource(R.string.archive_unfiltered_note)
    ui.waiverNeeded -> stringResource(R.string.archive_spoken_word_note)
    ui.kind == ArchiveKind.TEXTS -> stringResource(R.string.archive_texts_note)
    else -> stringResource(R.string.archive_recordings_note, ui.filterYear)
}

/** The search box's placeholder for [kind]'s view. */
@Composable
private fun searchPlaceholder(kind: ArchiveKind): String = stringResource(
    when (kind) {
        ArchiveKind.TEXTS -> R.string.archive_search_texts
        ArchiveKind.SPOKEN_WORD -> R.string.archive_search_audiobooks
        ArchiveKind.MUSIC -> R.string.archive_search_music
    },
)

/**
 * Browse and search archive.org texts, spoken word or music (public domain until the waiver), with an item sheet to
 * Get, Borrow or View. [onOpenLibriVox] is the spoken-word note's way to LibriVox. [onOpenInLibrary] opens an audio
 * item got from the other audio view where it is (its album or its book).
 */
@Composable
fun ArchiveScreen(
    viewModel: ArchiveViewModel,
    detailsUrl: (String) -> String,
    onBack: () -> Unit,
    onOpenLibriVox: () -> Unit,
    onOpenInLibrary: (identifier: String) -> Unit = {},
) {
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
    fun openInBrowser(identifier: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(detailsUrl(identifier))))
        } catch (e: ActivityNotFoundException) {
            // No browser installed; nothing more to do.
        }
    }

    var confirmRemove by remember { mutableStateOf<Pair<String, String>?>(null) }

    ArchiveContent(
        ui = ui,
        searchText = viewModel.searchText,
        snackbar = snackbar,
        onQuery = viewModel::setQuery,
        onTab = viewModel::setTab,
        onShelf = viewModel::selectShelf,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        onOpenRow = viewModel::openItem,
        onGetRow = {
            beforeDownload()
            viewModel.get(it)
        },
        onCancelRow = viewModel::cancel,
        onRetryRow = viewModel::retryDownload,
        onRemoveRow = { confirmRemove = it.identifier to it.title },
        onAcceptWaiver = viewModel::acceptWaiver,
        onOpenLibriVox = onOpenLibriVox,
        onBack = onBack,
        onOpenInLibraryRow = onOpenInLibrary,
    )

    ui.sheet?.let { sheet ->
        ArchiveItemSheet(
            sheet,
            audio = ui.sheetAudio,
            onGet = {
                beforeDownload()
                viewModel.get(sheet.identifier)
            },
            onGetPdf = {
                beforeDownload()
                viewModel.get(sheet.identifier, pdf = true)
            },
            onCancel = { viewModel.cancel(sheet.identifier) },
            onRetry = { viewModel.retryDownload(sheet.identifier) },
            onRemove = { confirmRemove = sheet.identifier to (sheet.item?.title?.ifBlank { null } ?: sheet.identifier) },
            onOpenInBrowser = { openInBrowser(sheet.identifier) },
            onDismiss = viewModel::closeItem,
            onOpenInLibrary = {
                viewModel.closeItem()
                onOpenInLibrary(sheet.identifier)
            },
        )
    }

    ui.confirm?.let { confirm ->
        LargeDownloadDialog(confirm, onDownload = viewModel::confirmLarge, onDismiss = viewModel::dismissLarge)
    }

    confirmRemove?.let { (identifier, title) ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.archive_remove_title, title)) },
            text = {
                Text(
                    stringResource(
                        if (ui.kind == ArchiveKind.TEXTS) R.string.archive_remove_text_body else R.string.archive_remove_audio_body,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(identifier)
                    confirmRemove = null
                }) { Text(stringResource(R.string.common_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.common_keep)) } },
        )
    }
}

/** "This PDF is 212 MB. Download it?", with Don't ask again (Plan P2: big scans and comic runs are welcome, once warned). */
@Composable
internal fun LargeDownloadDialog(confirm: ConfirmLarge, onDownload: (dontAskAgain: Boolean) -> Unit, onDismiss: () -> Unit) {
    var dontAsk by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val size = app.granger.formatBytes(confirm.bytes)
            Text(stringResource(if (confirm.isPdf) R.string.archive_large_pdf else R.string.archive_large_download, size))
        },
        text = {
            // The whole row toggles, not just the box: the label is the bigger target.
            Row(
                Modifier.fillMaxWidth().toggleable(value = dontAsk, role = Role.Checkbox) { dontAsk = it },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Checkbox(checked = dontAsk, onCheckedChange = null)
                Text(stringResource(R.string.archive_dont_ask_again))
            }
        },
        confirmButton = { TextButton(onClick = { onDownload(dontAsk) }) { Text(stringResource(R.string.archive_download)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** The screen without its view model: the list, the filter note and the waiver dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArchiveContent(
    ui: ArchiveUi,
    searchText: String,
    snackbar: SnackbarHostState,
    onQuery: (String) -> Unit,
    onTab: (ArchiveTab) -> Unit,
    onShelf: (ArchiveShelf) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenRow: (String) -> Unit,
    onGetRow: (String) -> Unit,
    onCancelRow: (String) -> Unit,
    onRetryRow: (String) -> Unit,
    onRemoveRow: (ArchiveRow) -> Unit,
    onAcceptWaiver: () -> Unit,
    onOpenLibriVox: () -> Unit,
    onBack: () -> Unit,
    /** An item got from the other audio view ([ItemStatus.InLibrary]): open it there. */
    onOpenInLibraryRow: (String) -> Unit = {},
) {
    var showWaiver by rememberSaveable { mutableStateOf(false) }
    // Spoken word before the waiver shows nothing of archive.org's results, only the note (Decisions, Plan M2).
    val hidingResults = ui.waiverNeeded && ui.tab == ArchiveTab.BROWSE

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.archive_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "search") {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = onQuery,
                    placeholder = { Text(searchPlaceholder(ui.kind)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item(key = "tabs") {
                PrimaryTabRow(selectedTabIndex = ui.tab.ordinal) {
                    for (tab in ArchiveTab.entries) {
                        Tab(selected = ui.tab == tab, onClick = { onTab(tab) }, text = { Text(stringResource(tab.title)) })
                    }
                }
            }
            item(key = "note") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        filterNote(ui),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (ui.waiverNeeded) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onOpenLibriVox) { Text(stringResource(R.string.archive_librivox)) }
                            OutlinedButton(onClick = { showWaiver = true }) { Text(stringResource(R.string.archive_show_everything)) }
                        }
                    } else if (ui.filtered) {
                        OutlinedButton(onClick = { showWaiver = true }) { Text(stringResource(R.string.archive_show_everything)) }
                    }
                }
            }
            if (ui.tab == ArchiveTab.BROWSE && ui.query.isBlank() && !hidingResults) {
                item(key = "shelves") {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (shelf in ArchiveShelf.of(ui.kind)) {
                            FilterChip(
                                selected = ui.shelf == shelf,
                                onClick = { onShelf(shelf) },
                                label = { Text(androidx.compose.ui.res.stringResource(shelf.title)) },
                            )
                        }
                    }
                }
            }
            ui.error?.let { problem ->
                item(key = "problem") {
                    Column(Modifier.padding(16.dp)) {
                        Text(problem.resolve(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                    }
                }
            }
            if (!ui.loading && ui.error == null && ui.rows.isEmpty() && !hidingResults) {
                item(key = "empty") {
                    Text(
                        when {
                            ui.tab == ArchiveTab.INSTALLED -> stringResource(R.string.archive_empty_installed)
                            else -> stringResource(R.string.archive_empty_search)
                        },
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(if (hidingResults) emptyList() else ui.rows, key = { "row-${it.identifier}" }) { row ->
                ArchiveResultRow(
                    row,
                    onOpen = { onOpenRow(row.identifier) },
                    onGet = { onGetRow(row.identifier) },
                    onCancel = { onCancelRow(row.identifier) },
                    onRetry = { onRetryRow(row.identifier) },
                    onRemove = { onRemoveRow(row) },
                    onOpenInLibrary = { onOpenInLibraryRow(row.identifier) },
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
                    TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text(stringResource(R.string.archive_load_more)) }
                }
            }
            item(key = "disclaimer") {
                Text(
                    stringResource(R.string.archive_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (showWaiver) {
        WaiverDialog(
            onAccept = {
                onAcceptWaiver()
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
    onOpenInLibrary: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = { Text(row.title) },
        supportingContent = {
            Column {
                if (row.details.isNotBlank()) Text(row.details, style = MaterialTheme.typography.bodySmall)
                (audioLabel(row.audio) ?: offerLabel(row.preview))?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                ItemStatusText(row.status)
            }
        },
        trailingContent = {
            when (row.status) {
                ItemStatus.NotInstalled -> if (row.preview is Offer.Download || row.audio is AudioOffer.Tracks) {
                    TextButton(onClick = onGet) { Text(stringResource(R.string.common_get)) }
                }
                is ItemStatus.Queued, is ItemStatus.Downloading -> TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
                is ItemStatus.Failed -> Row {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                }
                ItemStatus.Installed, ItemStatus.UpdateAvailable -> TextButton(onClick = onRemove) { Text(stringResource(R.string.common_remove)) }
                is ItemStatus.Partial -> Row {
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.common_remove)) }
                    TextButton(onClick = onGet) { Text(stringResource(R.string.archive_get_the_rest)) }
                }
                is ItemStatus.InLibrary -> TextButton(onClick = onOpenInLibrary) { Text(stringResource(R.string.common_open)) }
                ItemStatus.NeedsMoreMemory -> Unit
            }
        },
    )
}
