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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.granger.R
import app.granger.UiText
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
                title = { Text(ui.familyTitle?.resolve() ?: title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
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
        val header: LazyListScope.() -> Unit = {
            item(key = "search") {
                OutlinedTextField(
                    value = viewModel.searchText,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text(stringResource(R.string.sources_search, ui.familyTitle?.resolve() ?: title)) },
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
                            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.sources_show_all_kiwix)) },
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
                            FilterChip(selected = ui.category == null, onClick = { viewModel.setCategory(null) }, label = { Text(stringResource(R.string.sources_all)) })
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
                        Tab(selected = ui.tab == tab, onClick = { viewModel.setTab(tab) }, text = { Text(stringResource(tab.title)) })
                    }
                }
            }
            storageSummary(ui)?.let { summary ->
                item(key = "storage") {
                    Text(
                        summary.resolve(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            ui.catalogProblem?.let { problem ->
                item(key = "problem") {
                    Text(problem.resolve(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
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
                        stringResource(if (ui.query.isNotBlank()) R.string.sources_no_match else R.string.sources_nothing_yet),
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
                        section.heading.resolve(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
                    )
                }
                items(section.rows, key = { it.id }) { row -> rowItem(row) }
                if (section.foldable) {
                    item(key = moreKey(index, section)) {
                        val text = when {
                            section.rows.size < section.total -> pluralStringResource(
                                if (section.noun == SectionNoun.BOOKS) R.plurals.sources_show_all_books else R.plurals.sources_show_all_packs,
                                section.total, section.total,
                            )
                            else -> stringResource(if (section.noun == SectionNoun.BOOKS) R.string.sources_show_fewer_books else R.string.sources_show_fewer_packs)
                        }
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
            title = {
                val slice = row.slices.firstOrNull { it.chosen }
                Text(
                    if (slice != null) stringResource(R.string.sources_remove_slice_title, row.title, slice.label)
                    else stringResource(R.string.sources_remove_title, row.title),
                )
            },
            text = {
                Text(stringResource(if (row.packId != null) R.string.sources_remove_pack_body else R.string.sources_remove_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(row.id)
                    confirmRemove = null
                }) { Text(stringResource(R.string.common_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.common_keep)) } },
        )
    }
}

/** "38.2 GB free on Phone · Granger uses 4.1 GB" for the volume downloads will go to. */
private fun storageSummary(ui: SourceUi): UiText? {
    val volume = ui.volumes.firstOrNull { it.path == ui.selectedVolume } ?: return null
    return UiText.Res(R.string.sources_storage_summary, formatBytes(volume.freeBytes), volume.label, formatBytes(volume.grangerBytes))
}

@Composable
private fun PresetItem(preset: PresetRow, onGet: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.sources_preset_title, preset.title)) },
        supportingContent = {
            Column {
                Text(PresetText.summary(preset).resolve())
                Text(PresetText.status(preset).resolve())
            }
        },
        trailingContent = { if (preset.remainingCount > 0) Button(onClick = onGet) { Text(stringResource(R.string.sources_get_all)) } },
    )
}

/** A folded family's row (Plan KB Decision 10): "Developer docs", "243 packs", opening its own list. */
@Composable
internal fun FamilyRow(row: ItemRow, onOpen: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = { Text(row.familyLabel?.resolve() ?: row.title) },
        supportingContent = { Text(row.details.resolve(), style = MaterialTheme.typography.bodySmall) },
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
        title = { Text(stringResource(R.string.sources_size_check_title, check.title, formatBytes(check.sizeBytes))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val free = formatBytes(check.freeBytes)
                Text(
                    check.volumeLabel?.let { stringResource(R.string.sources_size_check_free, free, it) }
                        ?: stringResource(R.string.sources_size_check_free_phone, free),
                )
                if (check.sizeBytes > check.freeBytes) Text(stringResource(R.string.sources_size_check_no_room), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                check.smaller?.let { smaller ->
                    TextButton(onClick = { onGet(smaller.id) }) {
                        Text(stringResource(R.string.sources_get_smaller, smaller.label.resolve().lowercase(), formatBytes(smaller.sizeBytes)))
                    }
                }
                TextButton(onClick = { onGet(check.itemId) }) { Text(stringResource(R.string.sources_get_size, formatBytes(check.sizeBytes))) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
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
                val description = row.description.resolve()
                if (description.isNotBlank()) {
                    Text(description, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(stringResource(R.string.sources_dotted, formatBytes(row.sizeBytes), row.details.resolve()), style = MaterialTheme.typography.bodySmall)
                if (licenceShown) Text(stringResource(R.string.sources_licence, row.license), style = MaterialTheme.typography.bodySmall)
                row.adds?.let { AddsText(it.resolve()) }
                row.ask?.let { Text(it.text.resolve(), style = MaterialTheme.typography.bodySmall) }
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
            val description = row.description.resolve()
            if (description.isNotBlank()) {
                Text(description, style = MaterialTheme.typography.bodyMedium, color = variant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.padding(vertical = 4.dp).selectableGroup().testTag(SourceTags.FLAVOURS)) {
            for (flavour in row.flavours) FlavourOption(flavour) { onChooseFlavour(flavour.id) }
        }
        Text(row.details.resolve(), style = MaterialTheme.typography.bodySmall, color = variant)
        if (licenceShown) Text(stringResource(R.string.sources_licence, row.license), style = MaterialTheme.typography.bodySmall, color = variant)
        row.adds?.let { AddsText(it.resolve()) }
        row.ask?.let { Text(it.text.resolve(), style = MaterialTheme.typography.bodySmall, color = variant) }
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
            val description = row.description.resolve()
            if (description.isNotBlank()) {
                Text(description, style = MaterialTheme.typography.bodyMedium, color = variant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(stringResource(R.string.sources_how_much), style = MaterialTheme.typography.labelMedium, color = variant, modifier = Modifier.padding(top = 8.dp))
        Column(Modifier.selectableGroup().testTag(SourceTags.SLICES)) {
            for (slice in row.slices) SliceOption(slice) { onChooseSlice(slice.zimName) }
        }
        if (row.pictures.size > 1) {
            Text(stringResource(R.string.sources_pictures), style = MaterialTheme.typography.labelMedium, color = variant, modifier = Modifier.padding(top = 4.dp))
            Column(Modifier.selectableGroup().testTag(SourceTags.PICTURES)) {
                for (choice in row.pictures) PictureOption(choice) { onChoosePictures(choice.flavour) }
            }
        }
        if (row.subjects != null) {
            TextButton(onClick = onAddSubject, modifier = Modifier.testTag(SourceTags.ADD_SUBJECT)) { Text(stringResource(R.string.sources_add_subject)) }
        }
        Text(row.details.resolve(), style = MaterialTheme.typography.bodySmall, color = variant)
        if (licenceShown) Text(stringResource(R.string.sources_licence, row.license), style = MaterialTheme.typography.bodySmall, color = variant)
        row.ask?.let { Text(it.text.resolve(), style = MaterialTheme.typography.bodySmall, color = variant) }
        if (row.status != ItemStatus.Installed && !row.switches && row.insideOf == null) {
            CompositionLocalProvider(LocalContentColor provides variant) { ItemStatusText(row.status) }
        }
        var confirmSwitch by remember(row.id) { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().testTag(SourceTags.ACTIONS), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            val inside = row.insideOf
            when {
                row.switches -> {
                    if (row.packId != null) TextButton(onClick = onOpen) { Text(stringResource(R.string.common_open)) }
                    TextButton(onClick = { confirmSwitch = true }) {
                        Text(row.switchTo?.let { stringResource(R.string.sources_switch_to, it.label.resolve()) } ?: stringResource(R.string.sources_switch_to_this))
                    }
                }
                inside != null && row.status == ItemStatus.NotInstalled -> {
                    Text(inside.resolve(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (row.packId != null) TextButton(onClick = onOpen) { Text(stringResource(R.string.common_open)) }
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
    val extra = (slice.inside ?: slice.note)?.resolve()
    val installed = stringResource(R.string.sources_installed_lower)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = slice.chosen, role = Role.RadioButton, onClick = onChoose)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(slice.label, size, extra, installed.takeIf { slice.installed }).joinToString(", ")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = slice.chosen, onClick = null)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(stringResource(R.string.sources_dotted, slice.label, size), style = MaterialTheme.typography.bodyMedium)
            if (extra != null) Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (slice.installed) {
            Text(stringResource(R.string.sources_installed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** One pictures choice; one the chosen slice doesn't offer is greyed, with why. */
@Composable
private fun PictureOption(choice: PictureChoice, onChoose: () -> Unit) {
    val label = choice.label.resolve()
    val reason = choice.reason?.resolve()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = choice.chosen, enabled = choice.enabled, role = Role.RadioButton, onClick = onChoose)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(label, reason).joinToString(", ")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = choice.chosen, onClick = null, enabled = choice.enabled)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            val color = if (choice.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = color)
            reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** What Switch says it trades (Plan KB walkthrough 2): "Switch WikiMed from No pictures (1.1 GB) to With pictures (1.9 GB)?…" */
internal fun switchText(title: String, from: FlavourChoice, to: FlavourChoice): UiText =
    UiText.Res(R.string.sources_switch_text, title, from.label, formatBytes(from.sizeBytes), to.label, formatBytes(to.sizeBytes))

@Composable
internal fun SwitchDialog(title: String, from: FlavourChoice, to: FlavourChoice, onSwitch: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(switchText(title, from, to).resolve()) },
        confirmButton = { TextButton(onClick = onSwitch) { Text(stringResource(R.string.sources_switch)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** One flavour: a radio row read as "With pictures, 7.0 GB, installed". */
@Composable
private fun FlavourOption(flavour: FlavourChoice, onChoose: () -> Unit) {
    val size = formatBytes(flavour.sizeBytes)
    val label = flavour.label.resolve()
    val installed = stringResource(R.string.sources_installed_lower)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = flavour.chosen, role = Role.RadioButton, onClick = onChoose)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(label, size, installed.takeIf { flavour.installed }).joinToString(", ")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = flavour.chosen, onClick = null)
        Text(
            stringResource(R.string.sources_dotted, label, size),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 12.dp).weight(1f),
        )
        if (flavour.installed) {
            Text(stringResource(R.string.sources_installed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
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
            if (opens) TextButton(onClick = onOpen) { Text(stringResource(R.string.common_open)) }
            TextButton(onClick = onSwitch) {
                Text(row.flavours.firstOrNull { it.chosen }?.let { stringResource(R.string.sources_switch_to, it.label.resolve()) } ?: stringResource(R.string.sources_switch))
            }
        }
        return
    }
    if (row.status == ItemStatus.Installed && opens && row.flavours.size > 1 && row.flavours.any { it.installed }) {
        // The flavour on the phone is the one chosen: nothing to switch to yet.
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpen) { Text(stringResource(R.string.common_open)) }
            TextButton(onClick = {}, enabled = false) { Text(stringResource(R.string.sources_switch)) }
            RemoveMenu(row.title, onRemove)
        }
        return
    }
    when (val status = row.status) {
        // A subject already inside the Wikipedia on the phone (Plan WF Decision 4): Get stays, quietly.
        ItemStatus.NotInstalled -> if (row.covered) {
            TextButton(onClick = onGet) { Text(stringResource(R.string.common_get), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            TextButton(onClick = onGet) { Text(stringResource(R.string.common_get)) }
        }
        ItemStatus.UpdateAvailable -> Row(verticalAlignment = Alignment.CenterVertically) {
            if (opens) TextButton(onClick = onOpen) { Text(stringResource(R.string.common_open)) } else TextButton(onClick = onRemove) { Text(stringResource(R.string.common_remove)) }
            TextButton(onClick = onGet) { Text(stringResource(R.string.sources_update)) }
            if (opens) RemoveMenu(row.title, onRemove)
        }
        is ItemStatus.Queued, is ItemStatus.Downloading -> TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        is ItemStatus.Failed -> Row {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
            TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
        }
        ItemStatus.Installed -> if (opens) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpen) { Text(stringResource(R.string.common_open)) }
                RemoveMenu(row.title, onRemove)
            }
        } else {
            TextButton(onClick = onRemove) { Text(stringResource(R.string.common_remove)) }
        }
        is ItemStatus.Partial -> Row {
            TextButton(onClick = onRemove) { Text(stringResource(R.string.common_remove)) }
            TextButton(onClick = onGet) { Text(stringResource(R.string.archive_get_the_rest)) }
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
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.sources_more_for, title)) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.common_remove)) }, onClick = { open = false; onRemove() })
        }
    }
}

/** The status line under an item: queue or progress, failure, installed. Shared with the archive.org rows. */
@Composable
internal fun ItemStatusText(status: ItemStatus) {
    when (status) {
        is ItemStatus.Queued -> Text(
            when {
                status.lastError != null -> stringResource(R.string.sources_retrying, status.lastError)
                status.wifiOnly -> stringResource(R.string.sources_waiting_wifi)
                else -> stringResource(R.string.sources_queued_status)
            },
        )
        is ItemStatus.Downloading -> {
            LinearProgressIndicator(
                progress = { status.fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Text(stringResource(R.string.sources_percent, (status.fraction * 100).toInt()))
        }
        is ItemStatus.Failed -> Text(failedText(status).resolve(), color = MaterialTheme.colorScheme.error)
        ItemStatus.Installed -> Text(stringResource(R.string.sources_installed), color = MaterialTheme.colorScheme.primary)
        ItemStatus.UpdateAvailable -> Text(stringResource(R.string.sources_update_available))
        ItemStatus.NeedsMoreMemory -> Text(stringResource(R.string.sources_needs_memory))
        is ItemStatus.Partial -> Text(stringResource(R.string.sources_partly_downloaded, status.done, status.total))
        is ItemStatus.InLibrary -> Text(inLibraryText(status).resolve(), color = MaterialTheme.colorScheme.primary)
        ItemStatus.NotInstalled -> Unit
    }
}
