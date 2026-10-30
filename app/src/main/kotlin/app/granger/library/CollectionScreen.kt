package app.granger.library

import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.core.library.Book
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.library.shelf.ShelfCoverSize
import app.granger.library.shelf.ShelfGrid
import app.granger.library.shelf.ShelfList
import app.granger.library.shelf.ShelfRow
import app.granger.library.shelf.ShelfView
import app.granger.library.shelf.ViewToggle
import app.granger.theme.readout
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * One collection's packs and books; the books can be found by title or author, sorted, and in Granger Classics
 * browsed by kind. Tap opens a pack in a reader tab; long-press organises or removes it. A folder (Kiwix)
 * lists its collections instead, as the Library does, each opening its own screen; a folder has no menu.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CollectionScreen(
    viewModel: CollectionViewModel,
    onOpened: (tabId: Long) -> Unit,
    /** Opens an installed pack: the NavHost's one way to (Plan U3b Decision 3). */
    onOpenPack: (packId: String) -> Unit,
    /** Opens the Granger source, where the e-books that replace a superseded pack are. */
    onGetBooks: () -> Unit,
    /** A folder's child collection. */
    onOpenCollection: (collectionId: String) -> Unit,
    onBack: () -> Unit,
) {
    val row by viewModel.ui.collectAsStateWithLifecycle()
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    val positions by viewModel.positions.collectAsStateWithLifecycle()
    val superseded by viewModel.superseded.collectAsStateWithLifecycle()
    val shown by viewModel.books.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val view by viewModel.view.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // The collection disappears when its last pack moves out or it is deleted; leave once it has been seen.
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(row) {
        if (row != null) seen = true else if (seen) onBack()
    }
    var menuOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<CollectionDialog?>(null) }
    val loaded = row

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loaded?.collection?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (loaded != null && !loaded.isFolder) Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Move collection to…") },
                                onClick = { menuOpen = false; dialog = CollectionDialog.MoveCollection },
                            )
                            if (loaded.collection.userCreated) {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = { menuOpen = false; dialog = CollectionDialog.Rename },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = { menuOpen = false; dialog = CollectionDialog.Delete },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (loaded != null && loaded.isFolder) LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(loaded.children, key = { it.collection.id }) { child ->
                ListItem(
                    modifier = Modifier.clickable { onOpenCollection(child.collection.id) },
                    headlineContent = { Text(child.collection.name) },
                    supportingContent = { Text(rowCountText(child)) },
                )
            }
        } else if (loaded != null) {
            // Above the books, in either view: the packs, then the Find field and the chips once there are books.
            val head = buildList {
                for (pack in loaded.packs) add(HeadItem("pack:${pack.id}", "pack") {
                    Column {
                        Box {
                            var packMenu by remember { mutableStateOf(false) }
                            PackRow(
                                pack,
                                replaced = pack.id in superseded,
                                onClick = { onOpenPack(pack.id) },
                                onLongClick = { packMenu = true },
                                onGetBooks = onGetBooks,
                            )
                            DropdownMenu(packMenu, { packMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Move to collection…") },
                                    onClick = { packMenu = false; dialog = CollectionDialog.MovePack(pack) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Remove from phone") },
                                    onClick = { packMenu = false; dialog = CollectionDialog.RemovePack(pack) },
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                })
                if (shown.total > 0) {
                    add(HeadItem("find", "find") {
                        OutlinedTextField(
                            value = viewModel.findText,
                            onValueChange = viewModel::setQuery,
                            placeholder = { Text("Find a title or author") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = if (viewModel.findText.isEmpty()) null else {
                                { IconButton(onClick = { viewModel.setQuery("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear") } }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    })
                    // The sort chips, and at their end the view toggle (in the top bar it pushed a long name onto two lines at 360 dp).
                    add(HeadItem("sort", "chips") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LazyRow(
                                Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(BookSort.entries, key = { it.name }) { option ->
                                    FilterChip(selected = sort == option, onClick = { viewModel.setSort(option) }, label = { Text(option.label) })
                                }
                            }
                            ViewToggle(view, viewModel::toggleView)
                        }
                    })
                    // Granger Classics: the catalog's kinds (novels, drama, philosophy, …); one chip shows that kind alone.
                    if (shown.categories.isNotEmpty()) add(HeadItem("categories", "chips") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item(key = "all") {
                                FilterChip(selected = shown.category == null, onClick = { viewModel.setCategory(null) }, label = { Text("All") })
                            }
                            items(shown.categories, key = { it }) { category ->
                                FilterChip(
                                    selected = shown.category == category,
                                    onClick = { viewModel.setCategory(if (shown.category == category) null else category) },
                                    label = { Text(category) },
                                )
                            }
                        }
                    })
                    if (shown.books.isEmpty()) add(HeadItem("none", "none") {
                        Text(
                            "No books match.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                        )
                    })
                }
            }
            val open = { book: Book -> scope.launch { viewModel.openBook(book)?.let(onOpened) }; Unit }
            if (view == ShelfView.LIST) ShelfList(
                shown.sections,
                Modifier.padding(padding).fillMaxSize(),
                contentType = "book",
                header = { for (h in head) item(key = h.key, contentType = h.contentType) { h.content() } },
            ) { book ->
                Box {
                    var bookMenu by remember { mutableStateOf(false) }
                    BookRow(book, positions[book.id]?.totalProgression, onClick = { open(book) }, onLongClick = { bookMenu = true })
                    BookMenu(bookMenu, { bookMenu = false }, onMove = { dialog = CollectionDialog.MoveBook(book) }, onRemove = { dialog = CollectionDialog.RemoveBook(book) })
                }
            } else ShelfGrid(
                shown.sections,
                cellMin = 104.dp,
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                headerIndent = 8.dp,
                contentType = "book",
                header = { for (h in head) item(key = h.key, span = { GridItemSpan(maxLineSpan) }, contentType = h.contentType) { h.content() } },
            ) { book ->
                Box(Modifier.padding(horizontal = 8.dp)) {
                    var bookMenu by remember { mutableStateOf(false) }
                    BookCell(book, positions[book.id]?.totalProgression, onClick = { open(book) }, onLongClick = { bookMenu = true })
                    BookMenu(bookMenu, { bookMenu = false }, onMove = { dialog = CollectionDialog.MoveBook(book) }, onRemove = { dialog = CollectionDialog.RemoveBook(book) })
                }
            }
        }
    }

    val collection = loaded?.collection ?: return
    when (val d = dialog) {
        null -> Unit
        is CollectionDialog.MovePack -> MovePackDialog(
            shelves = targets,
            currentCollectionId = collection.id,
            onMove = { viewModel.movePack(d.pack, it) },
            onMoveToNewCollection = { type, name -> viewModel.movePackToNewCollection(d.pack, type, name) },
            onMoveToNewMediaType = { type, name -> viewModel.movePackToNewMediaType(d.pack, type, name) },
            onDismiss = { dialog = null },
        )
        is CollectionDialog.MoveBook -> MovePackDialog(
            shelves = targets,
            currentCollectionId = collection.id,
            onMove = { viewModel.moveBook(d.book, it) },
            onMoveToNewCollection = { type, name -> viewModel.moveBookToNewCollection(d.book, type, name) },
            onMoveToNewMediaType = { type, name -> viewModel.moveBookToNewMediaType(d.book, type, name) },
            onDismiss = { dialog = null },
        )
        is CollectionDialog.RemoveBook -> ConfirmDialog(
            title = "Remove from phone?",
            message = "${d.book.title} will be deleted from this phone. Your place, bookmarks and highlights are kept.",
            confirm = "Remove",
            onConfirm = { viewModel.removeBook(d.book) },
            onDismiss = { dialog = null },
        )
        is CollectionDialog.RemovePack -> ConfirmDialog(
            title = "Remove from phone?",
            message = "${d.pack.title} will be deleted from this phone. You can download it again later.",
            confirm = "Remove",
            onConfirm = { viewModel.remove(d.pack) },
            onDismiss = { dialog = null },
        )
        CollectionDialog.MoveCollection -> MoveCollectionDialog(
            shelves = targets,
            currentMediaTypeId = collection.mediaTypeId,
            onMove = viewModel::moveCollection,
            onMoveToNewMediaType = viewModel::moveCollectionToNewMediaType,
            onDismiss = { dialog = null },
        )
        CollectionDialog.Rename -> NameDialog(
            title = "Rename collection",
            label = "Collection name",
            confirm = "Rename",
            initial = collection.name,
            onConfirm = { viewModel.rename(it); dialog = null },
            onDismiss = { dialog = null },
        )
        CollectionDialog.Delete -> ConfirmDialog(
            title = "Delete collection?",
            message = "Its packs go back to their usual collections. Nothing is removed from the phone.",
            confirm = "Delete",
            onConfirm = viewModel::delete,
            onDismiss = { dialog = null },
        )
    }
}

/** A row above the books, in either view: a pack, the Find field, the chips. */
private class HeadItem(val key: String, val contentType: String, val content: @Composable () -> Unit)

@Composable
private fun BookMenu(open: Boolean, onClose: () -> Unit, onMove: () -> Unit, onRemove: () -> Unit) {
    DropdownMenu(open, onClose) {
        DropdownMenuItem(text = { Text("Move to collection…") }, onClick = { onClose(); onMove() })
        DropdownMenuItem(text = { Text("Remove from phone") }, onClick = { onClose(); onRemove() })
    }
}

private sealed interface CollectionDialog {
    data class MovePack(val pack: Pack) : CollectionDialog
    data class RemovePack(val pack: Pack) : CollectionDialog
    data class MoveBook(val book: Book) : CollectionDialog
    data class RemoveBook(val book: Book) : CollectionDialog
    data object MoveCollection : CollectionDialog
    data object Rename : CollectionDialog
    data object Delete : CollectionDialog
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PackRow(pack: Pack, replaced: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onGetBooks: () -> Unit) {
    val context = LocalContext.current
    val details = listOf(pack.language, pack.date, Formatter.formatShortFileSize(context, pack.sizeBytes))
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    val problem = when (pack.status) {
        PackStatus.OK -> null
        PackStatus.MISSING -> "File not found. Reinsert the SD card or download the pack again."
        PackStatus.CORRUPT -> "This file is damaged and can't be opened."
    }
    ListItem(
        modifier = Modifier.combinedClickable(
            enabled = true,
            onClick = { if (pack.status == PackStatus.OK) onClick() },
            onLongClick = onLongClick,
        ),
        headlineContent = { Text(pack.title) },
        supportingContent = {
            Column {
                Text(details)
                if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error)
                if (replaced) Text("Replaced by the Granger Classics e-books", color = MaterialTheme.colorScheme.primary)
            }
        },
        trailingContent = if (replaced) {
            { Button(onClick = onGetBooks) { Text("Get the e-books") } }
        } else {
            null
        },
    )
}

/**
 * A book in the list (Plan LV Decision 2): its cover, title, author, the format label, and how far through it the
 * reader is once started. A missing or damaged book is dimmed and says so.
 */
@Composable
internal fun BookRow(book: Book, progress: Double?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val readable = book.status == PackStatus.OK
    val problem = when (book.status) {
        PackStatus.OK -> null
        PackStatus.MISSING -> "File not found"
        PackStatus.CORRUPT -> "Damaged"
    }
    val label = formatLabel(book)
    ShelfRow(
        title = book.title,
        subtitle = book.author,
        cover = { BookCover(book, Modifier.height(ShelfCoverSize)) },
        onClick = { if (readable) onClick() },
        onLongClick = onLongClick,
        dimmed = !readable,
        detail = if (label == null && problem == null) null else {
            {
                if (label != null) Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (problem != null) Text(problem, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        },
        trailing = progress?.takeIf { readable && it > 0.0 }?.let { p ->
            { Text("${(p * 100).roundToInt().coerceIn(1, 100)}%", style = MaterialTheme.typography.labelMedium.readout, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
    )
}

/** A book in the grid: its cover, title (two lines), author (one) and a thin bar for how far through it the reader is. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookCell(book: Book, progress: Double?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val readable = book.status == PackStatus.OK
    Column(
        Modifier.combinedClickable(onClick = { if (readable) onClick() }, onLongClick = onLongClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // A book that is missing or damaged is dimmed, like an unreadable pack is flagged in red.
        BookCover(book, Modifier.fillMaxWidth().alpha(if (readable) 1f else 0.4f))
        Text(
            book.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            minLines = 2,
        )
        // Always one line, blank or not, so the covers in a row stay level.
        Text(
            book.author,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            minLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        formatLabel(book)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (book.status) {
            PackStatus.OK -> LinearProgressIndicator(
                progress = { (progress ?: 0.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
            PackStatus.MISSING -> Text("File not found", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            PackStatus.CORRUPT -> Text("Damaged", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
