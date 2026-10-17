package app.granger

import app.granger.core.library.Book
import app.granger.core.library.BookDetails
import app.granger.core.library.BookOrigin
import app.granger.core.library.Bookshelf
import app.granger.archive.ArchiveDownloads
import app.granger.core.archive.ArchiveItem
import app.granger.core.archive.TextKind
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.ItemKind
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookInstalledTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Records every filing it is asked for. */
    private class Recording : Bookshelf by FakeBookshelf() {
        val filed = mutableListOf<Triple<BookOrigin, String, BookDetails?>>()
        override suspend fun addDownloaded(file: File, origin: BookOrigin, ref: String, details: BookDetails?): Book? {
            filed += Triple(origin, ref, details)
            return app.granger.book("book:${origin.name}:$ref")
        }
    }

    private val scan = ArchiveItem(
        identifier = "scan1881", title = "Scientific American", creator = "Munn", date = "1881-10-15", year = 1881,
        subjects = listOf("science"), description = "A weekly.", language = "eng", licenseUrl = "", restricted = false,
        files = emptyList(),
    )

    private fun bookRecord(itemId: String) =
        record(itemId, DownloadState.INSTALLED, kind = ItemKind.BOOK, filePath = "/internal/books/archive-org/scan1881.epub")

    @Test fun anArchiveRecordIsFiledWithItsSidecarDetails() = runTest {
        val dir = tmp.newFolder("sidecars")
        ArchiveDownloads.writeSidecar(dir, scan, TextKind.OCR_TEXT)
        val shelf = Recording()
        var indexed = false
        fileBookRecord(shelf, dir, bookRecord("archive-org:scan1881"), startIndexing = { indexed = true })
        val (origin, ref, details) = shelf.filed.single()
        assertThat(origin).isEqualTo(BookOrigin.ARCHIVE_ORG)
        assertThat(ref).isEqualTo("scan1881")
        assertThat(details).isEqualTo(ArchiveDownloads.details(scan, TextKind.OCR_TEXT))
        assertThat(indexed).isTrue()
    }

    @Test fun anArchiveRecordWithoutASidecarHasNoDetails() = runTest {
        val shelf = Recording()
        fileBookRecord(shelf, tmp.newFolder("sidecars"), bookRecord("archive-org:scan1881")) {}
        assertThat(shelf.filed.single()).isEqualTo(Triple(BookOrigin.ARCHIVE_ORG, "scan1881", null))
    }

    @Test fun aRepairStillFilesTheDetails() = runTest {
        // Readiness re-files an installed record; the sidecar must still be there for it (it stays while the book does).
        val dir = tmp.newFolder("sidecars")
        ArchiveDownloads.writeSidecar(dir, scan, TextKind.OCR_TEXT)
        val shelf = Recording()
        fileBookRecord(shelf, dir, bookRecord("archive-org:scan1881")) {}
        fileBookRecord(shelf, dir, bookRecord("archive-org:scan1881")) {}
        assertThat(shelf.filed).hasSize(2)
        assertThat(shelf.filed[1].third).isEqualTo(ArchiveDownloads.details(scan, TextKind.OCR_TEXT))
        assertThat(File(dir, "scan1881.json").exists()).isTrue()
    }

    @Test fun aGrangerRecordIsAGrangerBook() = runTest {
        val shelf = Recording()
        fileBookRecord(shelf, tmp.newFolder("sidecars"), bookRecord("pg1342")) {}
        assertThat(shelf.filed.single()).isEqualTo(Triple(BookOrigin.GRANGER, "pg1342", null))
    }
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
