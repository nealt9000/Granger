package app.granger

import app.granger.core.library.Book
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
        override suspend fun addDownloaded(file: File, catalogId: String): Book? = result()
    }

    @Test fun aThrowingBookshelfDoesNotFailTheInstall() = runTest {
        var indexed = false
        onBookInstalled(Shelf { error("disk full") }, File("x.epub"), "pg1") { indexed = true }
        assertThat(indexed).isFalse()
    }

    @Test fun anUnreadableBookIsLoggedAndNotIndexed() = runTest {
        var indexed = false
        onBookInstalled(Shelf { null }, File("x.epub"), "pg1") { indexed = true }
        assertThat(indexed).isFalse()
    }

    @Test fun aRegisteredBookStartsIndexing() = runTest {
        var indexed = false
        onBookInstalled(Shelf { book("book:granger:pg1") }, File("x.epub"), "pg1") { indexed = true }
        assertThat(indexed).isTrue()
    }
}
