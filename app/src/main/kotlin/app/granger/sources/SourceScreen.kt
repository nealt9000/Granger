package app.granger.sources

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import app.granger.formatBytes

private fun sectionKey(index: Int, section: Section) = "section-$index-${section.title}"
private fun moreKey(index: Int, section: Section) = "more-$index-${section.title}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceScreen(viewModel: SourceViewModel, title: String, onBack: () -> Unit, onOpenPack: (packId: String) -> Unit = {}) {
    LifecycleResumeEffect(Unit) {
        viewModel.refreshVolumes()
        onPauseOrDispose {}
    }
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

    var confirmRemove by remember { mutableStateOf<ItemRow?>(null) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    /** Show all or Show fewer; folding a section the list has scrolled into brings its heading back. */
    fun toggle(index: Int, section: Section) {
        val folding = section.rows.size == section.total
        viewModel.toggleSection(section.title)
        if (!folding) return
        val heading = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == sectionKey(index, section) }
        if (heading == null) {
            val foot = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == moreKey(index, section) } ?: return
            scope.launch { list.scrollToItem((foot.index - section.rows.size - 1).coerceAtLeast(0)) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshCatalog, enabled = !ui.refreshing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Check for updates")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), state = list) {
            item(key = "search") {
                OutlinedTextField(
                    value = viewModel.searchText,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text("Search ${title}") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            val domainTitle = ui.domainTitle
            if (domainTitle != null) {
                item(key = "domain") {
                    Row(Modifier.padding(horizontal = 16.dp)) {
                        FilterChip(
                            selected = true,
                            onClick = viewModel::clearDomain,
                            label = { Text(domainTitle) },
                            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Show all of Kiwix") },
                        )
                    }
                }
            } else if (ui.categories.isNotEmpty()) {
                item(key = "categories") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item(key = "all") {
                            FilterChip(selected = ui.category == null, onClick = { viewModel.setCategory(null) }, label = { Text("All") })
                        }
                        items(ui.categories, key = { it }) { category ->
                            FilterChip(
                                selected = ui.category == category,
                                onClick = { viewModel.setCategory(if (ui.category == category) null else category) },
                                label = { Text(category) },
                            )
                        }
                    }
                }
            }
            item(key = "tabs") {
                PrimaryTabRow(selectedTabIndex = ui.tab.ordinal) {
                    for (tab in SourceTab.entries) {
                        Tab(selected = ui.tab == tab, onClick = { viewModel.setTab(tab) }, text = { Text(tab.title) })
                    }
                }
            }
            storageSummary(ui)?.let { summary ->
                item(key = "storage") {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            ui.catalogProblem?.let { problem ->
                item(key = "problem") {
                    Text(problem, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                }
            }
            if (ui.loading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
                }
            }
            if (!ui.loading && ui.catalogProblem == null && ui.sections.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (ui.query.isNotBlank()) "Nothing matches that search." else "Nothing here yet.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(ui.presets, key = { "preset-${it.id}" }) { preset ->
                PresetItem(preset) {
                    beforeDownload()
                    viewModel.getPreset(preset.id)
                }
                HorizontalDivider()
            }
            for ((index, section) in ui.sections.withIndex()) {
                // Indexed: a pack category and a book category can share a name.
                item(key = sectionKey(index, section)) {
                    Text(
                        section.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
                    )
                }
                items(section.rows, key = { it.id }) { row ->
                    CatalogItemRow(
                        row,
                        onGet = {
                            beforeDownload()
                            viewModel.get(row.id)
                        },
                        onCancel = { viewModel.cancel(row.id) },
                        onRetry = { viewModel.retry(row.id) },
                        onRemove = { confirmRemove = row },
                        onOpen = { row.packId?.let(onOpenPack) },
                    )
                    HorizontalDivider()
                }
                if (section.foldable) {
                    item(key = moreKey(index, section)) {
                        val text = if (section.rows.size < section.total) "Show all ${section.total} packs" else "Show fewer packs"
                        ListItem(
                            modifier = Modifier.clickable { toggle(index, section) },
                            headlineContent = { Text(text, color = MaterialTheme.colorScheme.primary) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    confirmRemove?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${row.title}?") },
            text = {
                Text(
                    if (row.packId != null) "This deletes the file, and the bookmarks and reading places you saved in it."
                    else "This deletes the file.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(row.id)
                    confirmRemove = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Keep") } },
        )
    }
}

/** "38.2 GB free on Phone · Granger uses 4.1 GB" for the volume downloads will go to. */
private fun storageSummary(ui: SourceUi): String? {
    val volume = ui.volumes.firstOrNull { it.path == ui.selectedVolume } ?: return null
    return "${formatBytes(volume.freeBytes)} free on ${volume.label} · Granger uses ${formatBytes(volume.grangerBytes)}"
}

@Composable
private fun PresetItem(preset: PresetRow, onGet: () -> Unit) {
    ListItem(
        headlineContent = { Text("${preset.title} set") },
        supportingContent = {
            Text(
                if (preset.remainingCount == 0) "All ${preset.itemCount} items are here or on their way."
                else "${preset.remainingCount} of ${preset.itemCount} items to get · ${formatBytes(preset.remainingBytes)}",
            )
        },
        trailingContent = { if (preset.remainingCount > 0) Button(onClick = onGet) { Text("Get all") } },
    )
}

/**
 * An installed pack opens here, where it's managed (Plan U3b Decision 3): Open is the row's action and Remove is in its
 * overflow. An installed item the library doesn't hold as a pack (a book, a model) keeps Remove as its action.
 */
@Composable
internal fun CatalogItemRow(
    row: ItemRow, onGet: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit, onOpen: () -> Unit = {},
) {
    val status = row.status
    val opens = row.packId != null
    ListItem(
        headlineContent = { Text(row.title) },
        supportingContent = {
            Column {
                if (row.description.isNotBlank()) {
                    Text(row.description, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text("${formatBytes(row.sizeBytes)} · ${row.details}", style = MaterialTheme.typography.bodySmall)
                row.ask?.let { Text(it.text, style = MaterialTheme.typography.bodySmall) }
                ItemStatusText(status)
            }
        },
        trailingContent = {
            when (status) {
                ItemStatus.NotInstalled -> TextButton(onClick = onGet) { Text("Get") }
                ItemStatus.UpdateAvailable -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (opens) TextButton(onClick = onOpen) { Text("Open") } else TextButton(onClick = onRemove) { Text("Remove") }
                    TextButton(onClick = onGet) { Text("Update") }
                    if (opens) RemoveMenu(row.title, onRemove)
                }
                is ItemStatus.Queued, is ItemStatus.Downloading -> TextButton(onClick = onCancel) { Text("Cancel") }
                is ItemStatus.Failed -> Row {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                ItemStatus.Installed -> if (opens) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onOpen) { Text("Open") }
                        RemoveMenu(row.title, onRemove)
                    }
                } else {
                    TextButton(onClick = onRemove) { Text("Remove") }
                }
                is ItemStatus.Partial -> Row {
                    TextButton(onClick = onRemove) { Text("Remove") }
                    TextButton(onClick = onGet) { Text("Get the rest") }
                }
                ItemStatus.NeedsMoreMemory, is ItemStatus.InLibrary -> Unit
            }
        },
    )
}

/** A row's overflow, holding Remove (which still asks first). */
@Composable
private fun RemoveMenu(title: String, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for $title") }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("Remove") }, onClick = { open = false; onRemove() })
        }
    }
}

/** The status line under an item: queue or progress, failure, installed. Shared with the archive.org rows. */
@Composable
internal fun ItemStatusText(status: ItemStatus) {
    when (status) {
        is ItemStatus.Queued -> Text(
            when {
                status.lastError != null -> "Retrying: ${status.lastError}"
                status.wifiOnly -> "Waiting for Wi-Fi"
                else -> "Queued"
            },
        )
        is ItemStatus.Downloading -> {
            LinearProgressIndicator(
                progress = { status.fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Text("${(status.fraction * 100).toInt()}%")
        }
        is ItemStatus.Failed -> Text(status.reason, color = MaterialTheme.colorScheme.error)
        ItemStatus.Installed -> Text("Installed", color = MaterialTheme.colorScheme.primary)
        ItemStatus.UpdateAvailable -> Text("A newer version is available")
        ItemStatus.NeedsMoreMemory -> Text("Needs a phone with more memory")
        is ItemStatus.Partial -> Text("Partly downloaded: ${status.done} of ${status.total}")
        is ItemStatus.InLibrary -> Text("In your ${status.shelf}", color = MaterialTheme.colorScheme.primary)
        ItemStatus.NotInstalled -> Unit
    }
}
