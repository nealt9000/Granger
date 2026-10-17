package app.granger.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import app.granger.archive.WaiverDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.BuildConfig
import app.granger.R
import app.granger.formatBytes

/** Models, Readiness, storage and download choices, and About. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onModels: () -> Unit, onReadiness: () -> Unit, onBack: () -> Unit) {
    LifecycleResumeEffect(Unit) {
        viewModel.refreshVolumes()
        onPauseOrDispose {}
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var showWaiver by rememberSaveable { mutableStateOf(false) }
    if (showWaiver) {
        WaiverDialog(
            onAccept = {
                viewModel.acceptArchiveWaiver()
                showWaiver = false
            },
            onDismiss = { showWaiver = false },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "models") {
                ListItem(
                    headlineContent = { Text("Models") },
                    supportingContent = { Text("AI models for this phone") },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.selectable(selected = false, onClick = onModels),
                )
                HorizontalDivider()
            }
            item(key = "readiness") {
                ListItem(
                    headlineContent = { Text("Readiness") },
                    supportingContent = { Text("Check that what you downloaded still works") },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.selectable(selected = false, onClick = onReadiness),
                )
                HorizontalDivider()
            }
            item(key = "storage") { StorageSection(ui, viewModel::selectVolume) }
            item(key = "wifi") {
                ListItem(
                    headlineContent = { Text("Download on Wi-Fi only") },
                    supportingContent = { Text("Turn off to use mobile data") },
                    trailingContent = { Switch(checked = ui.wifiOnly, onCheckedChange = viewModel::setWifiOnly) },
                )
                HorizontalDivider()
            }
            item(key = "archive") {
                ListItem(
                    headlineContent = { Text("Archive.org: show everything") },
                    supportingContent = {
                        Text(
                            if (ui.archiveEverything) "On: everything archive.org serves openly. Turning it off keeps downloaded files."
                            else "Only public-domain texts are shown. Downloaded files stay. Turning it on asks you to accept responsibility.",
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = ui.archiveEverything,
                            onCheckedChange = { on -> if (on) showWaiver = true else viewModel.withdrawArchiveWaiver() },
                        )
                    },
                )
                HorizontalDivider()
            }
            item(key = "about") { About() }
        }
    }
}

@Composable
private fun StorageSection(ui: SettingsUi, onSelect: (String) -> Unit) {
    val choosable = ui.volumes.size > 1
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Storage & downloads", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
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
private fun About() {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Image(painterResource(R.drawable.granger_mark), contentDescription = null, modifier = Modifier.size(56.dp))
        Text("GRANGER", style = MaterialTheme.typography.titleMedium)
        Text("Civilization, backed up.", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Version ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "GPLv3 · source on GitHub",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
