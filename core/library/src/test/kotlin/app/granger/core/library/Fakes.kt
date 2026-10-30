package app.granger.core.library

import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimHit
import app.granger.core.zim.ZimItem
import app.granger.core.zim.ZimMetadata
import app.granger.core.zim.ZimOpenResult
import app.granger.core.zim.ZimOpener
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeZimArchive(
    override val uuid: String,
    title: String = uuid,
    private val paths: Set<String> = setOf("index"),
    private val redirects: Map<String, String> = emptyMap(),
    override val mainPath: String? = "index",
    override val hasFulltextIndex: Boolean = true,
    var suggestions: List<ZimHit> = emptyList(),
    var results: List<ZimHit> = emptyList(),
    var failSearch: Boolean = false,
    name: String = uuid,
    private val html: Map<String, String> = emptyMap(),
    var random: String? = null,
    description: String = "About $title",
    /** Entries of any type: path to (MIME type, content), beside [paths]' HTML ones. */
    private val files: Map<String, Pair<String, ByteArray>> = emptyMap(),
    scraper: String = "",
) : ZimArchive {
    override val metadata = ZimMetadata(title, description, "eng", "2026-10-15", name, scraper)
    var closed = false
    var searches = 0

    /** Every path [read] was asked for, in order. */
    val reads = mutableListOf<String>()

    override fun resolvePath(path: String) = (redirects[path] ?: path).takeIf { it in paths || it in files }
    override fun read(path: String) = resolvePath(path)?.let {
        reads += it
        files[it]?.let { (mime, bytes) -> ZimItem(it, mime, bytes) } ?: ZimItem(it, "text/html", html[it].orEmpty().toByteArray())
    }

    override fun randomPath(): String? = random

    override fun suggest(query: String, limit: Int): List<ZimHit> {
        if (failSearch) error("native failure")
        return suggestions.take(limit)
    }

    val queries = mutableListOf<String>()

    override fun search(query: String, limit: Int): List<ZimHit> {
        searches++
        queries += query
        if (failSearch) error("native failure")
        return results.take(limit)
    }

    override fun close() { closed = true }
}

/** Opens files by name: a name mapped to an archive factory opens; any other name fails. */
class FakeZimOpener(val archives: MutableMap<String, () -> FakeZimArchive> = mutableMapOf()) : ZimOpener {
    val opened = mutableListOf<FakeZimArchive>()

    override fun open(file: File): ZimOpenResult {
        val factory = archives[file.name] ?: return ZimOpenResult.Failed("not a ZIM")
        if (!file.exists()) return ZimOpenResult.Failed("missing")
        return ZimOpenResult.Opened(factory().also { opened += it })
    }
}

/** A [Library] serving whatever [state] holds. */
class StubLibrary(initial: List<Pack>) : Library {
    val state = MutableStateFlow(initial)
    override val packs: Flow<List<Pack>> = state
    override val bookmarks: Flow<List<Bookmark>> = MutableStateFlow(emptyList())
    override suspend fun refresh() = Unit
    override suspend fun fileReplaced(path: String) = Unit
    override fun archive(packId: String): ZimArchive? = null
    override fun cachedArchive(packId: String): ZimArchive? = null
    override suspend fun startPath(packId: String): String? = null
    override fun isBookmarked(packId: String, path: String): Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setBookmarked(packId: String, path: String, title: String, bookmarked: Boolean) = Unit
    override suspend fun savePosition(packId: String, path: String, scrollFraction: Float) = Unit
    override suspend fun position(packId: String, path: String): Float? = null
    override suspend fun remove(packId: String): Boolean = true
}

/** A [Bookshelf] serving whatever [state] holds; only [books] is meaningful. */
class StubBookshelf(initial: List<Book> = emptyList()) : Bookshelf {
    val state = MutableStateFlow(initial)
    override val books: Flow<List<Book>> = state
    override val positions: Flow<Map<String, BookPosition>> = MutableStateFlow(emptyMap())
    override suspend fun book(id: String): Book? = state.value.firstOrNull { it.id == id }
    override suspend fun refresh() = Unit
    override suspend fun addDownloaded(file: File, origin: BookOrigin, ref: String, details: BookDetails?): Book? = null
    override suspend fun import(displayName: String, open: () -> InputStream): ImportResult = ImportResult.Unsupported
    override suspend fun remove(id: String): Boolean = true
    override suspend fun registerPackDocument(
        zimId: String, path: String, title: String, file: File, linkText: String, listedTitle: String,
    ): Book? = null
    override suspend fun removePackDocuments(zimId: String) = Unit
    override suspend fun savePosition(bookId: String, locatorJson: String, totalProgression: Double, chapterTitle: String) = Unit
    override suspend fun position(bookId: String): BookPosition? = null
    override fun marks(bookId: String): Flow<List<BookAnnotation>> = state.map { emptyList() }
    override suspend fun addMark(mark: BookAnnotation): Long = 0
    override suspend fun updateMark(mark: BookAnnotation) = Unit
    override suspend fun deleteMark(id: Long) = Unit
}

/** In-memory [MediaLibrary]: the index only reads its flows. */
class MemoryMedia : MediaLibrary {
    val trackList = MutableStateFlow<List<Track>>(emptyList())
    val bookList = MutableStateFlow<List<Audiobook>>(emptyList())
    val chapterList = MutableStateFlow<List<AudiobookChapter>>(emptyList())
    val noteList = MutableStateFlow<List<AlbumNote>>(emptyList())
    override val tracks: Flow<List<Track>> = trackList
    override val audiobooks: Flow<List<Audiobook>> = bookList
    override val allChapters: Flow<List<AudiobookChapter>> = chapterList
    override val albumNotes: Flow<List<AlbumNote>> = noteList
    override fun chapters(audiobookId: String) = chapterList.map { all -> all.filter { it.audiobookId == audiobookId } }
    override suspend fun track(id: String) = trackList.value.firstOrNull { it.id == id }
    override suspend fun audiobook(id: String) = bookList.value.firstOrNull { it.id == id }
    override suspend fun coverPaths() = emptySet<String>()
    override suspend fun sync(origin: MediaOrigin, result: ScanResult, keep: (uri: String) -> Boolean) {
        trackList.value = trackList.value.filter { it.origin != origin } + result.tracks.filter { it.audiobookId == null }
        bookList.value = bookList.value.filter { it.origin != origin } + result.audiobooks.map { it.book }
        noteList.value = noteList.value + result.albumNotes
    }
    override suspend fun markAllMissing(origin: MediaOrigin) = error("not in tests")
    override suspend fun forget(trackIds: Collection<String>, audiobookIds: Collection<String>) = error("not in tests")
}

class FakeShelves(val rows: List<CollectionRow> = emptyList()) : Shelves {
    override val shelves: Flow<List<Shelf>> =
        MutableStateFlow(listOf(Shelf(MediaType("m", "Media", true, 0), rows)))
    override fun collection(id: String): Flow<CollectionRow?> = MutableStateFlow(rows.firstOrNull { it.collection.id == id })
    override suspend fun movePack(pack: Pack, toCollectionId: String) = Unit
    override suspend fun moveBook(book: Book, toCollectionId: String) = Unit
    override suspend fun moveAlbum(album: AlbumSummary, toCollectionId: String) = Unit
    override suspend fun moveAudiobook(book: Audiobook, toCollectionId: String) = Unit
    override suspend fun moveCollection(collectionId: String, toMediaTypeId: String) = Unit
    override suspend fun createMediaType(name: String) = ""
    override suspend fun deleteMediaType(id: String) = true
    override suspend fun createCollection(name: String, mediaTypeId: String) = ""
    override suspend fun renameCollection(id: String, name: String) = Unit
    override suspend fun deleteCollection(id: String) = Unit
}
