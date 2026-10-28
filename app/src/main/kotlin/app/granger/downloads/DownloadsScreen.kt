package app.granger.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.core.downloads.DownloadState
import kotlinx.coroutines.launch

/** [onOpened] shows the tab a finished pack or book opened in; [onGetMore] goes where more can be got. */
@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel, onBack: () -> Unit, onOpened: (tabId: Long) -> Unit, onGetMore: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    DownloadsContent(
        ui, onBack, viewModel::cancel, viewModel::retry,
        onOpen = { row -> scope.launch { viewModel.open(row)?.let(onOpened) } },
        onGetMore = onGetMore,
    )
}

/** Now, Stopped and Done today, each only when it has rows; the empty state points to Get more. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsContent(
    ui: DownloadsUi,
    onBack: () -> Unit,
    onCancel: (DownloadRow) -> Unit,
    onRetry: (DownloadRow) -> Unit,
    onOpen: (DownloadRow) -> Unit,
    onGetMore: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("downloads-list")) {
            if (ui.isEmpty) {
                item(key = "empty") {
                    Text(
                        "Nothing downloading.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp),
                    )
                    TextButton(onClick = onGetMore, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Get more →") }
                }
            }
            for ((heading, rows) in listOf("Now" to ui.now, "Stopped" to ui.stopped, "Done today" to ui.doneToday)) {
                if (rows.isEmpty()) continue
                item(key = "heading-$heading") { Heading(heading) }
                for (row in rows) {
                    val open = row.key in expanded
                    item(key = row.key) {
                        DownloadItem(
                            row, open,
                            onToggle = { expanded = if (open) expanded - row.key else expanded + row.key },
                            onCancel = { onCancel(row) },
                            onRetry = { onRetry(row) },
                            onOpen = { onOpen(row) },
                        )
                    }
                    if (open) {
                        items(row.files.withIndex().toList(), key = { (i, _) -> "${row.key}/$i" }) { (_, file) ->
                            ListItem(
                                headlineContent = { Text(file.name, style = MaterialTheme.typography.bodyMedium) },
                                supportingContent = { Text(fileText(file.state), style = MaterialTheme.typography.bodySmall) },
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun fileText(state: DownloadState) = when (state) {
    DownloadState.QUEUED -> "Queued"
    DownloadState.RUNNING -> "Downloading"
    DownloadState.FAILED -> "Failed"
    DownloadState.INSTALLED -> "Done"
}

/** A group's row opens to its files; a finished pack or book opens on a tap. */
@Composable
private fun DownloadItem(
    row: DownloadRow, open: Boolean, onToggle: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit, onOpen: () -> Unit,
) {
    val tap = when {
        row.isGroup -> onToggle
        row.open != null -> onOpen
        else -> null
    }
    ListItem(
        headlineContent = { Text(row.title) },
        supportingContent = {
            Column {
                if (row.state == RowState.RUNNING) {
                    LinearProgressIndicator(progress = { row.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                Text(
                    downloadsText(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.state == RowState.FAILED) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        },
        leadingContent = if (!row.isGroup) null else {
            {
                Icon(
                    if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (open) "Hide files of ${row.title}" else "Show files of ${row.title}",
                )
            }
        },
        trailingContent = {
            when (row.state) {
                RowState.RUNNING, RowState.QUEUED -> TextButton(onClick = onCancel) { Text("Cancel") }
                RowState.FAILED -> Row {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                RowState.DONE -> if (row.open != null) TextButton(onClick = onOpen) { Text("Open") }
            }
        },
        modifier = if (tap == null) Modifier else Modifier.clickable(onClick = tap),
    )
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
