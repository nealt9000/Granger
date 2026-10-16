package app.granger

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
    override suspend fun moveCollection(collectionId: String, toMediaTypeId: String) {}
    override suspend fun createMediaType(name: String) = "new-${nextId++}"
    override suspend fun deleteMediaType(id: String) = false
    override suspend fun createCollection(name: String, mediaTypeId: String) = "new-${nextId++}"
    override suspend fun renameCollection(id: String, name: String) {}
    override suspend fun deleteCollection(id: String) {}
}
