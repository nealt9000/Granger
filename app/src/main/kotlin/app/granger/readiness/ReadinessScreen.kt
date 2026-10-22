package app.granger.readiness

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.core.ai.DeviceTier
import app.granger.formatBytes
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadinessScreen(viewModel: ReadinessViewModel, onBack: () -> Unit) {
    LifecycleResumeEffect(Unit) {
        viewModel.refreshVolumes()
        onPauseOrDispose {}
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Readiness") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "device") {
                Heading("This phone")
                ListItem(
                    headlineContent = { Text(tierText(ui.tier)) },
                    supportingContent = { Text("${formatBytes(ui.totalRamBytes)} of memory") },
                )
            }
            item(key = "storage") { Heading("Storage") }
            items(ui.volumes, key = { "volume-${it.path}" }) { volume ->
                ListItem(
                    headlineContent = { Text(volume.label) },
                    supportingContent = {
                        Text("${formatBytes(volume.freeBytes)} free of ${formatBytes(volume.totalBytes)} · Granger uses ${formatBytes(volume.grangerBytes)}")
                    },
                )
            }
            item(key = "models") { Heading("AI models") }
            if (ui.models.isEmpty()) item(key = "no-models") { Empty("No models downloaded.") }
            items(ui.models, key = { it.key }) { row ->
                Check(row, viewModel::verify, viewModel::repair)
                HorizontalDivider()
            }
            if (ui.voices.isNotEmpty()) item(key = "voices") { Heading("Voices") }
            items(ui.voices, key = { it.key }) { row ->
                Check(row, viewModel::verify, viewModel::repair)
                HorizontalDivider()
            }
            item(key = "packs") { Heading("Packs") }
            if (ui.packs.isEmpty()) item(key = "no-packs") { Empty("No packs installed.") }
            items(ui.packs, key = { it.key }) { row ->
                Check(row, viewModel::verify, viewModel::repair)
                HorizontalDivider()
            }
            if (ui.media.isNotEmpty()) item(key = "media") { Heading("Music & audiobooks") }
            items(ui.media, key = { it.key }) { row ->
                Check(row, viewModel::verify, viewModel::repair)
                HorizontalDivider()
            }
        }
    }
}

private fun tierText(tier: DeviceTier) = when (tier) {
    DeviceTier.LIBRARY_ONLY -> "Library only: Research needs a phone with 6 GB of memory"
    DeviceTier.ASK -> "Library and Research"
    DeviceTier.THINK_DEEPER -> "Library, Research and Think deeper"
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun Empty(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun Check(row: CheckRow, onVerify: (String) -> Unit, onRepair: (String) -> Unit) {
    val (text, good) = when (row.status) {
        CheckStatus.VERIFIED -> {
            val date = row.verifiedAt?.let { " on " + DateFormat.getDateInstance().format(Date(it)) }.orEmpty()
            "Verified$date" to true
        }
        CheckStatus.SIZE_OK -> "Size is right; not fully checked yet" to true
        CheckStatus.INCOMPLETE -> "Incomplete: the file is the wrong size" to false
        CheckStatus.CORRUPTED -> "Corrupted: the contents don't match" to false
        CheckStatus.MISSING -> "Missing: the file isn't there" to false
        CheckStatus.DAMAGED -> "Damaged: it can't be opened" to false
        CheckStatus.NOT_FROM_CATALOG -> "Added by hand; can't be verified" to true
    }
    ListItem(
        headlineContent = { Text(row.title) },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(row.snapshotDate.ifBlank { null }, formatBytes(row.sizeBytes)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(text, color = if (good) Color.Unspecified else MaterialTheme.colorScheme.error)
                row.progress?.let { p ->
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    Text("Checking… ${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        trailingContent = {
            Row {
                if (row.canVerify) TextButton(onClick = { onVerify(row.key) }) { Text("Check") }
                if (row.canRepair) TextButton(onClick = { onRepair(row.key) }) { Text("Download again") }
            }
        },
    )
}
