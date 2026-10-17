package app.granger.core.library

import app.granger.core.books.DrmKind
import app.granger.core.books.EpubInspector
import app.granger.core.books.Inspection
import app.granger.core.books.TestEpubs
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookshelfTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = inMemoryDatabase()
    private val dispatcher = StandardTestDispatcher()
    private val booksDir by lazy { tmp.newFolder("books") }
    private val grangerDir by lazy { File(booksDir, "granger").apply { mkdirs() } }
    private val importDir by lazy { File(booksDir, "imports") }
    private val coversDir by lazy { File(tmp.root, "covers") }
    private val src by lazy { tmp.newFolder("src") }
    private val shelf by lazy { RoomBookshelf(db, { listOf(booksDir) }, { importDir }, coversDir, dispatcher, { 1_000L }) }

    @After fun close() = db.close()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun sha256(file: File) =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun importFiles() = importDir.listFiles().orEmpty().map { it.name }

    private fun mark(bookId: String) =
        BookMark(0, bookId, MarkKind.HIGHLIGHT, "{\"href\":\"c1\"}", "It is a truth", "yellow", "", 1L, 1L)

    @Test fun downloadedBookIsFiledUnderGrangerClassics() = test {
        val file = TestEpubs.build(grangerDir, "pride_2026-10-01.epub", title = "Pride and Prejudice",
            authors = listOf("Jane Austen"), cover = TestEpubs.CoverStyle.EPUB3_PROPERTY)
        val book = shelf.addDownloaded(file, "pride")!!
        assertThat(book.id).isEqualTo(BookIds.of(BookOrigin.GRANGER, "pride"))
        assertThat(book.origin).isEqualTo(BookOrigin.GRANGER)
        assertThat(book.sourceRef).isEqualTo("pride")
        assertThat(book.title).isEqualTo("Pride and Prejudice")
        assertThat(book.author).isEqualTo("Jane Austen")
        assertThat(book.filePath).isEqualTo(file.absolutePath)
        assertThat(book.indexedAt).isNull()
        assertThat(File(book.coverPath!!).readBytes()).isEqualTo(TestEpubs.COVER_BYTES)
        assertThat(File(book.coverPath).parentFile).isEqualTo(coversDir)

        val shelves = RoomShelves(db, StubLibrary(emptyList()), shelf)
        val row = shelves.shelves.first().single().collections.single()
        assertThat(row.collection.defaultKey).isEqualTo("granger-classics")
        assertThat(row.collection.name).isEqualTo("Granger Classics")
        assertThat(row.books.map { it.id }).containsExactly(book.id)
    }

    @Test fun addDownloadedRejectsAFileThatIsNotAnEpub() = test {
        val file = File(grangerDir, "junk_2026-10-01.epub").apply { writeText("junk") }
        assertThat(shelf.addDownloaded(file, "junk")).isNull()
        assertThat(shelf.books.first()).isEmpty()
    }

    @Test fun redownloadKeepsPositionAndMarks() = test {
        val first = shelf.addDownloaded(TestEpubs.build(grangerDir, "pride_2026-10-01.epub"), "pride")!!
        shelf.savePosition(first.id, "{\"p\":1}", 0.4, "Chapter 3")
        val markId = shelf.addMark(mark(first.id))
        assertThat(shelf.remove(first.id)).isTrue()

        val second = shelf.addDownloaded(
            TestEpubs.build(grangerDir, "pride_2026-11-01.epub", title = "Pride (revised)"), "pride",
        )!!
        assertThat(second.id).isEqualTo(first.id)
        assertThat(second.title).isEqualTo("Pride (revised)")
        assertThat(shelf.position(second.id)!!.chapterTitle).isEqualTo("Chapter 3")
        assertThat(shelf.positions.first()[second.id]!!.totalProgression).isEqualTo(0.4)
        assertThat(shelf.marks(second.id).first().map { it.id }).containsExactly(markId)
    }

    @Test fun marksCanBeUpdatedAndDeleted() = test {
        val book = shelf.addDownloaded(TestEpubs.build(grangerDir, "a_2026-10-01.epub"), "a")!!
        val id = shelf.addMark(mark(book.id))
        shelf.updateMark(mark(book.id).copy(id = id, note = "Lovely", color = "green"))
        assertThat(shelf.marks(book.id).first().single().note).isEqualTo("Lovely")
        shelf.deleteMark(id)
        assertThat(shelf.marks(book.id).first()).isEmpty()
    }

    @Test fun importEpubCopiesAndRegisters() = test {
        val source = TestEpubs.build(src, "My Novel (2nd ed).epub", title = "My Novel")
        val result = shelf.import("My Novel (2nd ed).epub") { source.inputStream() }
        val sha = sha256(source)
        assertThat(result).isEqualTo(ImportResult.Imported(BookIds.of(BookOrigin.IMPORT, sha)))

        val book = shelf.book((result as ImportResult.Imported).bookId)!!
        assertThat(book.origin).isEqualTo(BookOrigin.IMPORT)
        assertThat(book.sourceRef).isEqualTo(sha)
        assertThat(book.title).isEqualTo("My Novel")
        assertThat(File(book.filePath)).isEqualTo(File(importDir, "${sha.take(12)}-My-Novel-2nd-ed-.epub"))
        assertThat(File(book.filePath).readBytes()).isEqualTo(source.readBytes())
        assertThat(importFiles()).containsExactly(File(book.filePath).name)
        assertThat(source.exists()).isTrue()
    }

    @Test fun importTxtConvertsToEpub() = test {
        val source = File(src, "field notes.txt").apply { writeText("The first day.\n\nThe second day.") }
        val result = shelf.import("field notes.txt") { source.inputStream() }
        assertThat(result).isInstanceOf(ImportResult.Imported::class.java)

        val book = shelf.book((result as ImportResult.Imported).bookId)!!
        assertThat(book.sourceRef).isEqualTo(sha256(source))
        assertThat(book.title).isEqualTo("field notes")
        assertThat(File(book.filePath).name).isEqualTo("${sha256(source).take(12)}-field-notes.epub")
        assertThat(EpubInspector.inspect(File(book.filePath))).isInstanceOf(Inspection.Ok::class.java)
        assertThat(importFiles()).containsExactly(File(book.filePath).name)
    }

    @Test fun importMarkdownWithoutAKnownNameIsSniffedAsText() = test {
        val source = File(src, "README").apply { writeText("# Title\n\nSome words.") }
        val result = shelf.import("README") { source.inputStream() }
        val book = shelf.book((result as ImportResult.Imported).bookId)!!
        assertThat(book.title).isEqualTo("README")
        assertThat(File(book.filePath).name).endsWith("-README.epub")
    }

    @Test fun importSameFileTwiceIsAlreadyThere() = test {
        val source = TestEpubs.build(src, "twice.epub")
        val first = shelf.import("twice.epub") { source.inputStream() } as ImportResult.Imported
        val second = shelf.import("copy of twice.epub") { source.inputStream() }
        assertThat(second).isEqualTo(ImportResult.AlreadyThere(first.bookId))
        assertThat(importFiles()).hasSize(1)
        assertThat(shelf.books.first()).hasSize(1)
    }

    @Test fun importRejectsProtectedAndLeavesNoFile() = test {
        val source = TestEpubs.build(src, "locked.epub", drmFiles = mapOf("META-INF/license.lcpl" to "{}"))
        val result = shelf.import("locked.epub") { source.inputStream() }
        assertThat(result).isEqualTo(ImportResult.Protected(DrmKind.LCP))
        assertThat(result.message("locked.epub"))
            .isEqualTo("locked.epub is copy-protected (DRM). Granger can't open it and won't remove the protection.")
        assertThat(importFiles()).isEmpty()
        assertThat(shelf.books.first()).isEmpty()
    }

    @Test fun importDamagedLeavesNoFile() = test {
        val zipLike = File(src, "broken.epub").apply { writeBytes("PK\u0003\u0004garbage".toByteArray()) }
        val result = shelf.import("broken.epub") { zipLike.inputStream() }
        assertThat(result).isInstanceOf(ImportResult.Damaged::class.java)
        assertThat(result.message("broken.epub")).isEqualTo("broken.epub is damaged or isn't a complete e-book.")

        val empty = File(src, "empty.epub").apply { writeBytes(ByteArray(0)) }
        assertThat(shelf.import("empty.epub") { empty.inputStream() }).isInstanceOf(ImportResult.Damaged::class.java)

        val binary = File(src, "scan.pdf").apply { writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0, 1, 2, 0)) }
        val unsupported = shelf.import("scan.pdf") { binary.inputStream() }
        assertThat(unsupported).isEqualTo(ImportResult.Unsupported)
        assertThat(unsupported.message("scan.pdf")).isEqualTo("scan.pdf isn't an EPUB, TXT or Markdown file.")

        assertThat(importFiles()).isEmpty()
        assertThat(shelf.books.first()).isEmpty()
    }

    @Test fun importThatThrowsLeavesNoFile() = test {
        val halfway = shelf.import("half.epub") {
            object : java.io.InputStream() {
                var sent = 0
                override fun read(): Int = if (sent++ < 10) 'P'.code else throw java.io.IOException("unplugged")
            }
        }
        assertThat(halfway).isEqualTo(ImportResult.Damaged("Couldn't read the file"))
        val unopenable = shelf.import("gone.epub") { throw java.io.FileNotFoundException("gone") }
        assertThat(unopenable).isEqualTo(ImportResult.Damaged("Couldn't read the file"))
        assertThat(importFiles()).isEmpty()
        assertThat(shelf.books.first()).isEmpty()
    }

    @Test fun importOverCapTextIsDamaged() = test {
        val small = RoomBookshelf(db, { listOf(booksDir) }, { importDir }, coversDir, dispatcher, { 1_000L }, maxTextBytes = 10)
        val source = File(src, "long.txt").apply { writeText("This text is longer than ten bytes.") }
        assertThat(small.import("long.txt") { source.inputStream() }).isEqualTo(ImportResult.Damaged("Too large"))
        assertThat(importFiles()).isEmpty()
        // EPUBs aren't held in memory whole, so the cap doesn't apply to them.
        val epub = TestEpubs.build(src, "book.epub")
        assertThat(small.import("book.epub") { epub.inputStream() }).isInstanceOf(ImportResult.Imported::class.java)
    }

    @Test fun pdfRenamedEpubIsUnsupported() = test {
        val pdf = File(src, "paper.epub").apply { writeText("%PDF-1.7\nplain looking text without zero bytes") }
        assertThat(shelf.import("paper.epub") { pdf.inputStream() }).isEqualTo(ImportResult.Unsupported)
        // Whatever it's called.
        assertThat(shelf.import("paper.txt") { pdf.inputStream() }).isEqualTo(ImportResult.Unsupported)
        assertThat(shelf.import("paper") { pdf.inputStream() }).isEqualTo(ImportResult.Unsupported)
        assertThat(importFiles()).isEmpty()
        assertThat(shelf.books.first()).isEmpty()
    }

    @Test fun onlyTextOrExtensionlessNamesAreSniffedAsText() = test {
        val text = File(src, "story").apply { writeText("Once upon a time.\n\nThe end.") }
        for (name in listOf("story.epub", "story.html", "story.pdf", "story.docx")) {
            assertThat(shelf.import(name) { text.inputStream() }).isEqualTo(ImportResult.Unsupported)
        }
        assertThat(importFiles()).isEmpty()
        assertThat(shelf.import("story.text") { text.inputStream() }).isInstanceOf(ImportResult.Imported::class.java)
    }

    @Test fun booksIgnoresIndexedAtOnlyChanges() = test {
        val a = book("a")
        val emitted = kotlinx.coroutines.flow.flowOf(
            listOf(a),
            listOf(a.copy(indexedAt = 5L)),
            listOf(a.copy(indexedAt = 5L, title = "Renamed")),
        ).ignoringIndexedAt().toList()
        assertThat(emitted.map { it.single().title }).containsExactly("a", "Renamed").inOrder()
    }

    @Test fun textNameStartingWithPkIsConverted() = test {
        val source = File(src, "notes.txt").apply { writeText("PK wrote these notes.\n\nSecond paragraph.") }
        val result = shelf.import("notes.txt") { source.inputStream() }
        val book = shelf.book((result as ImportResult.Imported).bookId)!!
        assertThat(book.title).isEqualTo("notes")
        assertThat(EpubInspector.inspect(File(book.filePath))).isInstanceOf(Inspection.Ok::class.java)
        assertThat(importFiles()).containsExactly(File(book.filePath).name)
    }

    @Test fun reimportRestoresAMissingBook() = test {
        val source = TestEpubs.build(src, "again.epub")
        val first = shelf.import("again.epub") { source.inputStream() } as ImportResult.Imported
        File(shelf.book(first.bookId)!!.filePath).delete()
        shelf.refresh()
        assertThat(shelf.book(first.bookId)!!.status).isEqualTo(PackStatus.MISSING)

        assertThat(shelf.import("again.epub") { source.inputStream() }).isEqualTo(ImportResult.Imported(first.bookId))
        val book = shelf.book(first.bookId)!!
        assertThat(book.status).isEqualTo(PackStatus.OK)
        assertThat(File(book.filePath).exists()).isTrue()
        assertThat(importFiles()).containsExactly(File(book.filePath).name)
        // Present and OK: the next import is a no-op.
        assertThat(shelf.import("again.epub") { source.inputStream() }).isEqualTo(ImportResult.AlreadyThere(first.bookId))
    }

    @Test fun oddCoverExtensionsAreWrittenInsideCoversDir() = test {
        for ((ref, href) in listOf("slash" to "cover.j/pg", "long" to "cover.abcdefghij", "upper" to "cover.JPG")) {
            val file = TestEpubs.build(grangerDir, "${ref}_2026-01-01.epub", cover = TestEpubs.CoverStyle.EPUB3_PROPERTY, coverHref = href)
            val cover = File(shelf.addDownloaded(file, ref)!!.coverPath!!)
            assertThat(cover.parentFile).isEqualTo(coversDir)
            assertThat(cover.readBytes()).isEqualTo(TestEpubs.COVER_BYTES)
        }
        assertThat(coversDir.listFiles()!!.map { it.extension }).containsExactly("img", "img", "jpg")
    }

    @Test fun refreshMarksMissingAndRegistersStrays() = test {
        val alpha = shelf.addDownloaded(TestEpubs.build(grangerDir, "alpha_2026-01-01.epub"), "alpha")!!
        File(alpha.filePath).delete()
        TestEpubs.build(grangerDir, "beta_2026-01-01.epub", title = "Beta old")
        TestEpubs.build(grangerDir, "beta_2026-02-01.epub", title = "Beta new")
        File(grangerDir, "gamma_2026-01-01.epub").writeText("not a zip")
        TestEpubs.build(grangerDir, "war_and_peace_2026-01-01.epub", title = "War and Peace")
        File(grangerDir, ".incoming-123").writeText("partial")
        File(grangerDir, "notes.txt").writeText("not a book")
        importDir.mkdirs()
        val stray = TestEpubs.build(importDir, "0123456789ab-stray.epub", title = "Stray")

        shelf.refresh()

        assertThat(shelf.book(alpha.id)!!.status).isEqualTo(PackStatus.MISSING)
        val beta = shelf.book(BookIds.of(BookOrigin.GRANGER, "beta"))!!
        assertThat(beta.title).isEqualTo("Beta new")
        assertThat(beta.status).isEqualTo(PackStatus.OK)
        assertThat(shelf.book(BookIds.of(BookOrigin.GRANGER, "gamma"))!!.status).isEqualTo(PackStatus.CORRUPT)
        // A catalog id may hold underscores; only the last one starts the release date.
        assertThat(shelf.book(BookIds.of(BookOrigin.GRANGER, "war_and_peace"))!!.title).isEqualTo("War and Peace")
        val imported = shelf.book(BookIds.of(BookOrigin.IMPORT, sha256(stray)))!!
        assertThat(imported.title).isEqualTo("Stray")
        assertThat(imported.origin).isEqualTo(BookOrigin.IMPORT)
        assertThat(shelf.books.first()).hasSize(5)

        // The file comes back: the book is OK again and nothing is registered twice.
        TestEpubs.build(grangerDir, "alpha_2026-01-01.epub")
        shelf.refresh()
        assertThat(shelf.book(alpha.id)!!.status).isEqualTo(PackStatus.OK)
        assertThat(shelf.book(BookIds.of(BookOrigin.GRANGER, "beta"))!!.filePath).isEqualTo(beta.filePath)
        assertThat(shelf.books.first()).hasSize(5)
    }

    @Test fun removeDeletesFileButKeepsMarks() = test {
        val book = shelf.addDownloaded(
            TestEpubs.build(grangerDir, "x_2026-01-01.epub", cover = TestEpubs.CoverStyle.EPUB3_PROPERTY), "x",
        )!!
        shelf.savePosition(book.id, "{}", 0.5, "One")
        shelf.addMark(mark(book.id))
        db.bookDao().insertChunk(BookChunk(0, book.id, "OEBPS/c001.xhtml", 0, 0.0))

        assertThat(shelf.remove(book.id)).isTrue()

        assertThat(File(book.filePath).exists()).isFalse()
        assertThat(File(book.coverPath!!).exists()).isFalse()
        assertThat(shelf.book(book.id)).isNull()
        assertThat(db.bookDao().chunkCount()).isEqualTo(0)
        assertThat(shelf.position(book.id)).isNotNull()
        assertThat(shelf.marks(book.id).first()).hasSize(1)
    }
}
