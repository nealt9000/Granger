package app.granger.library

import app.granger.resolve
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.granger.R
import app.granger.core.library.Book
import app.granger.core.library.isBookPack
import app.granger.core.library.isPackBook
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
    val gutenberg by viewModel.gutenbergHeader.collectAsStateWithLifecycle()
    val removed by viewModel.removed.collectAsStateWithLifecycle()
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
                title = {
                    // A Gutenberg collection is called by its subject (Plan GB Decision 2), often long: two lines, smaller.
                    if (gutenberg != null) {
                        Text(
                            loaded?.collection?.shownName()?.resolve().orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(loaded?.collection?.let { viewModel.titleFor(it, it.shownName().resolve()) }.orEmpty())
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
                actions = {
                    if (loaded != null && !loaded.isFolder) Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more)) }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            gutenberg?.let { g ->
                                if (g.kind != HeaderKind.BOOK_SET && g.progress == null && g.searchable < g.books) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.library_make_searchable_menu)) },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch { viewModel.searchableCost()?.let { dialog = CollectionDialog.MakeSearchable(it) } }
                                    },
                                )
                                val pack = g.pack
                                if (pack != null && pack.status == PackStatus.OK) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.library_open_pack_pages)) },
                                    onClick = { menuOpen = false; onOpenPack(pack.id) },
                                )
                                if (pack != null) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.library_remove_pack)) },
                                    onClick = { menuOpen = false; dialog = CollectionDialog.RemovePack(pack) },
                                )
                            }
                            // Removed pack books come back (Plan KB2 Task 4.3).
                            if (removed.isNotEmpty()) DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_show_removed)) },
                                onClick = { menuOpen = false; viewModel.showRemoved() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_move_collection)) },
                                onClick = { menuOpen = false; dialog = CollectionDialog.MoveCollection },
                            )
                            if (loaded.collection.userCreated) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.library_rename)) },
                                    onClick = { menuOpen = false; dialog = CollectionDialog.Rename },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.common_delete)) },
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
                    headlineContent = { Text(child.collection.shownName().resolve()) },
                    supportingContent = { Text(rowCountText(child).resolve()) },
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
                                    text = { Text(stringResource(R.string.library_move_to_collection)) },
                                    onClick = { packMenu = false; dialog = CollectionDialog.MovePack(pack) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.library_remove_from_phone)) },
                                    onClick = { packMenu = false; dialog = CollectionDialog.RemovePack(pack) },
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                })
                gutenberg?.let { g ->
                    add(HeadItem("gutenberg", "gutenberg") { GutenbergHead(g) })
                }
                if (shown.total > 0) {
                    add(HeadItem("find", "find") {
                        OutlinedTextField(
                            value = viewModel.findText,
                            onValueChange = viewModel::setQuery,
                            placeholder = { Text(stringResource(R.string.library_find_title_author)) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = if (viewModel.findText.isEmpty()) null else {
                                { IconButton(onClick = { viewModel.setQuery("") }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.library_clear)) } }
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
                                    FilterChip(selected = sort == option, onClick = { viewModel.setSort(option) }, label = { Text(stringResource(option.label)) })
                                }
                            }
                            ViewToggle(view, viewModel::toggleView)
                        }
                    })
                    // Granger Classics: the catalog's kinds (novels, drama, philosophy, …); one chip shows that kind alone.
                    if (shown.categories.isNotEmpty()) add(HeadItem("categories", "chips") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item(key = "all") {
                                FilterChip(selected = shown.category == null, onClick = { viewModel.setCategory(null) }, label = { Text(stringResource(R.string.sources_all)) })
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
                    // A collection in more than one language (Plan KB2): one chip shows that language alone.
                    if (shown.languages.isNotEmpty()) add(HeadItem("languages", "chips") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item(key = "all") {
                                FilterChip(selected = shown.language == null, onClick = { viewModel.setLanguage(null) }, label = { Text(stringResource(R.string.sources_all)) })
                            }
                            items(shown.languages, key = { it }) { language ->
                                FilterChip(
                                    selected = shown.language == language,
                                    onClick = { viewModel.setLanguage(if (shown.language == language) null else language) },
                                    label = { Text(language) },
                                )
                            }
                        }
                    })
                    if (shown.books.isEmpty()) add(HeadItem("none", "none") {
                        Text(
                            stringResource(R.string.library_no_books_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                        )
                    })
                }
            }
            val open = { book: Book -> scope.launch { viewModel.openBook(book)?.let(onOpened) }; Unit }
            val sectioned = shown.rows
            // A manifest collection in its own order (Plan KB2 Task 2.3): sections, folded sections and works in parts.
            if (sectioned != null && view == ShelfView.LIST) LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                for (h in head) item(key = h.key, contentType = h.contentType) { h.content() }
                items(sectioned, key = { it.key }, contentType = { it::class.simpleName }) { entry ->
                    when (entry) {
                        is SectionRow.Header -> SectionHeaderRow(entry, onToggle = { viewModel.toggleSection(entry.name) })
                        is SectionRow.Work -> WorkRow(entry, onToggle = { viewModel.toggleWork(entry.workKey) })
                        is SectionRow.Item -> Box(if (entry.part) Modifier.padding(start = 24.dp) else Modifier) {
                            val book = entry.book
                            var bookMenu by remember { mutableStateOf(false) }
                            val edition = viewModel.editionOf(book)
                            BookRow(book, positions[book.id]?.totalProgression, onClick = { open(book) }, onLongClick = { bookMenu = true }, edition = edition?.title)
                            BookMenu(
                                book, bookMenu, { bookMenu = false }, onMove = { dialog = CollectionDialog.MoveBook(book) }, onRemove = { dialog = CollectionDialog.RemoveBook(book) },
                                onOriginal = if (edition == null) null else ({ scope.launch { viewModel.openOriginal(book)?.let(onOpened) } }),
                            )
                        }
                    }
                }
            } else if (sectioned != null) androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(104.dp),
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                for (h in head) item(key = h.key, span = { GridItemSpan(maxLineSpan) }, contentType = h.contentType) { h.content() }
                for (entry in sectioned) when (entry) {
                    is SectionRow.Header -> item(key = entry.key, span = { GridItemSpan(maxLineSpan) }, contentType = "section") {
                        SectionHeaderRow(entry, onToggle = { viewModel.toggleSection(entry.name) })
                    }
                    is SectionRow.Work -> item(key = entry.key, contentType = "work") {
                        Box(Modifier.padding(horizontal = 8.dp)) { WorkCell(entry, onToggle = { viewModel.toggleWork(entry.workKey) }) }
                    }
                    is SectionRow.Item -> item(key = entry.key, contentType = "book") {
                        val book = entry.book
                        Box(Modifier.padding(horizontal = 8.dp)) {
                            var bookMenu by remember { mutableStateOf(false) }
                            BookCell(book, positions[book.id]?.totalProgression, onClick = { open(book) }, onLongClick = { bookMenu = true })
                            BookMenu(
                                book, bookMenu, { bookMenu = false }, onMove = { dialog = CollectionDialog.MoveBook(book) }, onRemove = { dialog = CollectionDialog.RemoveBook(book) },
                                onOriginal = viewModel.editionOf(book)?.let { { scope.launch { viewModel.openOriginal(book)?.let(onOpened) } } },
                            )
                        }
                    }
                }
            } else if (view == ShelfView.LIST) ShelfList(
                shown.sections,
                Modifier.padding(padding).fillMaxSize(),
                contentType = "book",
                header = { for (h in head) item(key = h.key, contentType = h.contentType) { h.content() } },
            ) { book ->
                Box {
                    var bookMenu by remember { mutableStateOf(false) }
                    val edition = viewModel.editionOf(book)
                    BookRow(book, positions[book.id]?.totalProgression, onClick = { open(book) }, onLongClick = { bookMenu = true }, edition = edition?.title)
                    BookMenu(
                        book, bookMenu, { bookMenu = false }, onMove = { dialog = CollectionDialog.MoveBook(book) }, onRemove = { dialog = CollectionDialog.RemoveBook(book) },
                        onOriginal = if (edition == null) null else ({ scope.launch { viewModel.openOriginal(book)?.let(onOpened) } }),
                    )
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
                    BookMenu(
                                book, bookMenu, { bookMenu = false }, onMove = { dialog = CollectionDialog.MoveBook(book) }, onRemove = { dialog = CollectionDialog.RemoveBook(book) },
                                onOriginal = viewModel.editionOf(book)?.let { { scope.launch { viewModel.openOriginal(book)?.let(onOpened) } } },
                            )
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
            title = stringResource(R.string.library_remove_from_phone_title),
            // A pack book leaves the list until Show removed (Plan KB2 Task 4.3).
            message = stringResource(if (d.book.origin.isPackBook) R.string.library_remove_pack_book_body else R.string.library_remove_book_body, d.book.title),
            confirm = stringResource(R.string.common_remove),
            onConfirm = { viewModel.removeBook(d.book) },
            onDismiss = { dialog = null },
        )
        is CollectionDialog.MakeSearchable -> MakeSearchableDialog(
            d.cost, documents = gutenberg?.countsDocuments == true, onConfirm = viewModel::makeAllSearchable, onDismiss = { dialog = null },
        )
        is CollectionDialog.RemovePack -> ConfirmDialog(
            title = stringResource(R.string.library_remove_from_phone_title),
            // A web pack's bookmarks and places go with it; a book pack's books keep theirs (Plan KB2 Task 4.2).
            message = stringResource(
                when {
                    !d.pack.isBookPack -> R.string.library_remove_pack_body
                    gutenberg?.countsDocuments == true -> R.string.library_remove_document_pack_body
                    else -> R.string.library_remove_book_pack_body
                },
                d.pack.title,
            ),
            confirm = stringResource(R.string.common_remove),
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
            title = stringResource(R.string.library_rename_collection),
            label = stringResource(R.string.library_collection_name),
            confirm = stringResource(R.string.library_rename),
            initial = collection.name,
            onConfirm = { viewModel.rename(it); dialog = null },
            onDismiss = { dialog = null },
        )
        CollectionDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.library_delete_collection_title),
            message = stringResource(R.string.library_delete_collection_body),
            confirm = stringResource(R.string.common_delete),
            onConfirm = viewModel::delete,
            onDismiss = { dialog = null },
        )
    }
}

/** A row above the books, in either view: a pack, the Find field, the chips. */
private class HeadItem(val key: String, val contentType: String, val content: @Composable () -> Unit)

/**
 * A book's long-press menu. A book Search can't yet read in full says so (Plan GB Decision 6; since Plan KB2 Task 4.5
 * only the exceptions: a pack book not yet opened, a scan). Every book has Remove (Plan KB2 Task 4.3): a pack book's copy goes and its row is hidden.
 */
@Composable
private fun BookMenu(book: Book, open: Boolean, onClose: () -> Unit, onMove: () -> Unit, onRemove: () -> Unit, onOriginal: (() -> Unit)? = null) {
    DropdownMenu(open, onClose) {
        // Plan BK Task 6: a book with a reading edition opens the edition; its own pages are here.
        if (onOriginal != null) DropdownMenuItem(text = { Text(stringResource(R.string.bk_original_scan)) }, onClick = { onClose(); onOriginal() })
        // Searchable in full is the default and isn't said; only an exception is (Plan KB2 Task 4.5).
        val exception = when {
            book.scanTitleOnly -> R.string.library_title_only_scan
            book.titleOnlyUntilOpened -> R.string.library_titles_only
            else -> null
        }
        if (exception != null) DropdownMenuItem(text = { Text(stringResource(exception)) }, onClick = {}, enabled = false)
        DropdownMenuItem(text = { Text(stringResource(R.string.library_move_to_collection)) }, onClick = { onClose(); onMove() })
        DropdownMenuItem(text = { Text(stringResource(R.string.library_remove_from_phone)) }, onClick = { onClose(); onRemove() })
    }
}

/**
 * The lines under a book pack collection's name: how many books it holds, where they come from, how much is searchable
 * in full, and a run's progress. A line each rather than one that wraps mid-phrase at 360 dp; TalkBack reads them as one.
 */
@Composable
private fun GutenbergHead(header: GutenbergHeader) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val all = header.text.resolve()
        Column(Modifier.clearAndSetSemantics { contentDescription = all }) {
            // How many, then where from: one line each, as "24 books · From Kiwix · Federation of American Scientists"
            // wraps mid-phrase at 360 dp.
            Text(header.count.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(header.provider.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Searchable in full is the default and isn't said; only the exceptions are (Plan KB2 Task 4.5).
            header.exceptionText?.let {
                Text(it.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            header.fileNamesText?.let {
                Text(it.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        header.progress?.let { p ->
            val number = java.text.NumberFormat.getIntegerInstance()
            Text(
                stringResource(R.string.library_making_searchable, number.format(p.done), number.format(p.total)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            LinearProgressIndicator(
                progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }
    }
}

/**
 * A section's header in a manifest collection (Plan KB2 Task 2.3), read by TalkBack as a heading. A long section is
 * folded behind it: then it's a button, "Course materials · 643", with its state (expanded or collapsed).
 */
@Composable
private fun SectionHeaderRow(header: SectionRow.Header, onToggle: () -> Unit) {
    val style = MaterialTheme.typography.titleMedium
    val colour = MaterialTheme.colorScheme.primary
    if (!header.foldable) {
        Text(
            header.name,
            style = style,
            color = colour,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
        )
        return
    }
    val count = java.text.NumberFormat.getIntegerInstance().format(header.count)
    val state = stringResource(if (header.expanded) R.string.library_expanded else R.string.library_collapsed)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(if (header.expanded) R.string.library_collapse else R.string.library_expand),
                role = Role.Button,
                onClick = onToggle,
            )
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) { heading(); stateDescription = state },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.library_dotted, header.name, count), style = style, color = colour, modifier = Modifier.weight(1f))
        Icon(if (header.expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = colour)
    }
}

/**
 * A work in parts as one row (Plan KB2 Task 2.3): its title, author and "3 parts"; a tap shows its parts beneath it
 * (the whole file first, when the pack has one), another hides them. A button with its state for TalkBack.
 */
@Composable
private fun WorkRow(work: SectionRow.Work, onToggle: () -> Unit) {
    val state = stringResource(if (work.expanded) R.string.library_expanded else R.string.library_collapsed)
    ShelfRow(
        title = work.title,
        subtitle = work.author,
        cover = { BookCover(work.first, Modifier.height(ShelfCoverSize)) },
        onClick = onToggle,
        modifier = Modifier.semantics { role = Role.Button; stateDescription = state },
        detail = {
            Text(
                pluralStringResource(R.plurals.library_parts, work.parts.size, work.parts.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailing = { Icon(if (work.expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null) },
    )
}

/** A work in parts in the grid: its first file's cover, its title and "3 parts"; a tap shows or hides the parts. */
@Composable
private fun WorkCell(work: SectionRow.Work, onToggle: () -> Unit) {
    val state = stringResource(if (work.expanded) R.string.library_expanded else R.string.library_collapsed)
    Column(
        Modifier
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) { stateDescription = state },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BookCover(work.first, Modifier.fillMaxWidth())
        Text(work.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
        Text(work.author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, minLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(R.plurals.library_parts, work.parts.size, work.parts.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(if (work.expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Gives the size first (Plan GB Decision 5); with too little space, says so and offers nothing to run. A collection of
 * [documents] says documents, not books (Plan KB2 Task 4.2).
 */
@Composable
private fun MakeSearchableDialog(cost: SearchableCost, documents: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val size = Formatter.formatShortFileSize(context, cost.bytes)
    val free = Formatter.formatShortFileSize(context, cost.freeBytes)
    val count = java.text.NumberFormat.getIntegerInstance().format(cost.books)
    if (!cost.fits) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.library_not_enough_space)) },
            text = {
                Text(
                    pluralStringResource(
                        if (documents) R.plurals.library_searchable_no_room_documents else R.plurals.library_searchable_no_room,
                        cost.books, count, size, free,
                    ),
                )
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) } },
        )
    } else {
        ConfirmDialog(
            title = stringResource(R.string.library_make_searchable_title),
            message = pluralStringResource(
                if (documents) R.plurals.library_make_searchable_body_documents else R.plurals.library_make_searchable_body,
                cost.books, count, size, free,
            ),
            confirm = stringResource(R.string.library_make_searchable),
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

private sealed interface CollectionDialog {
    data class MovePack(val pack: Pack) : CollectionDialog
    data class RemovePack(val pack: Pack) : CollectionDialog
    data class MoveBook(val book: Book) : CollectionDialog
    data class RemoveBook(val book: Book) : CollectionDialog
    data class MakeSearchable(val cost: SearchableCost) : CollectionDialog
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
        PackStatus.MISSING -> stringResource(R.string.library_pack_missing)
        PackStatus.CORRUPT -> stringResource(R.string.library_pack_corrupt)
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
                if (replaced) Text(stringResource(R.string.library_replaced_by_classics), color = MaterialTheme.colorScheme.primary)
            }
        },
        trailingContent = if (replaced) {
            { Button(onClick = onGetBooks) { Text(stringResource(R.string.library_get_ebooks)) } }
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
internal fun BookRow(book: Book, progress: Double?, onClick: () -> Unit, onLongClick: () -> Unit, edition: String? = null) {
    val readable = book.status == PackStatus.OK
    val problem = when (book.status) {
        PackStatus.OK -> null
        PackStatus.MISSING -> stringResource(R.string.library_file_not_found)
        PackStatus.CORRUPT -> stringResource(R.string.library_damaged)
    }
    // Plan BK Task 6: a pack book with a reading edition is shown as the edition, which a tap opens.
    val label = if (edition != null) stringResource(R.string.bk_reading_edition) else formatLabel(book)?.resolve()
    ShelfRow(
        title = edition ?: book.title,
        subtitle = if (edition != null) "" else book.author,
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
            { Text(stringResource(R.string.library_percent, (p * 100).roundToInt().coerceIn(1, 100)), style = MaterialTheme.typography.labelMedium.readout, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
            Text(it.resolve(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (book.status) {
            PackStatus.OK -> LinearProgressIndicator(
                progress = { (progress ?: 0.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
            PackStatus.MISSING -> Text(stringResource(R.string.library_file_not_found), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            PackStatus.CORRUPT -> Text(stringResource(R.string.library_damaged), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
