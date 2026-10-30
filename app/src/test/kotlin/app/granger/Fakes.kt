package app.granger

import app.granger.core.books.PdfToEpub
import app.granger.core.library.AlbumSummary
import app.granger.core.library.Audiobook
import app.granger.core.library.Filing
import app.granger.core.library.Book
import app.granger.core.library.BookAnnotation
import app.granger.core.library.BookDetails
import app.granger.core.library.BookFormat
import app.granger.core.library.BookIds
import app.granger.core.library.BookOrigin
import app.granger.core.library.BookPosition
import app.granger.core.library.Bookshelf
import app.granger.core.library.ImportResult
import app.granger.core.library.Bookmark
import app.granger.core.library.CollectionRow
import app.granger.core.library.HistoryEntry
import app.granger.core.library.Library
import app.granger.core.library.MAX_BACK_STACK
import app.granger.core.library.MAX_RAIL_PINS
import app.granger.core.library.PinResult
import app.granger.core.library.RailPin
import app.granger.core.library.RailPins
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.core.library.ReadingSessions
import app.granger.core.library.ReadingTab
import app.granger.core.library.RecentSearch
import app.granger.core.library.Shelf
import app.granger.core.library.Shelves
import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimHit
import app.granger.core.zim.ZimItem
import app.granger.core.zim.ZimMetadata
import app.granger.ebook.BookOpener
import app.granger.settings.AppearancePrefs
import app.granger.settings.OpenTo
import app.granger.settings.RailSide
import app.granger.settings.ThemeChoice
import app.granger.shell.RailFour
import app.granger.shell.RailHidden
import app.granger.shell.RailOrder
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.services.PerResourcePositionsService
import org.readium.r2.shared.publication.services.content.DefaultContentService
import org.readium.r2.shared.publication.services.content.contentServiceFactory
import org.readium.r2.shared.publication.services.content.iterators.HtmlResourceContentIterator
import org.readium.r2.shared.publication.services.positionsServiceFactory
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.resource.InMemoryResource
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.mediatype.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.InputStream

fun pack(id: String, title: String = id, status: PackStatus = PackStatus.OK) =
    Pack(id, "/packs/$id.zim", title, "", "eng", "2026-10-15", 1_000L, true, status, 0L)

class FakeZimArchive(
    override val uuid: String = "p",
    private val paths: Set<String> = setOf("index"),
    override val mainPath: String? = "index",
    private val redirects: Map<String, String> = emptyMap(),
    var random: String? = null,
    var suggestions: List<ZimHit> = emptyList(),
    var results: List<ZimHit> = emptyList(),
    /** HTML per path, for the pages that need a body (read-aloud); others read as empty. */
    val contents: Map<String, String> = emptyMap(),
) : ZimArchive {
    override val metadata = ZimMetadata(uuid, "", "eng", "2026-10-15", uuid)
    override val hasFulltextIndex = true
    override fun resolvePath(path: String) = (redirects[path] ?: path).takeIf { it in paths || it in contents }
    override fun read(path: String) = resolvePath(path)?.let { ZimItem(it, "text/html; charset=utf-8", contents[it]?.toByteArray() ?: ByteArray(0)) }
    override fun randomPath() = random
    var suggestCalls = 0
    override fun suggest(query: String, limit: Int): List<ZimHit> {
        suggestCalls++
        return suggestions.take(limit)
    }
    override fun search(query: String, limit: Int) = results.take(limit)
    override fun close() {}
}

class FakeLibrary : Library {
    val packList = MutableStateFlow<List<Pack>>(emptyList())
    val bookmarkList = MutableStateFlow<List<Bookmark>>(emptyList())
    val archives = mutableMapOf<String, ZimArchive>()
    val lastRead = mutableMapOf<String, String>()
    val positions = mutableMapOf<Pair<String, String>, Float>()
    var refreshes = 0

    override val packs: Flow<List<Pack>> = packList
    override val bookmarks: Flow<List<Bookmark>> = bookmarkList

    val removed = mutableListOf<String>()

    override suspend fun remove(packId: String): Boolean {
        removed += packId
        packList.update { list -> list.filterNot { it.id == packId } }
        bookmarkList.update { list -> list.filterNot { it.packId == packId } }
        return true
    }

    override suspend fun refresh() { refreshes++ }

    val replacedFiles = mutableListOf<String>()
    override suspend fun fileReplaced(path: String) { replacedFiles += path }
    override fun archive(packId: String) = archives[packId]
    override fun cachedArchive(packId: String) = archives[packId]
    override suspend fun startPath(packId: String) = lastRead[packId] ?: archives[packId]?.mainPath

    override fun isBookmarked(packId: String, path: String) =
        bookmarkList.map { list -> list.any { it.packId == packId && it.path == path } }

    override suspend fun setBookmarked(packId: String, path: String, title: String, bookmarked: Boolean) {
        bookmarkList.update { list ->
            val others = list.filterNot { it.packId == packId && it.path == path }
            if (bookmarked) listOf(Bookmark(packId, path, title, 0L)) + others else others
        }
    }

    override suspend fun savePosition(packId: String, path: String, scrollFraction: Float) {
        positions[packId to path] = scrollFraction
    }

    override suspend fun position(packId: String, path: String) = positions[packId to path]
}

/** In-memory [ReadingSessions]; tabs and history keep whatever order tests put them in. */
class FakeReadingSessions : ReadingSessions {
    val tabs = MutableStateFlow<List<ReadingTab>>(emptyList())
    val historyList = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val searches = MutableStateFlow<List<String>>(emptyList())
    val closed = mutableListOf<Long>()
    private var clock = 1_000L

    override fun railTabs(limit: Int): Flow<List<ReadingTab>> =
        tabs.map { list -> list.filter { it.onRail }.sortedByDescending { it.openedAt }.take(limit) }

    override val history: Flow<List<HistoryEntry>> = historyList

    override fun recentSearches(limit: Int): Flow<List<String>> = searches.map { it.take(limit) }

    /** Timed rows for [recentSearchRows]; when empty, [searches] stand in with time 0. */
    val searchRows = MutableStateFlow<List<RecentSearch>>(emptyList())

    override fun recentSearchRows(limit: Int): Flow<List<RecentSearch>> =
        combine(searches, searchRows) { list, rows -> rows.ifEmpty { list.map { RecentSearch(it, 0L) } }.take(limit) }

    override suspend fun openTab(packId: String, path: String, title: String): Long {
        val time = ++clock
        historyList.update { list ->
            listOf(HistoryEntry(packId, path, title, time)) + list.filterNot { it.packId == packId && it.path == path }
        }
        val existing = tabs.value.firstOrNull { it.onRail && it.packId == packId && it.path == path }
        if (existing != null) {
            tabs.update { list -> list.map { if (it.id == existing.id) it.copy(title = title, openedAt = time) else it } }
            return existing.id
        }
        val id = (tabs.value.maxOfOrNull { it.id } ?: 0L) + 1
        tabs.update { it + ReadingTab(id, packId, path, title, time, onRail = true, backStack = path) }
        return id
    }

    override suspend fun newTab(packId: String, path: String, title: String): Long {
        val time = ++clock
        historyList.update { list ->
            listOf(HistoryEntry(packId, path, title, time)) + list.filterNot { it.packId == packId && it.path == path }
        }
        val id = (tabs.value.maxOfOrNull { it.id } ?: 0L) + 1
        tabs.update { it + ReadingTab(id, packId, path, title, time, onRail = true, backStack = path) }
        return id
    }

    override suspend fun showInTab(tabId: Long, packId: String, path: String, title: String) {
        tabs.update { list -> list.map { if (it.id == tabId) it.copy(packId = packId, path = path, title = title) else it } }
    }

    /** As the Room version: the stack is `\n`-joined, oldest first, with the shown page not repeated. */
    override suspend fun pushPath(tabId: Long, path: String, title: String) {
        tabs.update { list ->
            list.map { tab ->
                if (tab.id != tabId) return@map tab
                val stack = tab.backStack.split('\n').filter { it.isNotEmpty() }
                val pushed = if (stack.lastOrNull() == path) stack else (stack + path).takeLast(MAX_BACK_STACK)
                tab.copy(path = path, title = title, backStack = pushed.joinToString("\n"))
            }
        }
    }

    override suspend fun popPath(tabId: Long): String? {
        val tab = tab(tabId) ?: return null
        val stack = tab.backStack.split('\n').filter { it.isNotEmpty() }
        if (stack.size < 2) return null
        val left = stack.dropLast(1)
        tabs.update { list -> list.map { if (it.id == tabId) it.copy(backStack = left.joinToString("\n")) else it } }
        return left.last()
    }

    override suspend fun closeTab(tabId: Long) {
        closed += tabId
        tabs.update { list -> list.map { if (it.id == tabId) it.copy(onRail = false) else it } }
    }

    override suspend fun lastTab(): ReadingTab? = tabs.value.filter { it.onRail }.maxByOrNull { it.openedAt }

    override suspend fun tab(id: Long): ReadingTab? = tabs.value.firstOrNull { it.id == id }

    override suspend fun recordSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) searches.update { listOf(trimmed) + (it - trimmed) }
    }

    override suspend fun forgetSearch(query: String) {
        val trimmed = query.trim()
        searches.update { it - trimmed }
        searchRows.update { rows -> rows.filterNot { it.query == trimmed } }
    }

    override suspend fun clearHistory(keepTabIds: Set<Long>, keepSearches: Set<String>) {
        historyList.value = emptyList()
        searches.update { list -> list.filter { it in keepSearches } }
        searchRows.update { rows -> rows.filter { it.query in keepSearches } }
        tabs.update { list -> list.map { if (it.id in keepTabIds) it else it.copy(onRail = false) } }
    }
}

/** In-memory [Shelves] serving whatever [shelfList] holds (a folder's children too); writes are recorded, not applied. */
class FakeShelves : Shelves {
    val shelfList = MutableStateFlow<List<Shelf>>(emptyList())
    val moves = mutableListOf<Pair<String, String>>()
    private var nextId = 0

    override val shelves: Flow<List<Shelf>> = shelfList

    override fun collection(id: String): Flow<CollectionRow?> =
        shelfList.map { shelves -> shelves.flatMap { it.allCollections }.firstOrNull { it.collection.id == id } }

    override suspend fun movePack(pack: Pack, toCollectionId: String) { moves += pack.id to toCollectionId }
    override suspend fun moveBook(book: Book, toCollectionId: String) { moves += book.id to toCollectionId }
    override suspend fun moveAlbum(album: AlbumSummary, toCollectionId: String) { moves += Filing.albumKey(album) to toCollectionId }
    override suspend fun moveAudiobook(book: Audiobook, toCollectionId: String) { moves += book.id to toCollectionId }
    override suspend fun moveCollection(collectionId: String, toMediaTypeId: String) {}
    override suspend fun createMediaType(name: String) = "new-${nextId++}"
    override suspend fun deleteMediaType(id: String) = false
    override suspend fun createCollection(name: String, mediaTypeId: String) = "new-${nextId++}"
    override suspend fun renameCollection(id: String, name: String) {}
    override suspend fun deleteCollection(id: String) {}
}

fun book(id: String, title: String = id, author: String = "", status: PackStatus = PackStatus.OK, coverPath: String? = null) =
    Book(
        id = id, origin = BookOrigin.GRANGER, sourceRef = id, filePath = "/books/$id.epub", title = title, author = author,
        translator = "", date = "", language = "eng", subjects = "", description = "", coverPath = coverPath,
        sizeBytes = 1_000L, status = status, indexedAt = null, addedAt = 0L,
    )

/** In-memory [Bookshelf]: [bookList] and [positionMap] are what tests set; writes are applied to them. */
class FakeBookshelf : Bookshelf {
    val bookList = MutableStateFlow<List<Book>>(emptyList())
    val positionMap = MutableStateFlow<Map<String, BookPosition>>(emptyMap())
    var refreshes = 0
    val removed = mutableListOf<String>()

    override val books: Flow<List<Book>> = bookList
    override val positions: Flow<Map<String, BookPosition>> = positionMap
    override suspend fun book(id: String): Book? = bookList.value.firstOrNull { it.id == id }
    override suspend fun refresh() { refreshes++ }
    override suspend fun addDownloaded(file: File, origin: BookOrigin, ref: String, details: BookDetails?): Book? = null
    override suspend fun import(displayName: String, open: () -> InputStream): ImportResult = ImportResult.Unsupported
    override suspend fun remove(id: String): Boolean {
        removed += id
        bookList.update { list -> list.filterNot { it.id == id } }
        return true
    }

    /** Original pages by ebook id (Plan IA): the hidden PDF book and its size. */
    val originalPages = mutableMapOf<String, Pair<Book, Long>>()

    /** What Read as ebook gives, by book id; a passing report also turns the book into an EPUB. */
    val ebookReports = mutableMapOf<String, PdfToEpub.Report>()
    override suspend fun originalPages(bookId: String): Book? = originalPages[bookId]?.first
    override suspend fun originalPagesBytes(bookId: String): Long? = originalPages[bookId]?.second
    override suspend fun removeOriginalPages(bookId: String): Boolean = originalPages.remove(bookId) != null
    override suspend fun readAsEbook(bookId: String): PdfToEpub.Report? {
        val report = ebookReports[bookId] ?: return null
        if (report.passes) bookList.update { list -> list.map { if (it.id == bookId) it.copy(format = BookFormat.EPUB) else it } }
        return report
    }

    /** Pack documents registered, as (zimId, path, title); each becomes a PDF book whose file is the one given. */
    val packDocuments = mutableListOf<Triple<String, String, String>>()

    /** The link text each registration in [packDocuments] was given. */
    val packDocumentLinkTexts = mutableListOf<String>()

    /** The pack's listed title each registration in [packDocuments] was given. */
    val packDocumentListedTitles = mutableListOf<String>()
    override suspend fun registerPackDocument(
        zimId: String, path: String, title: String, file: File, linkText: String, listedTitle: String,
    ): Book {
        packDocuments += Triple(zimId, path, title)
        packDocumentLinkTexts += linkText
        packDocumentListedTitles += listedTitle
        val book = book(BookIds.of(BookOrigin.PACK, "$zimId/$path"), title).copy(
            origin = BookOrigin.PACK, sourceRef = "$zimId/$path", filePath = file.absolutePath, format = BookFormat.PDF,
        )
        bookList.update { list -> list.filterNot { it.id == book.id } + book }
        return book
    }

    override suspend fun removePackDocuments(zimId: String) {
        bookList.update { list -> list.filterNot { it.origin == BookOrigin.PACK && it.sourceRef.startsWith("$zimId/") } }
    }

    override suspend fun savePosition(bookId: String, locatorJson: String, totalProgression: Double, chapterTitle: String) {
        positionMap.update { it + (bookId to BookPosition(bookId, locatorJson, totalProgression, chapterTitle, 0L)) }
    }

    override suspend fun position(bookId: String): BookPosition? = positionMap.value[bookId]
    /** Oldest first, like the DAO. */
    val markList = MutableStateFlow<List<BookAnnotation>>(emptyList())
    private var nextMarkId = 1L
    override fun marks(bookId: String): Flow<List<BookAnnotation>> = markList.map { all -> all.filter { it.bookId == bookId } }
    override suspend fun addMark(mark: BookAnnotation): Long {
        val id = nextMarkId++
        markList.update { it + mark.copy(id = id) }
        return id
    }
    override suspend fun updateMark(mark: BookAnnotation) { markList.update { all -> all.map { if (it.id == mark.id) mark else it } } }
    override suspend fun deleteMark(id: Long) { markList.update { all -> all.filterNot { it.id == id } } }
}

/** Records whether the publication holding it was closed. */
class TrackingContainer : Container<Resource> {
    var closed = false
    override val entries: Set<Url> = emptySet()
    override fun get(url: Url): Resource? = null
    override fun close() { closed = true }
}

/**
 * An EPUB-like publication whose reading order is [chapters] (decoded paths, which Readium percent-encodes), one
 * position per chapter so progressions resolve. [container] shows whether it was closed.
 */
fun testPublication(
    title: String = "Test Book",
    chapters: List<String> = listOf("c001.xhtml", "c002.xhtml", "c003.xhtml"),
    container: TrackingContainer = TrackingContainer(),
    /** Table of contents: each entry's href (a URL, fragment allowed; its title is the same text) and its children's. */
    toc: List<Pair<String, List<String>>> = emptyList(),
): Publication = Publication(
    manifest = Manifest(
        metadata = Metadata(localizedTitle = LocalizedString(title)),
        readingOrder = chapters.map { Link(Url.fromDecodedPath(it)!!, mediaType = MediaType.XHTML) },
        tableOfContents = toc.map { (href, children) ->
            Link(Url(href)!!, title = href, children = children.map { Link(Url(it)!!, title = it) })
        },
    ),
    container = container,
    servicesBuilder = Publication.ServicesBuilder().apply {
        positionsServiceFactory = PerResourcePositionsService.createFactory(MediaType.XHTML)
    },
)

/**
 * A PDF publication of [pages] pages, as Readium's PDF parser makes one: one reading-order item, positions one per
 * page (with the `page=n` fragment), and an outline of [outline] (title to 1-based page) as `#page=n` links.
 */
fun testPdfPublication(
    title: String = "The Water Book",
    pages: Int = 5,
    outline: List<Pair<String, Int>> = listOf("Boiling" to 1, "Filters" to 2, "Storage" to 4),
): Publication {
    val href = Url("doc.pdf")!!
    val positions = (1..pages).map { n ->
        Locator(
            href = href,
            mediaType = MediaType.PDF,
            locations = Locator.Locations(
                fragments = listOf("page=$n"),
                position = n,
                progression = (n - 1).toDouble() / pages,
                totalProgression = (n - 1).toDouble() / pages,
            ),
        )
    }
    return Publication(
        manifest = Manifest(
            metadata = Metadata(localizedTitle = LocalizedString(title), conformsTo = setOf(Publication.Profile.PDF)),
            readingOrder = listOf(Link(href, mediaType = MediaType.PDF)),
            tableOfContents = outline.map { (t, page) -> Link(Url("doc.pdf#page=$page")!!, title = t) },
        ),
        container = TrackingContainer(),
        servicesBuilder = Publication.ServicesBuilder().apply {
            positionsServiceFactory = {
                object : org.readium.r2.shared.publication.services.PositionsService {
                    override suspend fun positionsByReadingOrder(): List<List<Locator>> = listOf(positions)
                }
            }
        },
    )
}

/**
 * A publication whose chapters have XHTML bodies ([chapters]: path to markup inside `<body>`), with Readium's content
 * service, so the content iterator (read-aloud) walks real text.
 */
fun testPublicationWithContent(
    title: String = "Test Book",
    chapters: Map<String, String>,
    toc: List<Pair<String, List<String>>> = emptyList(),
): Publication {
    val resources = chapters.mapKeys { Url.fromDecodedPath(it.key)!! }.mapValues { (_, body) ->
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>c</title></head><body>$body</body></html>".toByteArray()
    }
    val container = object : Container<Resource> {
        override val entries: Set<Url> = resources.keys
        override fun get(url: Url): Resource? = resources[url]?.let { InMemoryResource(it) }
        override fun close() {}
    }
    return Publication(
        manifest = Manifest(
            metadata = Metadata(localizedTitle = LocalizedString(title)),
            readingOrder = chapters.keys.map { Link(Url.fromDecodedPath(it)!!, mediaType = MediaType.XHTML) },
            tableOfContents = toc.map { (href, children) ->
                Link(Url(href)!!, title = href, children = children.map { Link(Url(it)!!, title = it) })
            },
        ),
        container = container,
        servicesBuilder = Publication.ServicesBuilder().apply {
            positionsServiceFactory = PerResourcePositionsService.createFactory(MediaType.XHTML)
            contentServiceFactory = DefaultContentService.createFactory(listOf(HtmlResourceContentIterator.Factory()))
        },
    )
}

/** [publication] is what every open returns; null is a file Readium can't open. [opened] records each file asked for. */
class FakeBookOpener(var publication: Publication? = testPublication()) : BookOpener {
    val opened = mutableListOf<File>()
    override suspend fun open(file: File): Publication? {
        opened += file
        return publication
    }
}

/** [RailPins] in memory, with the Room version's rules; [clock] stamps each pin. */
class FakeRailPins : RailPins {
    val pinList = MutableStateFlow<List<RailPin>>(emptyList())
    val dropped = mutableListOf<String>()
    private var clock = 1_000L

    override val pins: Flow<List<RailPin>> = pinList.map { list -> list.sortedByDescending { it.pinnedAt } }

    override suspend fun pin(key: String): PinResult = when {
        pinList.value.any { it.key == key } -> PinResult.ALREADY_PINNED
        pinList.value.size >= MAX_RAIL_PINS -> PinResult.FULL
        else -> {
            pinList.update { it + RailPin(key, ++clock) }
            PinResult.PINNED
        }
    }

    override suspend fun unpin(key: String) = pinList.update { list -> list.filterNot { it.key == key } }

    override suspend fun dropMissing(keys: Collection<String>) {
        dropped += keys
        pinList.update { list -> list.filterNot { it.key in keys } }
    }
}

/** [RailHidden] in memory. */
class FakeRailHidden : RailHidden {
    override val hidden = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val clearedAt = MutableStateFlow(0L)
    override fun hide(key: String, at: Long) = hidden.update { it + (key to at) }
    override fun clear(at: Long) {
        hidden.value = emptyMap()
        clearedAt.value = at
    }
}

/** [RailOrder] in memory; null until the rail seeds it. */
class FakeRailOrder(four: RailFour? = null) : RailOrder {
    override val four = MutableStateFlow(four)
    override fun save(four: RailFour) { this.four.value = four }
}

/** [AppearancePrefs] in memory. */
class FakeAppearancePrefs(side: RailSide = RailSide.LEFT, openTo: OpenTo = OpenTo.RESUME, theme: ThemeChoice = ThemeChoice.SYSTEM) : AppearancePrefs {
    private val state = MutableStateFlow(side)
    override val railSide: StateFlow<RailSide> = state
    override fun setRailSide(side: RailSide) { state.value = side }
    private val openToState = MutableStateFlow(openTo)
    override val openTo: StateFlow<OpenTo> = openToState
    override fun setOpenTo(openTo: OpenTo) { openToState.value = openTo }
    private val themeState = MutableStateFlow(theme)
    override val theme: StateFlow<ThemeChoice> = themeState
    override fun setTheme(theme: ThemeChoice) { themeState.value = theme }
}
