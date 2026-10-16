package app.granger.core.library

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** How many tabs the side rail shows. */
const val RAIL_LIMIT = 8

/** Open reading tabs (the rail's Bookmarks section), history and recent searches. */
interface ReadingSessions {
    /** On-rail tabs, newest first. Tabs beyond [limit] drop off the rail view but stay stored. */
    fun railTabs(limit: Int = RAIL_LIMIT): Flow<List<ReadingTab>>

    /** Every article ever opened, newest first, one row per article. */
    val history: Flow<List<HistoryEntry>>

    /** Distinct queries, newest first. */
    fun recentSearches(limit: Int = 5): Flow<List<String>>

    /** Opens an article from Library, Search or History: a new rail tab, or the tab already showing it. Returns the tab id. */
    suspend fun openTab(packId: String, path: String, title: String): Long

    /** Navigation within a tab (following a link): updates that tab instead of adding a rail spot. */
    suspend fun showInTab(tabId: Long, packId: String, path: String, title: String)

    /** Takes the tab off the rail for good; history keeps the article. */
    suspend fun closeTab(tabId: Long)

    /** The most recently used tab still on the rail, or null. */
    suspend fun lastTab(): ReadingTab?

    suspend fun tab(id: Long): ReadingTab?

    /** Trims [query]; blanks are ignored. Keeps the newest 50. */
    suspend fun recordSearch(query: String)
}

class RoomReadingSessions(
    private val db: LibraryDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : ReadingSessions {
    private val dao = db.sessionDao()

    override fun railTabs(limit: Int): Flow<List<ReadingTab>> = dao.observeRailTabs(limit)

    override val history: Flow<List<HistoryEntry>> = dao.observeHistory()

    override fun recentSearches(limit: Int): Flow<List<String>> =
        dao.observeRecentSearches(limit).map { rows -> rows.map { it.query } }

    override suspend fun openTab(packId: String, path: String, title: String): Long = db.withTransaction {
        val time = now()
        dao.upsertHistory(HistoryEntry(packId, path, title, time))
        val existing = dao.railTabShowing(packId, path)
        if (existing != null) {
            dao.updateTab(existing.copy(title = title, openedAt = time))
            existing.id
        } else {
            dao.insertTab(ReadingTab(0, packId, path, title, time, onRail = true))
        }
    }

    override suspend fun showInTab(tabId: Long, packId: String, path: String, title: String) {
        db.withTransaction {
            val time = now()
            val tab = dao.tab(tabId) ?: return@withTransaction
            dao.updateTab(tab.copy(packId = packId, path = path, title = title, openedAt = time))
            dao.upsertHistory(HistoryEntry(packId, path, title, time))
        }
    }

    override suspend fun closeTab(tabId: Long) {
        val tab = dao.tab(tabId) ?: return
        if (tab.onRail) dao.updateTab(tab.copy(onRail = false))
    }

    override suspend fun lastTab(): ReadingTab? = dao.latestTab()

    override suspend fun tab(id: Long): ReadingTab? = dao.tab(id)

    override suspend fun recordSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        db.withTransaction {
            dao.upsertRecentSearch(RecentSearch(trimmed, now()))
            dao.trimRecentSearches(MAX_RECENT_SEARCHES)
        }
    }

    private companion object {
        const val MAX_RECENT_SEARCHES = 50
    }
}
