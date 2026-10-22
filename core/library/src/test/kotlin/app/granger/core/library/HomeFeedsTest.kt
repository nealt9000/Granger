package app.granger.core.library

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeFeedsTest {
    private val db = inMemoryDatabase()
    private var clock = 1_000L
    private val feeds = RoomHomeFeeds(db) { clock++ }

    @After fun close() = db.close()

    private suspend fun scopes() = feeds.feeds.first().map { it.scope }

    @Test fun addAppendsInOrderWithDenseOrdinals() = runTest {
        feeds.add("family:wikipedia", FeedKind.ON_THIS_DAY)
        feeds.add("family:wikipedia", FeedKind.RANDOM)
        feeds.add("collection:granger-classics", FeedKind.SEARCH, "  stoicism ")
        val rows = feeds.feeds()
        assertThat(rows.map { it.ordinal }).containsExactly(0, 1, 2).inOrder()
        assertThat(rows.last().query).isEqualTo("stoicism")
        assertThat(rows.map { it.addedAt }).isInStrictOrder()
    }

    @Test fun removeClosesTheGapAndDropsTheDraw() = runTest {
        val a = feeds.add("a", FeedKind.RANDOM)
        val b = feeds.add("b", FeedKind.RANDOM)
        val c = feeds.add("c", FeedKind.RANDOM)
        feeds.saveDraw(HomeDraw(b, "2026-10-22", "[]"))
        feeds.remove(b)
        assertThat(scopes()).containsExactly("a", "c").inOrder()
        assertThat(feeds.feeds().map { it.ordinal }).containsExactly(0, 1).inOrder()
        assertThat(feeds.draw(b)).isNull()
        assertThat(feeds.feeds().map { it.id }).containsExactly(a, c)
    }

    @Test fun moveSwapsNeighboursAndStopsAtTheEnds() = runTest {
        val a = feeds.add("a", FeedKind.RANDOM)
        feeds.add("b", FeedKind.RANDOM)
        val c = feeds.add("c", FeedKind.RANDOM)
        feeds.move(c, up = true)
        assertThat(scopes()).containsExactly("a", "c", "b").inOrder()
        feeds.move(a, up = true)
        assertThat(scopes()).containsExactly("a", "c", "b").inOrder()
        feeds.move(a, up = false)
        assertThat(scopes()).containsExactly("c", "a", "b").inOrder()
        feeds.move(999, up = false)
        assertThat(scopes()).containsExactly("c", "a", "b").inOrder()
    }

    @Test fun aDrawIsReplacedPerFeed() = runTest {
        val a = feeds.add("a", FeedKind.SEARCH, "owls")
        feeds.saveDraw(HomeDraw(a, "2026-10-22", "[1]"))
        feeds.saveDraw(HomeDraw(a, "2026-10-23", "[2]"))
        assertThat(feeds.draw(a)).isEqualTo(HomeDraw(a, "2026-10-23", "[2]"))
        feeds.clearDraw(a)
        assertThat(feeds.draw(a)).isNull()
    }
}
