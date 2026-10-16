package app.granger.downloads

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel, onBack: () -> Unit) {
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
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
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "storage") { StorageSection(ui, viewModel::selectVolume) }
            item(key = "wifi") {
                ListItem(
                    headlineContent = { Text("Download on Wi-Fi only") },
                    supportingContent = { Text("Turn off to use mobile data") },
                    trailingContent = { Switch(checked = ui.wifiOnly, onCheckedChange = viewModel::setWifiOnly) },
                )
                HorizontalDivider()
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
            items(ui.presets, key = { "preset-${it.id}" }) { preset ->
                PresetItem(preset) {
                    beforeDownload()
                    viewModel.getPreset(preset.id)
                }
                HorizontalDivider()
            }
            for (section in ui.sections) {
                item(key = "section-${section.title}") {
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
                    )
                    HorizontalDivider()
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

@Composable
private fun StorageSection(ui: DownloadsUi, onSelect: (String) -> Unit) {
    val choosable = ui.volumes.size > 1
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Storage", style = MaterialTheme.typography.titleSmall)
        for (volume in ui.volumes) {
            val selected = volume.path == ui.selectedVolume
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().let {
                    if (choosable) it.selectable(selected = selected, onClick = { onSelect(volume.path) }) else it
                },
            ) {
                if (choosable) RadioButton(selected = selected, onClick = null)
                Column(Modifier.weight(1f).padding(start = if (choosable) 8.dp else 0.dp)) {
                    Text(volume.label)
                    val used = if (volume.totalBytes > 0) 1f - volume.freeBytes.toFloat() / volume.totalBytes else 0f
                    LinearProgressIndicator(
                        progress = { used.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    Text(
                        buildString {
                            append("${formatBytes(volume.freeBytes)} free · Granger uses ${formatBytes(volume.grangerBytes)}")
                            if (volume.queuedBytes > 0) append(" · ${formatBytes(volume.queuedBytes)} to download")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (choosable) Text("New downloads go to the selected storage.", style = MaterialTheme.typography.bodySmall)
        Text(
            "Uninstalling Granger deletes everything it downloaded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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

@Composable
private fun CatalogItemRow(row: ItemRow, onGet: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit) {
    val status = row.status
    ListItem(
        headlineContent = { Text(row.title) },
        supportingContent = {
            Column {
                Text("${formatBytes(row.sizeBytes)} · ${row.details}", style = MaterialTheme.typography.bodySmall)
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
                    ItemStatus.NotInstalled -> Unit
                }
            }
        },
        trailingContent = {
            when (status) {
                ItemStatus.NotInstalled -> TextButton(onClick = onGet) { Text("Get") }
                ItemStatus.UpdateAvailable -> Row {
                    TextButton(onClick = onRemove) { Text("Remove") }
                    TextButton(onClick = onGet) { Text("Update") }
                }
                is ItemStatus.Queued, is ItemStatus.Downloading -> TextButton(onClick = onCancel) { Text("Cancel") }
                is ItemStatus.Failed -> Row {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                ItemStatus.Installed -> TextButton(onClick = onRemove) { Text("Remove") }
                ItemStatus.NeedsMoreMemory -> Unit
            }
        },
    )
}
