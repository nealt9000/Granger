package app.granger

import app.granger.core.library.Bookmark
import app.granger.core.library.Library
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimHit
import app.granger.core.zim.ZimItem
import app.granger.core.zim.ZimMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

fun pack(id: String, title: String = id, status: PackStatus = PackStatus.OK) =
    Pack(id, "/packs/$id.zim", title, "", "eng", "2026-10-15", 1_000L, true, status, 0L)

class FakeZimArchive(
    override val uuid: String = "p",
    private val paths: Set<String> = setOf("index"),
    override val mainPath: String? = "index",
) : ZimArchive {
    override val metadata = ZimMetadata(uuid, "", "eng", "2026-10-15", uuid)
    override val hasFulltextIndex = true
    override fun resolvePath(path: String) = path.takeIf { it in paths }
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

    override suspend fun refresh() { refreshes++ }
    override fun archive(packId: String) = archives[packId]
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
