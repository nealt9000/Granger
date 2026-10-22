package app.granger.core.library

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/** Home's feeds (Plan H2): the rows the app draws cards from, in their order, and each feed's cards for the day. */
interface HomeFeeds {
    /** In Home order. */
    val feeds: Flow<List<HomeFeed>>

    suspend fun feeds(): List<HomeFeed>

    /** Appends a feed; returns its id. */
    suspend fun add(scope: String, kind: FeedKind, query: String = ""): Long

    /** Removes the feed and its draw; the others close the gap. */
    suspend fun remove(id: Long)

    /** Swaps the feed with its neighbour above ([up]) or below; nothing happens at the ends. */
    suspend fun move(id: Long, up: Boolean)

    /** The feed's cards for the day they were drawn, or null when it has none. */
    suspend fun draw(feedId: Long): HomeDraw?

    suspend fun saveDraw(draw: HomeDraw)

    /** Forgets a feed's draw so the next look redraws it. */
    suspend fun clearDraw(feedId: Long)
}

class RoomHomeFeeds(
    private val db: LibraryDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : HomeFeeds {
    private val dao = db.homeDao()

    override val feeds: Flow<List<HomeFeed>> = dao.observeFeeds()

    override suspend fun feeds(): List<HomeFeed> = dao.feeds()

    override suspend fun add(scope: String, kind: FeedKind, query: String): Long = db.withTransaction {
        dao.insertFeed(HomeFeed(0, scope, kind, query.trim(), dao.maxOrdinal() + 1, now()))
    }

    override suspend fun remove(id: Long) = db.withTransaction {
        dao.deleteFeed(id)
        dao.deleteDraw(id)
        renumber(dao.feeds())
    }

    override suspend fun move(id: Long, up: Boolean) = db.withTransaction {
        val feeds = dao.feeds().toMutableList()
        val at = feeds.indexOfFirst { it.id == id }
        val to = if (up) at - 1 else at + 1
        if (at < 0 || to !in feeds.indices) return@withTransaction
        feeds[at] = feeds[to].also { feeds[to] = feeds[at] }
        renumber(feeds)
    }

    /** Ordinals dense from 0 in list order; only rows whose ordinal changes are written. */
    private suspend fun renumber(feeds: List<HomeFeed>) {
        val changed = feeds.mapIndexedNotNull { i, feed -> if (feed.ordinal == i) null else feed.copy(ordinal = i) }
        if (changed.isNotEmpty()) dao.updateFeeds(changed)
    }

    override suspend fun draw(feedId: Long): HomeDraw? = dao.draw(feedId)

    override suspend fun saveDraw(draw: HomeDraw) = dao.upsertDraw(draw)

    override suspend fun clearDraw(feedId: Long) = dao.deleteDraw(feedId)
}
