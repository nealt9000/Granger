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
class DaosTest {
    private val db = inMemoryDatabase()

    @After fun close() = db.close()

    @Test fun packsAreSortedByTitleIgnoringCase() = runTest {
        db.packDao().upsert(pack("b", "wikipedia"))
        db.packDao().upsert(pack("a", "Classics"))
        db.packDao().upsert(pack("c", "Medicine"))
        assertThat(db.packDao().observeAll().first().map { it.title })
            .containsExactly("Classics", "Medicine", "wikipedia").inOrder()
    }

    @Test fun upsertReplacesAndKeepsStatus() = runTest {
        db.packDao().upsert(pack("a"))
        db.packDao().upsert(pack("a").copy(status = PackStatus.MISSING))
        assertThat(db.packDao().getAll().single().status).isEqualTo(PackStatus.MISSING)
        assertThat(db.packDao().get("a")!!.status).isEqualTo(PackStatus.MISSING)
        assertThat(db.packDao().get("zzz")).isNull()
    }

    @Test fun bookmarkExistsFollowsAddAndDelete() = runTest {
        val dao = db.bookmarkDao()
        assertThat(dao.observeExists("p", "C++").first()).isFalse()
        dao.upsert(Bookmark("p", "C++", "C++", 1L))
        assertThat(dao.observeExists("p", "C++").first()).isTrue()
        assertThat(dao.observeExists("other", "C++").first()).isFalse()
        dao.delete("p", "C++")
        assertThat(dao.observeExists("p", "C++").first()).isFalse()
    }

    @Test fun bookmarksAreNewestFirst() = runTest {
        db.bookmarkDao().upsert(Bookmark("p", "Old", "Old", 1L))
        db.bookmarkDao().upsert(Bookmark("p", "New", "New", 2L))
        assertThat(db.bookmarkDao().observeAll().first().map { it.path }).containsExactly("New", "Old").inOrder()
    }

    @Test fun lastReadIsTheMostRecentPositionInThatPack() = runTest {
        val dao = db.readingPositionDao()
        dao.upsert(ReadingPosition("p", "A", 0.1f, 10L))
        dao.upsert(ReadingPosition("p", "B", 0.2f, 30L))
        dao.upsert(ReadingPosition("other", "C", 0.3f, 50L))
        dao.upsert(ReadingPosition("p", "A", 0.5f, 20L))
        assertThat(dao.lastRead("p")!!.path).isEqualTo("B")
        assertThat(dao.get("p", "A")!!.scrollFraction).isEqualTo(0.5f)
        assertThat(dao.lastRead("nobody")).isNull()
    }

    @Test fun freshDatabaseHasBuiltInMediaTypes() = runTest {
        val types = db.shelfDao().observeMediaTypes().first()
        assertThat(types.map { it.id }).containsExactly("literature", "audiobooks", "music", "video").inOrder()
        assertThat(types.all { it.builtIn }).isTrue()
    }

    @Test fun railTabsAreNewestFirstAndLimited() = runTest {
        val dao = db.sessionDao()
        for (i in 1..10) dao.insertTab(ReadingTab(0, "p", "page$i", "Page $i", openedAt = i.toLong(), onRail = i != 10))
        val rail = dao.observeRailTabs(8).first()
        assertThat(rail.map { it.path }).containsExactly(
            "page9", "page8", "page7", "page6", "page5", "page4", "page3", "page2",
        ).inOrder()
    }

    @Test fun historyUpsertKeepsOneRowPerArticle() = runTest {
        val dao = db.sessionDao()
        dao.upsertHistory(HistoryEntry("p", "a", "A", 1L))
        dao.upsertHistory(HistoryEntry("p", "a", "A", 5L))
        val all = dao.observeHistory().first()
        assertThat(all).hasSize(1)
        assertThat(all.single().openedAt).isEqualTo(5L)
    }
}
