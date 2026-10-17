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
) : ZimArchive {
    override val metadata = ZimMetadata(title, "About $title", "eng", "2026-10-15", name)
    var closed = false

    override fun resolvePath(path: String) = (redirects[path] ?: path).takeIf { it in paths }
    override fun read(path: String) = resolvePath(path)?.let { ZimItem(it, "text/html", ByteArray(0)) }

    override fun suggest(query: String, limit: Int): List<ZimHit> {
        if (failSearch) error("native failure")
        return suggestions.take(limit)
    }

    override fun search(query: String, limit: Int): List<ZimHit> {
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
    override suspend fun savePosition(bookId: String, locatorJson: String, totalProgression: Double, chapterTitle: String) = Unit
    override suspend fun position(bookId: String): BookPosition? = null
    override fun marks(bookId: String): Flow<List<BookMark>> = state.map { emptyList() }
    override suspend fun addMark(mark: BookMark): Long = 0
    override suspend fun updateMark(mark: BookMark) = Unit
    override suspend fun deleteMark(id: Long) = Unit
}
