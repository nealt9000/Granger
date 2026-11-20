package app.granger.library

import app.granger.invoke
import app.granger.R
import app.granger.UiText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.archive.ArchiveDownloads
import app.granger.library.shelf.LibraryViewPrefs
import app.granger.library.shelf.ShelfKind
import app.granger.library.shelf.ShelfView
import app.granger.library.shelf.sortNamed
import app.granger.library.shelf.toggled
import app.granger.core.library.Book
import app.granger.core.library.BookOrigin
import app.granger.core.library.BookPosition
import app.granger.core.library.Bookshelf
import app.granger.core.library.CollectionRow
import app.granger.core.library.Filing
import app.granger.core.library.PackBooks
import app.granger.core.library.isBookPack
import app.granger.core.library.BookManifest
import app.granger.core.library.ManifestCollection
import app.granger.core.library.bookSource
import app.granger.core.library.Library
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.core.library.ReadingSessions
import app.granger.core.library.Shelf
import app.granger.core.library.Shelves
import app.granger.core.catalog.CatalogSource
import app.granger.core.downloads.Downloads
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One collection's packs and books, or a folder's collections (`ui.children`). Rename and Delete are offered only
 * when `ui.collection.userCreated`; a folder offers nothing. The books can be found, sorted and, for Granger
 * Classics, browsed by catalog category ([books]); the choices are kept in [savedState], one per collection screen.
 */
class CollectionViewModel(
    private val collectionId: String,
    private val shelves: Shelves,
    private val library: Library,
    private val sessions: ReadingSessions,
    private val bookshelf: Bookshelf,
    private val downloads: Downloads,
    private val appScope: CoroutineScope,
    /** Where archive.org sidecars live (R2); null leaves them alone. */
    private val archiveSidecarDir: java.io.File? = null,
    /** Where a Granger book's category comes from; null offers no category chips. */
    private val catalog: CatalogSource? = null,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    /** The books shelf's remembered sort and view. */
    private val viewPrefs: LibraryViewPrefs = LibraryViewPrefs(),
    /** Where the books are filtered and sorted, off the main thread. */
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    /** "Make all searchable…" for a Gutenberg collection (Plan GB); null offers it nowhere. */
    private val gutenberg: GutenbergShelf? = null,
    /** What a Kiwix book pack's manifest says of its collection (Plan KB2); read off the main thread. */
    private val manifestOf: (Pack) -> ManifestCollection? = ::manifestCollectionOf,
    /** A language code's name for the language chips ("eng" → "English"). */
    private val languageName: (String) -> String = app.granger.sources.KiwixList::languageName,
    /** A book set's level as its section is headed, in the app's language ("Level 2", "Nivel 2"; Plan KB2 Task 3.2). */
    private val levelName: (Int) -> String = { "Level $it" },
) : ViewModel() {
    /** Null until loaded, and again if the collection goes away (emptied or deleted). */
    val ui: StateFlow<CollectionRow?> =
        shelves.collection(collectionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Where the reader left off in each book, by book id. */
    val positions: StateFlow<Map<String, BookPosition>> =
        bookshelf.positions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The Find field's text, held as Compose state so typing never waits on a flow. */
    var findText by mutableStateOf(savedState[QUERY] ?: "")
        private set
    /** The Find text: a title or author, matched from the start of a word. */
    val query: StateFlow<String> = savedState.getStateFlow(QUERY, "")
    val sort: StateFlow<BookSort> = viewPrefs.sort(ShelfKind.BOOKS)
        .map { sortNamed(it, BookSort.TITLE) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, sortNamed(viewPrefs.sort(ShelfKind.BOOKS).value, BookSort.TITLE))

    /** List (the default) or the cover grid. */
    val view: StateFlow<ShelfView> = viewPrefs.view(ShelfKind.BOOKS)
    private val category: StateFlow<String?> = savedState.getStateFlow(CATEGORY, null)

    /**
     * What the catalog says of the books here: each Granger book's category by id, in catalog order, and each set book's
     * reading level (Plan KB2 Task 3.2: African Storybook), which heads its section.
     */
    private val facts = (catalog?.state ?: flowOf(null))
        .map { state ->
            val catalog = state?.catalog
            CatalogFacts(
                catalog?.books.orEmpty().associate { it.id to it.category },
                catalog?.setBooks.orEmpty().filter { it.level > 0 }.associate { it.id to it.level },
            )
        }
        .distinctUntilChanged()

    private data class CatalogFacts(val categories: Map<String, String>, val levels: Map<String, Int>)

    /** The catalog's book pack providers by collection key (Plan KB2), for a header whose manifest names no one. */
    private val providers: Map<String, String> get() = bookPackProviders(catalog?.state?.value?.catalog)

    private data class Choices(val query: String, val sort: BookSort, val category: String?)

    /** A book pack collection's pack, when installed; null for any other collection. */
    private val bookPack = combine(ui, library.packs) { row, packs ->
        val key = row?.collection?.defaultKey?.takeIf(Filing::isBookPackCollection) ?: return@combine null
        packs.firstOrNull { it.isBookPack && Filing.defaultKey(it) == key }
    }.distinctUntilChanged()

    /** What a Kiwix book pack's manifest says of its collection (Plan KB2): its provider, noun and sections. */
    val manifest: StateFlow<ManifestCollection?> = bookPack
        .map { pack -> pack?.takeIf { it.bookSource == BookOrigin.KIWIX } }
        .distinctUntilChanged { a, b -> a?.id == b?.id && a?.bookManifest == b?.bookManifest && a?.title == b?.title }
        .map { pack -> pack?.let(manifestOf) }
        .flowOn(compute)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val openSections = savedState.getStateFlow(OPEN_SECTIONS, ArrayList<String>())
    private val openWorks = savedState.getStateFlow(OPEN_WORKS, ArrayList<String>())
    private val language = savedState.getStateFlow<String?>(LANGUAGE, null)

    private val layout = combine(manifest, openSections, openWorks, language) { m, sections, works, lang ->
        CollectionLayout(m?.groups.orEmpty(), sections.toSet(), works.toSet(), lang)
    }

    /** The books to show, after Find, the language, the category and the sort; a manifest collection in its own order. */
    val books: StateFlow<CollectionBooks> = combine(
        ui.map { it?.books.orEmpty() }.distinctUntilChanged(),
        combine(query, sort, category, ::Choices),
        facts,
        positions,
        layout,
    ) { list, c, f, read, l -> collectionBooks(withLevels(list, f.levels, levelName), c.query, c.sort, c.category, f.categories, read, l, languageName) }
        .flowOn(compute)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionBooks())

    /** Opens or folds a long section of a manifest collection ("Course materials · 643"). */
    fun toggleSection(name: String) {
        savedState[OPEN_SECTIONS] = ArrayList(openSections.value.toggled(name))
    }

    /** Shows or hides a work's parts. */
    fun toggleWork(workKey: String) {
        savedState[OPEN_WORKS] = ArrayList(openWorks.value.toggled(workKey))
    }

    /** Null shows every language. */
    fun setLanguage(name: String?) { savedState[LANGUAGE] = name }

    private fun List<String>.toggled(item: String): List<String> = if (item in this) this - item else this + item

    fun setQuery(text: String) {
        findText = text
        savedState[QUERY] = text
    }

    fun setSort(sort: BookSort) = viewPrefs.setSort(ShelfKind.BOOKS, sort.name)

    fun toggleView() = viewPrefs.setView(ShelfKind.BOOKS, view.value.toggled())

    /** Null shows every category. */
    fun setCategory(category: String?) { savedState[CATEGORY] = category }

    /**
     * The pack books removed from this collection (Plan KB2 Task 4.3): hidden, their copies gone, for "Show removed".
     * Empty for any other collection.
     */
    val removed: StateFlow<List<Book>> = combine(ui.map { it?.collection?.defaultKey }.distinctUntilChanged(), bookshelf.books) { key, books ->
        if (key == null || !Filing.isBookPackCollection(key)) emptyList() else books.filter { it.hidden && Filing.inBookPackCollection(it, key) }
    }.flowOn(compute).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Puts the removed books back in the collection, to be copied out again when opened. */
    fun showRemoved() {
        val ids = removed.value.map { it.id }
        if (ids.isNotEmpty()) appScope.launch { bookshelf.unhide(ids) }
    }

    /** Ids of the packs here that the Granger Classics e-books replace. */
    val superseded: StateFlow<Set<String>> = ui
        .map { row -> row?.packs.orEmpty().filter(Filing::isSuperseded).map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * A book pack's collection's header (Plan GB Decision 5, Plan KB Decision 7): its pack, when installed, for the
     * overflow; where it comes from ("From Kiwix · Project Gutenberg"); how many books it lists and how many are searchable
     * in full; and the "Make all searchable…" run, if one is going. Null for any other collection.
     */
    val gutenbergHeader: StateFlow<GutenbergHeader?> = combine(
        ui, library.packs, gutenberg?.progress ?: flowOf(emptyMap()), manifest,
    ) { row, packs, runs, m ->
        // A catalog book set's collection (Plan KB2 Task 3.2): "693 books · African Storybook Initiative".
        row?.collection?.defaultKey?.takeIf(Filing::isBookSetCollection)?.let { key ->
            return@combine GutenbergHeader(
                null, row.books.size, row.books.count { it.searchableInFull }, kind = HeaderKind.BOOK_SET,
                providerName = Filing.bookSetProvider(key), documents = false,
                unopened = row.books.count { it.titleOnlyUntilOpened }, scans = row.books.count { it.scanTitleOnly },
            )
        }
        val key = row?.collection?.defaultKey?.takeIf(Filing::isBookPackCollection) ?: return@combine null
        val pack = packs.firstOrNull { it.isBookPack && Filing.defaultKey(it) == key }
        val packName = pack?.name ?: row.books.firstOrNull()?.let(PackBooks::packNameOf)
        // A work in parts counts once (Plan KB2): its whole and parts share a work key; so in the exceptions.
        fun counted(books: List<Book>) = if (m != null) books.distinctBy { it.workKey ?: it.id }.size else books.size
        val header = GutenbergHeader(
            pack, counted(row.books), row.books.count { it.searchableInFull }, packName?.let(runs::get),
            unopened = counted(row.books.filter { it.titleOnlyUntilOpened }), scans = counted(row.books.filter { it.scanTitleOnly }),
        )
        if (Filing.isKiwixBooksCollection(key)) {
            header.copy(
                kind = HeaderKind.KIWIX_LIBRARY,
                fromFileNames = m?.fromFileNames ?: (pack?.bookManifest == Pack.BOOK_FILES),
                // The manifest's maker, else the catalog's (Plan KB2: a nautilus pack's, "Prunelle").
                providerName = m?.provider?.takeIf { it.isNotBlank() } ?: providers[key],
                documents = m?.documents ?: true,
            )
        } else {
            header
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** What "Make all searchable…" would take here; null when it isn't offered. */
    suspend fun searchableCost(): SearchableCost? {
        val shelf = gutenberg ?: return null
        val books = ui.value?.books ?: return null
        return shelf.cost(books)
    }

    /** Copies out and indexes every book here not yet on the phone, in the app's scope; leaving doesn't stop it. */
    fun makeAllSearchable() {
        val books = ui.value?.books.orEmpty()
        val packName = gutenbergHeader.value?.pack?.name ?: books.firstOrNull()?.let(PackBooks::packNameOf) ?: return
        gutenberg?.makeSearchable(packName, books)
    }

    /** Every media type and collection a pack or collection can move to. */
    val targets: StateFlow<List<Shelf>> =
        shelves.shelves.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Opens [book] in its rail tab (one per book) and returns the tab id; null, and nothing happens, unless it is readable. */
    suspend fun openBook(book: Book): Long? {
        if (book.status != PackStatus.OK) return null
        return sessions.openTab(book.id, "", book.title)
    }

    fun moveBook(book: Book, toCollectionId: String) {
        viewModelScope.launch { shelves.moveBook(book, toCollectionId) }
    }

    fun moveBookToNewCollection(book: Book, mediaTypeId: String, name: String) {
        viewModelScope.launch { shelves.moveBook(book, shelves.createCollection(name.trim(), mediaTypeId)) }
    }

    fun moveBookToNewMediaType(book: Book, mediaTypeName: String, collectionName: String) {
        viewModelScope.launch {
            val type = shelves.createMediaType(mediaTypeName.trim())
            shelves.moveBook(book, shelves.createCollection(collectionName.trim(), type))
        }
    }

    /**
     * Deletes the book's file from the phone; its place, bookmarks and highlights are kept. A Granger book's download
     * record goes first so it can be fetched again; an archive.org book's metadata sidecar goes last. A pack book's row
     * is hidden until "Show removed" (Plan KB2 Task 4.3). Runs in the app scope so leaving the screen can't cut it short.
     */
    fun removeBook(book: Book) {
        appScope.launch {
            when (book.origin) {
                BookOrigin.GRANGER -> downloads.remove(book.sourceRef)
                BookOrigin.ARCHIVE_ORG -> downloads.remove(ArchiveDownloads.itemId(book.sourceRef))
                else -> Unit
            }
            bookshelf.remove(book.id)
            if (book.origin == BookOrigin.ARCHIVE_ORG) archiveSidecarDir?.let { ArchiveDownloads.deleteSidecar(it, book.sourceRef) }
        }
    }

    fun movePack(pack: Pack, toCollectionId: String) {
        viewModelScope.launch { shelves.movePack(pack, toCollectionId) }
    }

    fun movePackToNewCollection(pack: Pack, mediaTypeId: String, name: String) {
        viewModelScope.launch { shelves.movePack(pack, shelves.createCollection(name.trim(), mediaTypeId)) }
    }

    fun movePackToNewMediaType(pack: Pack, mediaTypeName: String, collectionName: String) {
        viewModelScope.launch {
            val type = shelves.createMediaType(mediaTypeName.trim())
            shelves.movePack(pack, shelves.createCollection(collectionName.trim(), type))
        }
    }

    fun moveCollection(toMediaTypeId: String) {
        viewModelScope.launch { shelves.moveCollection(collectionId, toMediaTypeId) }
    }

    fun moveCollectionToNewMediaType(name: String) {
        viewModelScope.launch { shelves.moveCollection(collectionId, shelves.createMediaType(name.trim())) }
    }

    fun rename(name: String) {
        viewModelScope.launch { shelves.renameCollection(collectionId, name.trim()) }
    }

    fun delete() {
        viewModelScope.launch { shelves.deleteCollection(collectionId) }
    }

    /** Deletes the pack's file from the phone. */
    fun remove(pack: Pack) {
        viewModelScope.launch { library.remove(pack.id) }
    }

    private companion object {
        const val QUERY = "query"
        const val CATEGORY = "category"
        const val OPEN_SECTIONS = "openSections"
        const val OPEN_WORKS = "openWorks"
        const val LANGUAGE = "language"
    }
}

/**
 * [books] with each set book's reading level ([levels] by catalog id) as its section and order (Plan KB2 Task 3.2): an
 * African Storybook collection shows "Level 1" … "Level 5", lowest first, as a manifest collection shows its sections.
 * Done as they're shown, not stored, so a level reads in the app's language ([levelName]) and needs no new column.
 */
internal fun withLevels(books: List<Book>, levels: Map<String, Int>, levelName: (Int) -> String): List<Book> =
    if (levels.isEmpty()) books else books.map { book ->
        val level = if (book.origin == BookOrigin.GRANGER) levels[book.sourceRef] else null
        if (level == null) book else book.copy(groupName = levelName(level), ord = level)
    }

/**
 * What a Kiwix book pack's manifest says of its collection (Plan KB2), read from the `.gbooks` file [Pack.bookManifest]
 * names; a pack listed by its files' names says only that. Blocking.
 */
fun manifestCollectionOf(pack: Pack): ManifestCollection? = when (val manifest = pack.bookManifest) {
    null -> null
    Pack.BOOK_FILES -> ManifestCollection(pack.title, ManifestCollection.NOUN_DOCUMENTS, "", emptyList(), fromFileNames = true)
    else -> BookManifest.collectionOf(manifest)
}

/**
 * Whether a book pack's books are counted as documents (Plan KB2 Task 4.2): a nautilus pack's always, a manifest pack's
 * when its manifest's noun says so (or while it has none yet), a Gutenberg pack's never. Blocking: it may read the
 * manifest.
 */
fun countsDocuments(pack: Pack): Boolean = when (pack.bookSource) {
    BookOrigin.NAUTILUS -> true
    BookOrigin.KIWIX -> manifestCollectionOf(pack)?.documents ?: true
    else -> false
}

/**
 * Whose a book pack's collection is: Project Gutenberg's books, or a Kiwix pack's books or documents (Plan KB Decision 7);
 * or a catalog book set's, which Granger publishes (Plan KB2 Task 3.2: African Storybook).
 */
enum class HeaderKind { GUTENBERG, KIWIX_LIBRARY, BOOK_SET }

/**
 * A book pack's collection's [pack] (null once it's gone), its [books], how many are [searchable] in full, and a run's
 * [progress]; [kind] says whose it is and what it holds.
 */
data class GutenbergHeader(
    val pack: Pack?,
    val books: Int,
    val searchable: Int,
    val progress: SearchableProgress? = null,
    val kind: HeaderKind = HeaderKind.GUTENBERG,
    /** A pack listed with no manifest (Plan KB2 Task 2.2): its titles are its files' names, and the header says so. */
    val fromFileNames: Boolean = false,
    /** Who made a Kiwix collection, as its manifest names them ("Federation of American Scientists"); null says "From Kiwix" alone. */
    val providerName: String? = null,
    /** Whether a Kiwix collection counts documents (nautilus packs, and a manifest whose noun says so) rather than books. */
    val documents: Boolean = true,
    /** Its books Search finds by title only until they're opened ([titleOnlyUntilOpened]). */
    val unopened: Int = 0,
    /** Its scans, found by title only ([scanTitleOnly]). */
    val scans: Int = 0,
) {
    /** Whether its books are counted as documents ("17 documents"): a Kiwix collection whose noun says so. */
    val countsDocuments: Boolean get() = kind == HeaderKind.KIWIX_LIBRARY && documents

    /**
     * Where it comes from (Plan KB2 Task 4.2, one pattern for every book pack): "From Kiwix · Project Gutenberg",
     * "From Kiwix · Federation of American Scientists", or "From Kiwix" when the manifest names no one.
     */
    val provider: UiText get() = when (kind) {
        HeaderKind.GUTENBERG -> UiText.Res(R.string.library_from_kiwix_by, app.granger.core.library.GutenbergNames.PROVIDER)
        HeaderKind.KIWIX_LIBRARY -> providerName?.let { UiText.Res(R.string.library_from_kiwix_by, it) } ?: UiText.Res(R.string.library_from_kiwix)
        // Granger publishes a set's books: the maker alone, no "From Kiwix".
        HeaderKind.BOOK_SET -> UiText.Raw(providerName.orEmpty())
    }

    /** "1,284 books", "17 documents". */
    val count: UiText get() = UiText.Plural(
        if (countsDocuments) R.plurals.library_documents else R.plurals.library_books_counted, books, number(books),
    )

    /** "1,284 books · From Kiwix · Project Gutenberg", "17 documents · From Kiwix". */
    val provenance: UiText get() = UiText.Res(R.string.library_dotted, count, provider)

    /**
     * The exceptions to "searchable in full", the default, which isn't said (Plan KB2 Task 4.5): "12 titles only until
     * opened", "3 scans: titles only", or both; null when every book is searchable in full. When it's every book, the
     * count isn't said again: "65 books · … · Titles only until opened".
     */
    val exceptionText: UiText? get() = listOfNotNull<UiText>(
        when {
            unopened <= 0 -> null
            unopened >= books -> UiText.Res(R.string.library_all_titles_only_until_opened)
            else -> UiText.Plural(R.plurals.library_titles_only_until_opened, unopened, number(unopened))
        },
        when {
            scans <= 0 -> null
            scans >= books -> UiText.Res(R.string.library_all_scans_titles_only)
            else -> UiText.Plural(R.plurals.library_scans_titles_only, scans, number(scans))
        },
    ).reduceOrNull { line, part -> UiText.Res(R.string.library_dotted, line, part) }

    /** "Titles from file names", for a collection listed with no manifest; null otherwise. */
    val fileNamesText: UiText? get() = if (fromFileNames) UiText.Res(R.string.library_titles_from_file_names) else null

    /** "1,284 books · From Kiwix · Project Gutenberg · 1,247 titles only until opened", and "Titles from file names" when it applies. */
    val text: UiText get() = listOfNotNull<UiText>(provenance, exceptionText, fileNamesText)
        .reduce { line, part -> UiText.Res(R.string.library_dotted, line, part) }

    private fun number(n: Int) = java.text.NumberFormat.getIntegerInstance().format(n)
}
