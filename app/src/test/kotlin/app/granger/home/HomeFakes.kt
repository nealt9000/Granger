package app.granger.home

import app.granger.core.library.FeedKind
import app.granger.core.library.HomeDraw
import app.granger.core.library.HomeFeed
import app.granger.core.library.HomeFeeds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** In-memory [HomeFeeds]: rows in order with dense ordinals, one draw per feed. */
class FakeHomeFeeds : HomeFeeds {
    val list = MutableStateFlow<List<HomeFeed>>(emptyList())
    val draws = mutableMapOf<Long, HomeDraw>()
    private var nextId = 1L

    override val feeds: Flow<List<HomeFeed>> = list
    override suspend fun feeds(): List<HomeFeed> = list.value

    override suspend fun add(scope: String, kind: FeedKind, query: String): Long {
        val id = nextId++
        list.update { it + HomeFeed(id, scope, kind, query.trim(), it.size, id) }
        return id
    }

    override suspend fun remove(id: Long) {
        list.update { rows -> rows.filterNot { it.id == id }.mapIndexed { i, f -> f.copy(ordinal = i) } }
        draws.remove(id)
    }

    override suspend fun move(id: Long, up: Boolean) {
        list.update { rows ->
            val at = rows.indexOfFirst { it.id == id }
            val to = if (up) at - 1 else at + 1
            if (at < 0 || to !in rows.indices) return@update rows
            val swapped = rows.toMutableList()
            swapped[at] = rows[to]
            swapped[to] = rows[at]
            swapped.mapIndexed { i, f -> f.copy(ordinal = i) }
        }
    }

    override suspend fun draw(feedId: Long): HomeDraw? = draws[feedId]
    override suspend fun saveDraw(draw: HomeDraw) { draws[draw.feedId] = draw }
    override suspend fun clearDraw(feedId: Long) { draws.remove(feedId) }
}

class FakeHomePrefs(override var seeded: Boolean = false, override var dismissedFailures: Set<String> = emptySet()) : HomePrefs
