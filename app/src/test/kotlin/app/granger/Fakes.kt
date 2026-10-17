package app.granger

import app.granger.core.library.Book
import app.granger.core.library.BookMark
import app.granger.core.library.BookDetails
import app.granger.core.library.BookOrigin
import app.granger.core.library.BookPosition
import app.granger.core.library.Bookshelf
import app.granger.core.library.ImportResult
import app.granger.core.library.Bookmark
import app.granger.core.library.CollectionRow
import app.granger.core.library.HistoryEntry
import app.granger.core.library.Library
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.core.library.ReadingSessions
import app.granger.core.library.ReadingTab
import app.granger.core.library.Shelf
import app.granger.core.library.Shelves
import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimHit
import app.granger.core.zim.ZimItem
import app.granger.core.zim.ZimMetadata
import app.granger.ebook.BookOpener
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.services.PerResourcePositionsService
import org.readium.r2.shared.publication.services.positionsServiceFactory
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.mediatype.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
) : ZimArchive {
    override val metadata = ZimMetadata(uuid, "", "eng", "2026-10-15", uuid)
    override val hasFulltextIndex = true
    override fun resolvePath(path: String) = (redirects[path] ?: path).takeIf { it in paths }
    override fun read(path: String) = resolvePath(path)?.let { ZimItem(it, "text/html", ByteArray(0)) }
    override fun suggest(query: String, limit: Int) = emptyList<ZimHit>()
    override fun search(query: String, limit: Int) = emptyList<ZimHit>()
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
        tabs.update { it + ReadingTab(id, packId, path, title, time, onRail = true) }
        return id
    }

    override suspend fun showInTab(tabId: Long, packId: String, path: String, title: String) {
        tabs.update { list -> list.map { if (it.id == tabId) it.copy(packId = packId, path = path, title = title) else it } }
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
}

/** In-memory [Shelves] serving whatever [shelfList] holds; writes are recorded, not applied. */
class FakeShelves : Shelves {
    val shelfList = MutableStateFlow<List<Shelf>>(emptyList())
    val moves = mutableListOf<Pair<String, String>>()
    private var nextId = 0

    override val shelves: Flow<List<Shelf>> = shelfList

    override fun collection(id: String): Flow<CollectionRow?> =
        shelfList.map { shelves -> shelves.flatMap { it.collections }.firstOrNull { it.collection.id == id } }

    override suspend fun movePack(pack: Pack, toCollectionId: String) { moves += pack.id to toCollectionId }
    override suspend fun moveBook(book: Book, toCollectionId: String) { moves += book.id to toCollectionId }
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

    override suspend fun savePosition(bookId: String, locatorJson: String, totalProgression: Double, chapterTitle: String) {
        positionMap.update { it + (bookId to BookPosition(bookId, locatorJson, totalProgression, chapterTitle, 0L)) }
    }

    override suspend fun position(bookId: String): BookPosition? = positionMap.value[bookId]
    /** Oldest first, like the DAO. */
    val markList = MutableStateFlow<List<BookMark>>(emptyList())
    private var nextMarkId = 1L
    override fun marks(bookId: String): Flow<List<BookMark>> = markList.map { all -> all.filter { it.bookId == bookId } }
    override suspend fun addMark(mark: BookMark): Long {
        val id = nextMarkId++
        markList.update { it + mark.copy(id = id) }
        return id
    }
    override suspend fun updateMark(mark: BookMark) { markList.update { all -> all.map { if (it.id == mark.id) mark else it } } }
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

/** [publication] is what every open returns; null is a file Readium can't open. [opened] records each file asked for. */
class FakeBookOpener(var publication: Publication? = testPublication()) : BookOpener {
    val opened = mutableListOf<File>()
    override suspend fun open(file: File): Publication? {
        opened += file
        return publication
    }
}
