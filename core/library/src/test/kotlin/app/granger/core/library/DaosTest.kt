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
        assertThat(types.map { it.id }).containsExactly("literature", "audiobooks", "music", "video", "maps").inOrder()
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

    @Test fun clearingCallsLeaveWhatTheyAreToldToKeep() = runTest {
        val dao = db.sessionDao()
        dao.upsertHistory(HistoryEntry("p", "a", "A", 1L))
        dao.upsertHistory(HistoryEntry("q", "b", "B", 2L))
        dao.deleteAllHistory()
        assertThat(dao.observeHistory().first()).isEmpty()

        for (q in listOf("cats", "dogs", "owls")) dao.upsertRecentSearch(RecentSearch(q, 1L))
        dao.deleteAllRecentSearches(listOf("owls"))
        assertThat(dao.observeRecentSearches(10).first().map { it.query }).containsExactly("owls")
        dao.deleteAllRecentSearches(emptyList())
        assertThat(dao.observeRecentSearches(10).first()).isEmpty()

        val ids = (1..3).map { dao.insertTab(ReadingTab(0, "p", "page$it", "Page $it", openedAt = it.toLong(), onRail = true)) }
        dao.closeTabsExcept(listOf(ids[1]))
        assertThat(dao.observeRailTabs(8).first().map { it.id }).containsExactly(ids[1])
        assertThat(dao.tab(ids[0])).isNotNull()                 // off the rail, not deleted
        dao.closeTabsExcept(emptyList())
        assertThat(dao.observeRailTabs(8).first()).isEmpty()
    }

    private fun book(id: String, title: String = id, status: PackStatus = PackStatus.OK, indexedAt: Long? = null) = Book(
        id = id, origin = BookOrigin.IMPORT, sourceRef = id, filePath = "/books/$id.epub", title = title,
        author = "", translator = "", date = "", language = "eng", subjects = "", description = "",
        coverPath = null, sizeBytes = 10L, status = status, indexedAt = indexedAt, addedAt = 1L,
    )

    @Test fun freshDatabaseHasBookText() {
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL("INSERT INTO book_text (docid, text) VALUES (1, 'call me ishmael')")
        sqlite.query("SELECT docid FROM book_text WHERE book_text MATCH 'ishmael'").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getLong(0)).isEqualTo(1L)
        }
    }

    @Test fun chunkIdsAreNeverReused() = runTest {
        val dao = db.bookDao()
        val first = (1..3).map { dao.insertChunk(BookChunk(0, "b", "c$it.xhtml", it, 0.0)) }
        assertThat(first).containsExactly(1L, 2L, 3L).inOrder()
        dao.deleteChunks("b")
        assertThat(dao.chunkCount()).isEqualTo(0)
        assertThat(dao.insertChunk(BookChunk(0, "b", "c.xhtml", 0, 0.0))).isEqualTo(4L)
        assertThat(dao.chunk(4L)!!.href).isEqualTo("c.xhtml")
    }

    @Test fun notIndexedListsOnlyOkUnindexedBooks() = runTest {
        val dao = db.bookDao()
        dao.upsert(book("fresh"))
        dao.upsert(book("done", indexedAt = 5L))
        dao.upsert(book("gone", status = PackStatus.MISSING))
        assertThat(dao.notIndexed().map { it.id }).containsExactly("fresh")
        dao.setIndexed("fresh", 9L)
        assertThat(dao.notIndexed()).isEmpty()
        dao.clearIndexed()
        assertThat(dao.notIndexed().map { it.id }).containsExactly("fresh", "done")
    }

    @Test fun marksAreOldestFirstPerBook() = runTest {
        val dao = db.bookDao()
        fun mark(bookId: String, at: Long) = BookAnnotation(0, bookId, MarkKind.HIGHLIGHT, "{}", "t$at", "yellow", "", at, at)
        dao.insertMark(mark("a", 30L))
        dao.insertMark(mark("a", 10L))
        dao.insertMark(mark("b", 20L))
        assertThat(dao.observeMarks("a").first().map { it.createdAt }).containsExactly(10L, 30L).inOrder()
        val m = dao.observeMarks("b").first().single()
        dao.updateMark(m.copy(note = "n"))
        assertThat(dao.observeMarks("b").first().single().note).isEqualTo("n")
        dao.deleteMark(m)
        assertThat(dao.observeMarks("b").first()).isEmpty()
    }
}
