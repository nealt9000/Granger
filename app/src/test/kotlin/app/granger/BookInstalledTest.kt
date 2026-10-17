package app.granger

import app.granger.core.library.Book
import app.granger.core.library.BookDetails
import app.granger.core.library.BookOrigin
import app.granger.core.library.Bookshelf
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookInstalledTest {
    private class Shelf(val result: () -> Book?) : Bookshelf by FakeBookshelf() {
        override suspend fun addDownloaded(file: File, origin: BookOrigin, ref: String, details: BookDetails?): Book? = result()
    }

    @Test fun aThrowingBookshelfDoesNotFailTheInstall() = runTest {
        var indexed = false
        onBookInstalled(Shelf { error("disk full") }, File("x.epub"), BookOrigin.GRANGER, "pg1", null) { indexed = true }
        assertThat(indexed).isFalse()
    }

    @Test fun anUnreadableBookIsLoggedAndNotIndexed() = runTest {
        var indexed = false
        onBookInstalled(Shelf { null }, File("x.epub"), BookOrigin.GRANGER, "pg1", null) { indexed = true }
        assertThat(indexed).isFalse()
    }

    @Test fun archiveBookIsFiledWithItsOriginAndDetails() = runTest {
        val details = BookDetails(title = "T", author = "A", date = "1881", subjects = listOf("s"), description = "D", language = "eng")
        var filed: Triple<BookOrigin, String, BookDetails?>? = null
        val shelf = object : Bookshelf by FakeBookshelf() {
            override suspend fun addDownloaded(file: File, origin: BookOrigin, ref: String, details: BookDetails?): Book? {
                filed = Triple(origin, ref, details)
                return book("book:archive-org:x")
            }
        }
        onBookInstalled(shelf, File("x.epub"), BookOrigin.ARCHIVE_ORG, "x", details) {}
        assertThat(filed).isEqualTo(Triple(BookOrigin.ARCHIVE_ORG, "x", details))
    }

    @Test fun aRegisteredBookStartsIndexing() = runTest {
        var indexed = false
        onBookInstalled(Shelf { book("book:granger:pg1") }, File("x.epub"), BookOrigin.GRANGER, "pg1", null) { indexed = true }
        assertThat(indexed).isTrue()
    }
}
