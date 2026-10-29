package app.granger.core.library

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** How many tabs the side rail shows. */
const val RAIL_LIMIT = 8

/** How many pages a tab's [ReadingTab.backStack] keeps; the oldest drop off. */
const val MAX_BACK_STACK = 50

/** Open reading tabs (the rail's Bookmarks section), history and recent searches. */
interface ReadingSessions {
    /** On-rail tabs, newest first. Tabs beyond [limit] drop off the rail view but stay stored. */
    fun railTabs(limit: Int = RAIL_LIMIT): Flow<List<ReadingTab>>

    /** Every article ever opened, newest first, one row per article. */
    val history: Flow<List<HistoryEntry>>

    /** Distinct queries, newest first. */
    fun recentSearches(limit: Int = 5): Flow<List<String>>

    /** The rows behind [recentSearches], with the time each was searched. */
    fun recentSearchRows(limit: Int = 5): Flow<List<RecentSearch>>

    /** Opens an article from Library, Search or History: a new rail tab, or the tab already showing it. Returns the tab id. */
    suspend fun openTab(packId: String, path: String, title: String): Long

    /**
     * Opens [path] in a new rail tab even when a tab already shows it, so one pack can be open in two places (Browse's
     * pack, Plan U3c walkthrough). Returns the new tab's id.
     */
    suspend fun newTab(packId: String, path: String, title: String): Long

    /** Navigation within a tab (following a link): updates that tab instead of adding a rail spot. */
    suspend fun showInTab(tabId: Long, packId: String, path: String, title: String)

    /**
     * The tab now shows [path] in its own pack: [showInTab], and [path] goes on the tab's back stack. Showing the
     * page already on top (a reload, or the page Back went to) doesn't add it again.
     */
    suspend fun pushPath(tabId: Long, path: String, title: String)

    /** Takes the current page off the tab's back stack and returns the one before it, or null at the first page. */
    suspend fun popPath(tabId: Long): String?

    /** Takes the tab off the rail for good; history keeps the article. */
    suspend fun closeTab(tabId: Long)

    /** The most recently used tab still on the rail, or null. */
    suspend fun lastTab(): ReadingTab?

    suspend fun tab(id: Long): ReadingTab?

    /** Trims [query]; blanks are ignored. Keeps the newest 50. */
    suspend fun recordSearch(query: String)

    /** Deletes the recent search for [query] (trimmed). */
    suspend fun forgetSearch(query: String)

    /**
     * History's Clear history (Plan U3b Decision 7): deletes every History row and every recent search but
     * [keepSearches], and takes every tab but [keepTabIds] off the rail.
     */
    suspend fun clearHistory(keepTabIds: Set<Long>, keepSearches: Set<String> = emptySet())
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

    override fun recentSearchRows(limit: Int): Flow<List<RecentSearch>> = dao.observeRecentSearches(limit)

    override suspend fun openTab(packId: String, path: String, title: String): Long = db.withTransaction {
        val time = now()
        dao.upsertHistory(HistoryEntry(packId, path, title, time))
        val existing = dao.railTabShowing(packId, path)
        if (existing != null) {
            dao.updateTab(existing.copy(title = title, openedAt = time))
            existing.id
        } else {
            dao.insertTab(ReadingTab(0, packId, path, title, time, onRail = true, backStack = stackOf(listOf(path))))
        }
    }

    override suspend fun newTab(packId: String, path: String, title: String): Long = db.withTransaction {
        val time = now()
        dao.upsertHistory(HistoryEntry(packId, path, title, time))
        dao.insertTab(ReadingTab(0, packId, path, title, time, onRail = true, backStack = stackOf(listOf(path))))
    }

    override suspend fun showInTab(tabId: Long, packId: String, path: String, title: String) {
        db.withTransaction {
            val time = now()
            val tab = dao.tab(tabId) ?: return@withTransaction
            dao.updateTab(tab.copy(packId = packId, path = path, title = title, openedAt = time))
            dao.upsertHistory(HistoryEntry(packId, path, title, time))
        }
    }

    override suspend fun pushPath(tabId: Long, path: String, title: String) {
        db.withTransaction {
            val time = now()
            val tab = dao.tab(tabId) ?: return@withTransaction
            val stack = pathsOf(tab.backStack)
            val pushed = if (stack.lastOrNull() == path) stack else (stack + path).takeLast(MAX_BACK_STACK)
            dao.updateTab(tab.copy(path = path, title = title, openedAt = time, backStack = stackOf(pushed)))
            dao.upsertHistory(HistoryEntry(tab.packId, path, title, time))
        }
    }

    override suspend fun popPath(tabId: Long): String? = db.withTransaction {
        val tab = dao.tab(tabId) ?: return@withTransaction null
        val stack = pathsOf(tab.backStack)
        if (stack.size < 2) return@withTransaction null
        val left = stack.dropLast(1)
        dao.updateTab(tab.copy(backStack = stackOf(left)))
        left.last()
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

    override suspend fun forgetSearch(query: String) = dao.deleteRecentSearch(query.trim())

    override suspend fun clearHistory(keepTabIds: Set<Long>, keepSearches: Set<String>) {
        db.withTransaction {
            dao.deleteAllHistory()
            dao.deleteAllRecentSearches(keepSearches)
            dao.closeTabsExcept(keepTabIds)
        }
    }

    private companion object {
        const val MAX_RECENT_SEARCHES = 50

        fun pathsOf(stack: String): List<String> = if (stack.isEmpty()) emptyList() else stack.split('\n')

        /** A path with a line break (never seen in a ZIM) can't be kept in the joined stack, so it's left out. */
        fun stackOf(paths: List<String>): String = paths.filterNot { '\n' in it }.joinToString("\n")
    }
}
