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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
                    onChooseSlice = { zim -> row.setId?.let { viewModel.chooseSlice(it, zim) } },
                    onChoosePictures = { f -> row.setId?.let { viewModel.choosePictures(it, f) } },
                    onAddSubject = { row.subjects?.let(onOpenFamily) },
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
            title = { Text("Remove ${row.slices.firstOrNull { it.chosen }?.let { "${row.title}: ${it.label}" } ?: row.title}?") },
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
    onChooseSlice: (zimName: String) -> Unit = {},
    onChoosePictures: (flavour: String) -> Unit = {},
    onAddSubject: () -> Unit = {},
) {
    var licenceShown by rememberSaveable(row.id) { mutableStateOf(false) }
    val toggleLicence = if (row.license.isNotBlank()) Modifier.clickable { licenceShown = !licenceShown } else Modifier
    if (row.slices.isNotEmpty()) {
        SetRow(row, licenceShown, toggleLicence, onGet, onCancel, onRetry, onRemove, onOpen, onChooseSlice, onChoosePictures, onAddSubject)
        return
    }
    if (row.flavours.isNotEmpty()) {
        FlavouredRow(row, licenceShown, toggleLicence, onGet, onCancel, onRetry, onRemove, onOpen, onChooseFlavour)
        return
    }
    val status = row.status
    ListItem(
        modifier = toggleLicence,
        headlineContent = { Text(row.title) },
        supportingContent = {
            Column {
                if (row.description.isNotBlank()) {
                    Text(row.description, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text("${formatBytes(row.sizeBytes)} · ${row.details}", style = MaterialTheme.typography.bodySmall)
                if (licenceShown) Text("Licence: ${row.license}", style = MaterialTheme.typography.bodySmall)
                row.adds?.let { AddsText(it) }
                row.ask?.let { Text(it.text, style = MaterialTheme.typography.bodySmall) }
                ItemStatusText(status)
            }
        },
        trailingContent = { RowActions(row, onGet, onCancel, onRetry, onRemove, onOpen) },
    )
}

/**
 * A pack with flavours (Plan KB walkthrough, 360 dp): the title on its own line, wrapping rather than cut short; the
 * description; the flavours as radio rows, "With pictures · 7.0 GB", the one on the phone marked Installed; the
 * details; then the actions on their own line, so neither the title nor the choices are squeezed by them.
 */
@Composable
private fun FlavouredRow(
    row: ItemRow, licenceShown: Boolean, toggleLicence: Modifier,
    onGet: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit, onOpen: () -> Unit,
    onChooseFlavour: (String) -> Unit,
) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        // The title and description show the full licence on a tap, as a compact row does; the choices are their own.
        Column(Modifier.fillMaxWidth().then(toggleLicence)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (row.description.isNotBlank()) {
                Text(row.description, style = MaterialTheme.typography.bodyMedium, color = variant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.padding(vertical = 4.dp).selectableGroup().testTag(SourceTags.FLAVOURS)) {
            for (flavour in row.flavours) FlavourOption(flavour) { onChooseFlavour(flavour.id) }
        }
        Text(row.details, style = MaterialTheme.typography.bodySmall, color = variant)
        if (licenceShown) Text("Licence: ${row.license}", style = MaterialTheme.typography.bodySmall, color = variant)
        row.adds?.let { AddsText(it) }
        row.ask?.let { Text(it.text, style = MaterialTheme.typography.bodySmall, color = variant) }
        // Installed is on its choice; what's left is a download's progress or trouble, or an update.
        if (row.status != ItemStatus.Installed && !row.switches) {
            CompositionLocalProvider(LocalContentColor provides variant) { ItemStatusText(row.status) }
        }
        var confirmSwitch by remember(row.id) { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().testTag(SourceTags.ACTIONS), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            RowActions(row, onGet, onCancel, onRetry, onRemove, onOpen, onSwitch = { confirmSwitch = true })
        }
        val from = row.flavours.firstOrNull { it.installed }
        val to = row.flavours.firstOrNull { it.chosen }
        if (confirmSwitch && from != null && to != null) {
            SwitchDialog(row.title, from, to, onSwitch = { confirmSwitch = false; onGet() }, onDismiss = { confirmSwitch = false })
        }
    }
}

/** A subject's "Adds about 69,600 articles to your Best 50,000 (95% new)" (Plan WF Decision 4). */
@Composable
private fun AddsText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
}

/**
 * A set's one row (Plan WF Decision 3, 360 dp): the title and line; How much as radio rows, "Best 50,000 articles ·
 * 7.0 GB", a slice a bigger one on the phone holds reading "Inside your Top 1 million"; Pictures as radio rows, one the
 * chosen slice doesn't offer greyed with its reason; Add a subject; the details; then the actions on their own line.
 */
@Composable
private fun SetRow(
    row: ItemRow, licenceShown: Boolean, toggleLicence: Modifier,
    onGet: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit, onOpen: () -> Unit,
    onChooseSlice: (String) -> Unit, onChoosePictures: (String) -> Unit, onAddSubject: () -> Unit,
) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        Column(Modifier.fillMaxWidth().then(toggleLicence)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (row.description.isNotBlank()) {
                Text(row.description, style = MaterialTheme.typography.bodyMedium, color = variant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("How much", style = MaterialTheme.typography.labelMedium, color = variant, modifier = Modifier.padding(top = 8.dp))
        Column(Modifier.selectableGroup().testTag(SourceTags.SLICES)) {
            for (slice in row.slices) SliceOption(slice) { onChooseSlice(slice.zimName) }
        }
        if (row.pictures.size > 1) {
            Text("Pictures", style = MaterialTheme.typography.labelMedium, color = variant, modifier = Modifier.padding(top = 4.dp))
            Column(Modifier.selectableGroup().testTag(SourceTags.PICTURES)) {
                for (choice in row.pictures) PictureOption(choice) { onChoosePictures(choice.flavour) }
            }
        }
        if (row.subjects != null) {
            TextButton(onClick = onAddSubject, modifier = Modifier.testTag(SourceTags.ADD_SUBJECT)) { Text("Add a subject") }
        }
        Text(row.details, style = MaterialTheme.typography.bodySmall, color = variant)
        if (licenceShown) Text("Licence: ${row.license}", style = MaterialTheme.typography.bodySmall, color = variant)
        row.ask?.let { Text(it.text, style = MaterialTheme.typography.bodySmall, color = variant) }
        if (row.status != ItemStatus.Installed && !row.switches && row.insideOf == null) {
            CompositionLocalProvider(LocalContentColor provides variant) { ItemStatusText(row.status) }
        }
        var confirmSwitch by remember(row.id) { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().testTag(SourceTags.ACTIONS), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            val inside = row.insideOf
            when {
                row.switches -> {
                    if (row.packId != null) TextButton(onClick = onOpen) { Text("Open") }
                    TextButton(onClick = { confirmSwitch = true }) { Text("Switch to ${row.switchTo?.label ?: "this"}") }
                }
                inside != null && row.status == ItemStatus.NotInstalled -> {
                    Text(inside, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (row.packId != null) TextButton(onClick = onOpen) { Text("Open") }
                }
                else -> RowActions(row, onGet, onCancel, onRetry, onRemove, onOpen)
            }
        }
        val from = row.switchFrom
        val to = row.switchTo
        if (confirmSwitch && from != null && to != null) {
            SwitchDialog(row.title, from, to, onSwitch = { confirmSwitch = false; onGet() }, onDismiss = { confirmSwitch = false })
        }
    }
}

/** One slice: "Best 50,000 articles · 7.0 GB", or "· Inside your Top 1 million"; Installed when it is on the phone. */
@Composable
private fun SliceOption(slice: SliceChoice, onChoose: () -> Unit) {
    val size = formatBytes(slice.sizeBytes)
    val extra = slice.inside ?: slice.note
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = slice.chosen, role = Role.RadioButton, onClick = onChoose)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(slice.label, size, extra, "installed".takeIf { slice.installed }).joinToString(", ")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = slice.chosen, onClick = null)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text("${slice.label} · $size", style = MaterialTheme.typography.bodyMedium)
            if (extra != null) Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (slice.installed) {
            Text("Installed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** One pictures choice; one the chosen slice doesn't offer is greyed, with why. */
@Composable
private fun PictureOption(choice: PictureChoice, onChoose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = choice.chosen, enabled = choice.enabled, role = Role.RadioButton, onClick = onChoose)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(choice.label, choice.reason).joinToString(", ")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = choice.chosen, onClick = null, enabled = choice.enabled)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            val color = if (choice.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            Text(choice.label, style = MaterialTheme.typography.bodyMedium, color = color)
            choice.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** What Switch says it trades (Plan KB walkthrough 2): "Switch WikiMed from No pictures (1.1 GB) to With pictures (1.9 GB)?…" */
internal fun switchText(title: String, from: FlavourChoice, to: FlavourChoice): String =
    "Switch $title from ${from.label} (${formatBytes(from.sizeBytes)}) to ${to.label} (${formatBytes(to.sizeBytes)})? " +
        "The ${from.label} version is removed once the new one has downloaded. Your places and bookmarks carry over."

@Composable
internal fun SwitchDialog(title: String, from: FlavourChoice, to: FlavourChoice, onSwitch: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(switchText(title, from, to)) },
        confirmButton = { TextButton(onClick = onSwitch) { Text("Switch") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One flavour: a radio row read as "With pictures, 7.0 GB, installed". */
@Composable
private fun FlavourOption(flavour: FlavourChoice, onChoose: () -> Unit) {
    val size = formatBytes(flavour.sizeBytes)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = flavour.chosen, role = Role.RadioButton, onClick = onChoose)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(flavour.label, size, "installed".takeIf { flavour.installed }).joinToString(", ")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = flavour.chosen, onClick = null)
        Text(
            "${flavour.label} · $size",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 12.dp).weight(1f),
        )
        if (flavour.installed) {
            Text("Installed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * What a row's buttons are for its status: Get, Open, Switch, Update, Cancel, Retry, Remove. A pack with a flavour on
 * the phone offers Switch, enabled once another flavour is chosen and naming it ("Switch to With pictures"); [onSwitch]
 * asks first.
 */
@Composable
private fun RowActions(
    row: ItemRow, onGet: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit, onOpen: () -> Unit,
    onSwitch: () -> Unit = onGet,
) {
    val opens = row.packId != null
    if (row.switches) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (opens) TextButton(onClick = onOpen) { Text("Open") }
            TextButton(onClick = onSwitch) { Text(row.flavours.firstOrNull { it.chosen }?.let { "Switch to ${it.label}" } ?: "Switch") }
        }
        return
    }
    if (row.status == ItemStatus.Installed && opens && row.flavours.size > 1 && row.flavours.any { it.installed }) {
        // The flavour on the phone is the one chosen: nothing to switch to yet.
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpen) { Text("Open") }
            TextButton(onClick = {}, enabled = false) { Text("Switch") }
            RemoveMenu(row.title, onRemove)
        }
        return
    }
    when (val status = row.status) {
        // A subject already inside the Wikipedia on the phone (Plan WF Decision 4): Get stays, quietly.
        ItemStatus.NotInstalled -> if (row.covered) {
            TextButton(onClick = onGet) { Text("Get", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            TextButton(onClick = onGet) { Text("Get") }
        }
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
}

/** Test tags for a pack row with flavours. */
internal object SourceTags {
    const val FLAVOURS = "source-flavours"
    const val ACTIONS = "source-actions"
    const val SLICES = "source-slices"
    const val PICTURES = "source-pictures"
    const val ADD_SUBJECT = "source-add-subject"
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
