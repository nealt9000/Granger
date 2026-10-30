package app.granger.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import app.granger.core.downloads.WaitReason
import app.granger.formatBytes
import kotlinx.coroutines.launch

/** What the rows and the header can ask for; each default does nothing, for previews and tests. */
data class DownloadsActions(
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onDownloadNext: (DownloadRow) -> Unit = {},
    val onCancel: (DownloadRow) -> Unit = {},
    val onCancelGroup: (DownloadRow) -> Unit = {},
    val onRetry: (DownloadRow) -> Unit = {},
    val onRetryAll: (List<DownloadRow>) -> Unit = {},
    val onRemove: (DownloadRow) -> Unit = {},
    val onOpen: (DownloadRow) -> Unit = {},
    val onGetMore: () -> Unit = {},
)

/**
 * [onOpenPack] opens a finished pack the NavHost's one way (Plan U3b Decision 3); [onOpened] shows the tab a finished
 * book opened in; [onGetMore] goes where more can be got.
 */
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    onBack: () -> Unit,
    onOpened: (tabId: Long) -> Unit,
    onOpenPack: (packId: String) -> Unit,
    onGetMore: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    DownloadsContent(
        ui, onBack,
        DownloadsActions(
            onPause = viewModel::pause,
            onResume = viewModel::resume,
            onDownloadNext = viewModel::downloadNext,
            onCancel = viewModel::cancel,
            onCancelGroup = viewModel::cancelGroup,
            onRetry = viewModel::retry,
            onRetryAll = viewModel::retryAll,
            onRemove = viewModel::remove,
            onOpen = { row ->
                when (val target = row.open) {
                    is OpenTarget.Pack -> onOpenPack(target.packId)
                    is OpenTarget.Book -> scope.launch { viewModel.openBook(target, row.title).let(onOpened) }
                    null -> Unit
                }
            },
            onGetMore = onGetMore,
        ),
        now = viewModel.now(),
    )
}

/** The header, then Now, Up next, Stopped and Done today, each only when it has rows; the empty state points to Get more. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsContent(ui: DownloadsUi, onBack: () -> Unit, actions: DownloadsActions, now: Long = System.currentTimeMillis()) {
    var showAll by rememberSaveable { mutableStateOf(false) }
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
                    TextButton(onClick = actions.onGetMore, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Get more →") }
                }
            }
            ui.header?.let { h -> item(key = "header") { Header(h, actions) } }
            ui.now?.let { row ->
                item(key = "heading-now") { Heading("Now") }
                item(key = row.key) { DownloadItem(row, now, actions, speed = ui.header?.bytesPerSecond) }
            }
            if (ui.upNext.isNotEmpty()) {
                item(key = "heading-next") { Heading("Up next") }
                val shown = if (showAll) ui.upNext else ui.upNext.take(DownloadsModel.UP_NEXT_SHOWN)
                for (row in shown) item(key = row.key) { DownloadItem(row, now, actions) }
                if (shown.size < ui.upNext.size) {
                    item(key = "show-all") {
                        TextButton(onClick = { showAll = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text("Show all ${ui.upNext.size}")
                        }
                    }
                }
            }
            if (ui.stopped.isNotEmpty()) {
                item(key = "heading-stopped") {
                    Heading("Stopped", action = if (ui.retriable.size > 1) ({ actions.onRetryAll(ui.retriable) }) else null, actionLabel = "Retry all")
                }
                for (row in ui.stopped) item(key = "stopped/${row.key}") { DownloadItem(row, now, actions) }
            }
            if (ui.doneToday.isNotEmpty()) {
                item(key = "heading-done") { Heading("Done today") }
                for (row in ui.doneToday) item(key = "done/${row.key}") { DownloadItem(row, now, actions) }
            }
        }
    }
}

/** The round's bar and figures, the speed with Pause all or Resume, and how much is still to come against free space. */
@Composable
private fun Header(h: DownloadsHeader, actions: DownloadsActions) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
        LinearProgressIndicator(progress = { h.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        Text(headerText(h), style = MaterialTheme.typography.bodyMedium)
        // The speed beside the button while it downloads; otherwise the storage line takes its place.
        val speed = h.bytesPerSecond?.takeIf { !h.paused }?.let { "${formatBytes(it)}/s" }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                speed ?: storageText(h),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (h.paused) {
                FilledTonalButton(onClick = actions.onResume) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Resume")
                }
            } else {
                FilledTonalButton(onClick = actions.onPause) {
                    Icon(painterResource(R.drawable.ic_pause), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pause all")
                }
            }
        }
        if (speed != null) {
            Text(storageText(h), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * One row: the group as a small label over the title, a bar while it runs, the line under it, and its actions. A
 * waiting row's overflow has Download next and Cancel (and Cancel all for a group); a row that can't be fetched again
 * offers Remove.
 */
@Composable
private fun DownloadItem(row: DownloadRow, now: Long, actions: DownloadsActions, speed: Long? = null) {
    val tap = if (row.state == RowState.DONE && row.open != null) ({ actions.onOpen(row) }) else null
    ListItem(
        overlineContent = row.groupLabel?.takeIf { row.state != RowState.DONE }?.let { label ->
            { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        headlineContent = { Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                if (row.state == RowState.RUNNING) {
                    LinearProgressIndicator(progress = { row.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                Text(
                    downloadsText(row, now, speed),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        row.state == RowState.FAILED || row.removeOnly -> MaterialTheme.colorScheme.error
                        row.reason is WaitReason.Retrying || row.reason is WaitReason.NeedsSpace -> MaterialTheme.colorScheme.tertiary
                        else -> Color.Unspecified
                    },
                )
            }
        },
        trailingContent = {
            when {
                row.removeOnly -> TextButton(onClick = { actions.onRemove(row) }) { Text("Remove") }
                row.state == RowState.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { actions.onRetry(row) }) { Text("Retry") }
                    RowMenu(row, actions)
                }
                row.state == RowState.DONE -> if (row.open != null) TextButton(onClick = { actions.onOpen(row) }) { Text("Open") }
                else -> RowMenu(row, actions)
            }
        },
        modifier = if (tap == null) Modifier else Modifier.clickable(onClick = tap),
    )
}

/** Download next (not for the file running, nor the one already next), Cancel, and Cancel all for a group's file. */
@Composable
private fun RowMenu(row: DownloadRow, actions: DownloadsActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for ${row.title}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val next = row.state == RowState.QUEUED && row.reason != WaitReason.InLine(1)
            if (next) DropdownMenuItem(text = { Text("Download next") }, onClick = { open = false; actions.onDownloadNext(row) })
            DropdownMenuItem(text = { Text("Cancel") }, onClick = { open = false; actions.onCancel(row) })
            row.groupLabel?.let { label ->
                DropdownMenuItem(text = { Text("Cancel all of $label") }, onClick = { open = false; actions.onCancelGroup(row) })
            }
        }
    }
}

/** A section's heading, with an action at its end (Stopped's Retry all). */
@Composable
private fun Heading(text: String, action: (() -> Unit)? = null, actionLabel: String = "") {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        if (action != null) TextButton(onClick = action) { Text(actionLabel) }
    }
}
