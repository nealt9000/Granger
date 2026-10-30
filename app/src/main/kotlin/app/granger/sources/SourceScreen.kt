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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import app.granger.core.library.ShelfKey
import app.granger.library.shelf.ShelfList
import app.granger.library.shelf.ShelfSections

private fun sectionKey(index: Int, section: Section) = "section-$index-${section.title}"
private fun moreKey(index: Int, section: Section) = "more-$index-${section.title}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceScreen(
    viewModel: SourceViewModel,
    title: String,
    onBack: () -> Unit,
    onOpenPack: (packId: String) -> Unit = {},
    onOpenFamily: (family: String) -> Unit = {},
) {
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
                title = { Text(ui.familyTitle ?: title) },
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
        val header: LazyListScope.() -> Unit = {
            item(key = "search") {
                OutlinedTextField(
                    value = viewModel.searchText,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text("Search ${ui.familyTitle ?: title}") },
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
        }
        val rowItem: @Composable (ItemRow) -> Unit = { row ->
            if (row.opensFamily != null) {
                FamilyRow(row) { onOpenFamily(row.opensFamily) }
            } else {
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
                    onChooseFlavour = viewModel::chooseFlavour,
                )
            }
            HorizontalDivider()
        }
        if (ui.familyTitle != null) {
            // A folded family's own list (Plan KB Decision 10): A–Z on the shared shelf, with the letter strip.
            val rows = ui.sections.flatMap { it.rows }
            val shelf = remember(rows) {
                ShelfSections.of(rows, byName = true, key = { it.id }, letter = { ShelfKey.letter(ShelfKey.of(it.title)) })
            }
            ShelfList(shelf, Modifier.padding(padding).fillMaxSize(), state = list, header = header) { row -> rowItem(row) }
        } else LazyColumn(Modifier.padding(padding).fillMaxSize(), state = list) {
            header()
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
                items(section.rows, key = { it.id }) { row -> rowItem(row) }
                if (section.foldable) {
                    item(key = moreKey(index, section)) {
                        val text = if (section.rows.size < section.total) "Show all ${section.total} ${section.noun}" else "Show fewer ${section.noun}"
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
            Column {
                Text(PresetText.summary(preset))
                Text(PresetText.status(preset))
            }
        },
        trailingContent = { if (preset.remainingCount > 0) Button(onClick = onGet) { Text("Get all") } },
    )
}

/** A folded family's row (Plan KB Decision 10): "Developer docs", "243 packs", opening its own list. */
@Composable
internal fun FamilyRow(row: ItemRow, onOpen: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = { Text(row.title) },
        supportingContent = { Text(row.details, style = MaterialTheme.typography.bodySmall) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
    )
}

/**
 * "Survivor Library is 220 GB. You have 41 GB free on Phone." (Plan KB Decision 9): Get, the pack's smaller flavour
 * where there is one, or Cancel.
 */
@Composable
internal fun SizeCheckDialog(check: SizeCheck, onGet: (itemId: String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${check.title} is ${formatBytes(check.sizeBytes)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You have ${formatBytes(check.freeBytes)} free on ${check.volumeLabel}.")
                if (check.sizeBytes > check.freeBytes) Text("That isn't enough room for it.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                check.smaller?.let { smaller ->
                    TextButton(onClick = { onGet(smaller.id) }) { Text("Get ${smaller.label.lowercase()}, ${formatBytes(smaller.sizeBytes)}") }
                }
                TextButton(onClick = { onGet(check.itemId) }) { Text("Get ${formatBytes(check.sizeBytes)}") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * An installed pack opens here, where it's managed (Plan U3b Decision 3): Open is the row's action and Remove is in its
 * overflow. An installed item the library doesn't hold as a pack (a book, a model) keeps Remove as its action. A pack
 * with flavours offers them as chips, and says which is on the phone (Plan KB Decision 8); a tap on the row shows its
 * full licence.
 */
@Composable
internal fun CatalogItemRow(
    row: ItemRow, onGet: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit, onOpen: () -> Unit = {},
    onChooseFlavour: (String) -> Unit = {},
) {
    val status = row.status
    val opens = row.packId != null
    var licenceShown by rememberSaveable(row.id) { mutableStateOf(false) }
    ListItem(
        modifier = if (row.license.isNotBlank()) Modifier.clickable { licenceShown = !licenceShown } else Modifier,
        headlineContent = { Text(row.title) },
        supportingContent = {
            Column {
                if (row.description.isNotBlank()) {
                    Text(row.description, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (row.flavours.isNotEmpty()) {
                    FlowRow(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (flavour in row.flavours) {
                            FilterChip(
                                selected = flavour.chosen,
                                onClick = { onChooseFlavour(flavour.id) },
                                label = {
                                    Column {
                                        Text(flavour.label, style = MaterialTheme.typography.labelMedium)
                                        Text(formatBytes(flavour.sizeBytes), style = MaterialTheme.typography.labelSmall)
                                    }
                                },
                            )
                        }
                    }
                    Text(row.details, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("${formatBytes(row.sizeBytes)} · ${row.details}", style = MaterialTheme.typography.bodySmall)
                }
                if (licenceShown) Text("Licence: ${row.license}", style = MaterialTheme.typography.bodySmall)
                row.ask?.let { Text(it.text, style = MaterialTheme.typography.bodySmall) }
                when {
                    row.installedFlavour != null && (row.switches || status == ItemStatus.Installed || status == ItemStatus.UpdateAvailable) ->
                        Text("Installed: ${row.installedFlavour}", color = MaterialTheme.colorScheme.primary)
                    else -> ItemStatusText(status)
                }
                if (row.installedFlavour != null && status == ItemStatus.UpdateAvailable) Text("A newer version is available")
            }
        },
        trailingContent = {
            if (row.switches) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (opens) TextButton(onClick = onOpen) { Text("Open") }
                    TextButton(onClick = onGet) { Text("Switch") }
                }
                return@ListItem
            }
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
