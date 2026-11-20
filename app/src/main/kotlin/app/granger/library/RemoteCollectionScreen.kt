package app.granger.library

import app.granger.resolve
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import app.granger.formatBytes
import app.granger.online.Availability
import app.granger.online.Mark
import app.granger.theme.GroupLabel
import app.granger.theme.GroupedItem

/** Test tags on the online collection screen. */
internal object RemoteTags {
    const val KEEP_COLLECTION = "bk_keep_collection"
    const val FILTER = "bk_filter"
    fun row(key: String) = "bk_row_$key"
}

/** A collection big enough to want a find field. */
private const val FILTER_FROM = 40

/**
 * A book pack's collection read online (Plan BK Task 4), in the Collection screen's look: the title, who made it and
 * how many are kept; "Keep the whole collection · 220 GB"; then its sections, each book a row with its mark. A tap opens
 * the book (read from the pack, saved for now); a long press offers Keep this book.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteCollectionScreen(
    viewModel: RemoteCollectionViewModel,
    onBack: () -> Unit,
    onOpenBook: (bookId: String, title: String) -> Unit,
    /** The installed pack's own collection screen, once the whole collection is on this phone. */
    onOpenInstalled: () -> Unit = {},
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.event.collect { e ->
            when (e) {
                is RemoteEvent.Open -> onOpenBook(e.bookId, e.title)
                is RemoteEvent.Say -> snackbar.showSnackbar(e.text.resolve(context))
            }
        }
    }
    var confirmPack by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "head") { Head(ui, onOpenInstalled) { confirmPack = true } }
            if (ui.loading) {
                item(key = "loading") { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() } }
            }
            if (ui.noList) {
                item(key = "no-list") {
                    Text(stringResource(R.string.bk_no_list), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (ui.total >= FILTER_FROM) {
                item(key = "filter") {
                    OutlinedTextField(
                        value = ui.filter, onValueChange = viewModel::setFilter, singleLine = true,
                        placeholder = { Text(stringResource(R.string.bk_filter)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(RemoteTags.FILTER),
                    )
                }
            }
            for (section in ui.sections) {
                if (section.name.isNotBlank()) item(key = "label:${section.name}") { GroupLabel(section.name) }
                itemsIndexed(section.rows, key = { _, r -> "row:${r.key}" }) { i, row ->
                    GroupedItem(i, section.rows.size) {
                        BookLine(
                            row, enabled = ui.online || row.mark != null, onOpen = { viewModel.open(row) }, onKeep = { viewModel.keep(row) },
                            onOriginal = { viewModel.openOriginal(row) },
                        )
                    }
                }
            }
            item(key = "foot") { Spacer(Modifier.height(24.dp)) }
        }
    }
    ui.opening?.let { o ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.bk_opening, o.title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (o.total > 0 && o.done > 0) LinearProgressIndicator(progress = { (o.done.toFloat() / o.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.bk_opening_progress, formatBytes(o.done), formatBytes(o.total)), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.bk_cancel)) } },
        )
    }
    ui.bigAsk?.let { ask ->
        AlertDialog(
            onDismissRequest = { viewModel.answerBig(read = false, keep = false) },
            title = { Text(stringResource(R.string.bk_big_title, formatBytes(ask.bytes))) },
            text = { Text(stringResource(if (ask.overCap) R.string.bk_big_over_cap else R.string.bk_big_body)) },
            confirmButton = {
                Row {
                    TextButton(onClick = { viewModel.answerBig(read = false, keep = true) }) { Text(stringResource(R.string.bk_keep)) }
                    TextButton(onClick = { viewModel.answerBig(read = true, keep = false) }) { Text(stringResource(R.string.bk_read_now)) }
                }
            },
            dismissButton = { TextButton(onClick = { viewModel.answerBig(read = false, keep = false) }) { Text(stringResource(R.string.bk_cancel)) } },
        )
    }
    if (confirmPack) {
        AlertDialog(
            onDismissRequest = { confirmPack = false },
            title = { Text(stringResource(R.string.bk_keep_collection, formatBytes(ui.packBytes))) },
            text = { Text(ui.title) },
            confirmButton = { TextButton(onClick = { confirmPack = false; viewModel.keepWholeCollection() }) { Text(stringResource(R.string.bk_keep)) } },
            dismissButton = { TextButton(onClick = { confirmPack = false }) { Text(stringResource(R.string.bk_cancel)) } },
        )
    }
}

@Composable
private fun Head(ui: RemoteCollectionUi, onOpenInstalled: () -> Unit, onKeepPack: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val meta = listOfNotNull(
            ui.provider.takeIf { it.isNotBlank() }?.let { stringResource(R.string.library_from_kiwix_by, it) } ?: stringResource(R.string.library_from_kiwix),
            if (ui.total > 0) pluralStringResource(R.plurals.bk_books_kept, ui.kept, ui.kept, java.text.NumberFormat.getIntegerInstance().format(ui.total)) else null,
        ).joinToString(" · ")
        Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!ui.online) Text(stringResource(R.string.bk_needs_internet), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ui.packHere) {
            Text(stringResource(R.string.mark_on_phone), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = onOpenInstalled) { Text(stringResource(R.string.bk_open_collection)) }
        } else if (ui.packQueued) {
            Text(stringResource(R.string.bk_keep_collection_queued), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        } else if (ui.packBytes > 0) {
            Button(onClick = onKeepPack, modifier = Modifier.testTag(RemoteTags.KEEP_COLLECTION)) {
                Text(stringResource(R.string.bk_keep_collection, formatBytes(ui.packBytes)))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookLine(row: RemoteBookRow, enabled: Boolean, onOpen: () -> Unit, onKeep: () -> Unit, onOriginal: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .combinedClickable(enabled = enabled && (row.readable || row.mark != null || row.editionId != null), onClick = onOpen, onLongClick = { menu = true })
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                .testTag(RemoteTags.row(row.key)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(row.edition ?: row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                // A reading edition says so, with its own size; the scan's maker and size are the original's.
                val meta = if (row.edition != null) {
                    listOfNotNull(stringResource(R.string.bk_reading_edition), row.editionBytes.takeIf { it > 0 }?.let(::formatBytes)).joinToString(" · ")
                } else {
                    listOfNotNull(row.author.takeIf { it.isNotBlank() }, formatBytes(row.bytes)).joinToString(" · ")
                }
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Mark(row.mark)
        }
        DropdownMenu(menu, { menu = false }) {
            if (row.editionId != null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.bk_original_scan)) }, onClick = { menu = false; onOriginal() }, enabled = enabled && row.readable)
            }
            if (row.mark is Availability.OnPhone) {
                DropdownMenuItem(text = { Text(stringResource(R.string.bk_kept)) }, onClick = { menu = false }, enabled = false)
            } else {
                DropdownMenuItem(text = { Text(stringResource(R.string.bk_keep_book)) }, onClick = { menu = false; onKeep() }, enabled = enabled)
            }
        }
    }
}
