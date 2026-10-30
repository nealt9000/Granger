package app.granger.core.library

import app.granger.core.books.DrmKind
import app.granger.core.books.EpubInspector
import app.granger.core.books.Inspection
import app.granger.core.books.PdfFixtures
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

    @org.junit.Before fun pdfBox() = PdfFixtures.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())

    private val pngCover = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 9, 9)
    private val pdfShelf by lazy {
        RoomBookshelf(db, { listOf(booksDir) }, { importDir }, coversDir, dispatcher, { 1_000L }, pdfCovers = { _, _ -> pngCover })
    }

    @Test fun aGutenbergBookNotYetOpenedStaysListedThroughARefresh() = test {
        val unopened = book("43349", BookOrigin.GUTENBERG).copy(filePath = File(booksDir, "gutenberg/43349.epub").absolutePath)
        db.bookDao().upsert(unopened)
        shelf.refresh()
        assertThat(shelf.book(unopened.id)!!.status).isEqualTo(PackStatus.OK)
    }

    @Test fun removingAGutenbergBookDeletesOnlyItsCopy() = test {
        val file = TestEpubs.build(File(booksDir, "gutenberg").apply { mkdirs() }, name = "43349.epub")
        val cover = File(coversDir.apply { mkdirs() }, "c.png").apply { writeBytes(pngCover) }
        db.bookDao().upsert(
            book("43349", BookOrigin.GUTENBERG).copy(filePath = file.absolutePath, coverPath = cover.absolutePath, indexedAt = 5L),
        )
        assertThat(shelf.remove(BookIds.of(BookOrigin.GUTENBERG, "43349"))).isTrue()
        val row = shelf.book(BookIds.of(BookOrigin.GUTENBERG, "43349"))!!
        assertThat(file.exists()).isFalse()
        assertThat(cover.exists()).isFalse()
        assertThat(row.coverPath).isNull()
        assertThat(row.indexedAt).isNull()
    }

    @Test fun importsAPdf() = test {
        val source = PdfFixtures.text(src)
        val result = pdfShelf.import("water.pdf") { source.inputStream() }
        val book = pdfShelf.book((result as ImportResult.Imported).bookId)!!
        assertThat(book.format).isEqualTo(BookFormat.PDF)
        assertThat(book.title).isEqualTo(PdfFixtures.TITLE)
        assertThat(book.author).isEqualTo("A. Field")
        assertThat(book.filePath).endsWith("-The-Water-Book.pdf")
        assertThat(File(book.filePath).readBytes()).isEqualTo(source.readBytes())
        assertThat(File(book.coverPath!!).readBytes()).isEqualTo(pngCover)
        assertThat(book.coverPath).endsWith(".png")
        assertThat(book.searchable).isTrue()
        assertThat(pdfShelf.import("water.pdf") { source.inputStream() }).isEqualTo(ImportResult.AlreadyThere(book.id))
        assertThat(importFiles().filter { it.startsWith(".incoming-") }).isEmpty()
    }

    @Test fun importsAnOwnerPasswordPdf() = test {
        val source = PdfFixtures.encrypted(src)
        assertThat(pdfShelf.import("flags.pdf") { source.inputStream() }).isInstanceOf(ImportResult.Imported::class.java)
    }

    @Test fun refusesAPasswordLockedPdf() = test {
        val source = PdfFixtures.passwordLocked(src)
        val result = pdfShelf.import("locked.pdf") { source.inputStream() }
        assertThat(result).isEqualTo(ImportResult.Protected(DrmKind.PDF_ENCRYPTED))
        assertThat(result.message("locked.pdf")).isEqualTo(
            "locked.pdf is password-protected or encrypted. Granger can't open it and won't remove the protection.",
        )
        assertThat(importFiles()).isEmpty()
    }

    @Test fun refusesATruncatedPdf() = test {
        val source = PdfFixtures.truncated(src)
        assertThat(pdfShelf.import("broken.pdf") { source.inputStream() }).isInstanceOf(ImportResult.Damaged::class.java)
        assertThat(importFiles()).isEmpty()
        assertThat(pdfShelf.books.first()).isEmpty()
    }

    @Test fun addDownloadedRegistersAPdf() = test {
        val file = PdfFixtures.text(archiveDir, "steam_1908.pdf")
        val book = pdfShelf.addDownloaded(file, BookOrigin.ARCHIVE_ORG, "steam_1908#pdf",
            BookDetails("Steam Engines (PDF)", "A. Watt", "1908", emptyList(), "", ""))!!
        assertThat(book.id).isEqualTo("book:archive-org:steam_1908#pdf")
        assertThat(book.format).isEqualTo(BookFormat.PDF)
        assertThat(book.title).isEqualTo("Steam Engines (PDF)")
        // Removing it leaves a text download of the same item alone.
        val txt = File(archiveDir, "steam_1908.txt").apply { writeText("text") }
        assertThat(pdfShelf.remove(book.id)).isTrue()
        assertThat(file.exists()).isFalse()
        assertThat(txt.exists()).isTrue()
    }

    @Test fun refreshFindsAStrayPdfButNotPackDocuments() = test {
        importDir.mkdirs()
        val stray = PdfFixtures.text(importDir, "abc-water.pdf")
        PdfFixtures.text(File(booksDir, "packs/zim1"), "doc.pdf")
        pdfShelf.refresh()
        val books = pdfShelf.books.first()
        assertThat(books.map { it.filePath }).containsExactly(stray.absolutePath)
        assertThat(books.single().format).isEqualTo(BookFormat.PDF)
        assertThat(books.single().origin).isEqualTo(BookOrigin.IMPORT)
    }

    @Test fun packDocumentsAreHiddenBooksRemovedWithTheirPack() = test {
        val file = PdfFixtures.text(File(booksDir, "packs/zim1"), "doc.pdf")
        val book = pdfShelf.registerPackDocument("zim1", "docs/water.pdf", "Water treatment", file)!!
        assertThat(book.id).isEqualTo("book:pack:zim1/docs/water.pdf")
        assertThat(book.origin).isEqualTo(BookOrigin.PACK)
        assertThat(book.title).isEqualTo("Water treatment")
        assertThat(book.onShelf).isFalse()
        assertThat(pdfShelf.book(book.id)).isNotNull()

        val shelves = RoomShelves(db, StubLibrary(emptyList()), pdfShelf, RoomMediaLibrary(db))
        assertThat(shelves.shelves.first().flatMap { s -> s.collections.flatMap { it.books } }).isEmpty()

        val sessions = RoomReadingSessions(db)
        sessions.openTab(book.id, "", book.title)
        pdfShelf.savePosition(book.id, "{}", 0.5, "Page 2")
        pdfShelf.addMark(BookAnnotation(0, book.id, MarkKind.BOOKMARK, "{}", "p. 2", "", "", 1, 1))

        pdfShelf.removePackDocuments("zim1")
        assertThat(pdfShelf.book(book.id)).isNull()
        assertThat(file.exists()).isFalse()
        // Gone with its pack: no "Removed book" tab in the rail or Continue, no History row, place or bookmark.
        assertThat(sessions.railTabs(10).first()).isEmpty()
        assertThat(sessions.history.first()).isEmpty()
        assertThat(pdfShelf.position(book.id)).isNull()
        assertThat(pdfShelf.marks(book.id).first()).isEmpty()
    }

    @Test fun refreshForgetsWhatARemovedPackDocumentLeftBehind() = test {
        val sessions = RoomReadingSessions(db)
        val gone = BookIds.of(BookOrigin.PACK, "zim1/docs/old.pdf")
        sessions.openTab(gone, "", "Old")
        pdfShelf.savePosition(gone, "{}", 0.5, "Page 2")
        val kept = BookIds.of(BookOrigin.IMPORT, "removed")
        sessions.openTab(kept, "", "Removed import")

        pdfShelf.refresh()
        // The pack document's rows go; a removed book's tab stays, to say "Removed book".
        assertThat(sessions.railTabs(10).first().map { it.packId }).containsExactly(kept)
        assertThat(sessions.history.first().map { it.packId }).containsExactly(kept)
        assertThat(pdfShelf.position(gone)).isNull()
    }

    @Test fun aPackDocumentTitledByItsPathTakesThePdfsOwnTitle() = test {
        val named = PdfFixtures.text(File(booksDir, "packs/zim1"), "a.pdf")
        assertThat(pdfShelf.registerPackDocument("zim1", "site/docs/water.pdf", "site/docs/water.pdf", named)!!.title)
            .isEqualTo(PdfFixtures.TITLE)
        val untitled = PdfFixtures.imageOnly(File(booksDir, "packs/zim1"), "b.pdf")
        assertThat(pdfShelf.registerPackDocument("zim1", "site/docs/scan.pdf", "", untitled)!!.title).isEqualTo("scan")
    }

    @Test fun aPackDocumentsJunkTitleGivesWayToTheLinkThenTheFileName() = test {
        val junk = PdfFixtures.titled(File(booksDir, "packs/zim1"), "file://C:\\WINDOWS\\Desktop\\water.htm")
        assertThat(pdfShelf.registerPackDocument("zim1", "site/docs/root_cellar-plans.pdf", "site/docs/root_cellar-plans.pdf", junk, "Root cellar plans (PDF)")!!.title)
            .isEqualTo("Root cellar plans")
        assertThat(pdfShelf.registerPackDocument("zim1", "site/docs/root_cellar-plans.pdf", "", junk)!!.title)
            .isEqualTo("root cellar plans")
    }

    @Test fun aPackDocumentTakesThePacksListedTitleAndIsRenamedWhenRegisteredAgain() = test {
        val junk = PdfFixtures.titled(File(booksDir, "packs/zim1"), "file://C:\\WINDOWS\\Desktop\\x.htm")
        val path = "files/Food Preparation (2).pdf"
        val before = pdfShelf.registerPackDocument("zim1", path, path, junk, "Food Preparation (2)")!!
        assertThat(before.title).isEqualTo("Food Preparation (2)")
        // Opened again once the pack's list is read: the same book, under the list's title.
        val after = pdfShelf.registerPackDocument("zim1", path, path, junk, "Food Preparation (2)", "Camping Recipes")!!
        assertThat(after.id).isEqualTo(before.id)
        assertThat(pdfShelf.book(before.id)!!.title).isEqualTo("Camping Recipes")
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun sha256(file: File) =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun importFiles() = importDir.listFiles().orEmpty().map { it.name }

    private fun mark(bookId: String) =
        BookAnnotation(0, bookId, MarkKind.HIGHLIGHT, "{\"href\":\"c1\"}", "It is a truth", "yellow", "", 1L, 1L)

    @Test fun downloadedBookIsFiledUnderGrangerClassics() = test {
        val file = TestEpubs.build(grangerDir, "pride_2026-10-01.epub", title = "Pride and Prejudice",
            authors = listOf("Jane Austen"), cover = TestEpubs.CoverStyle.EPUB3_PROPERTY)
        val book = shelf.addDownloaded(file, BookOrigin.GRANGER, "pride")!!
        assertThat(book.id).isEqualTo(BookIds.of(BookOrigin.GRANGER, "pride"))
        assertThat(book.origin).isEqualTo(BookOrigin.GRANGER)
        assertThat(book.sourceRef).isEqualTo("pride")
        assertThat(book.title).isEqualTo("Pride and Prejudice")
        assertThat(book.author).isEqualTo("Jane Austen")
        assertThat(book.filePath).isEqualTo(file.absolutePath)
        assertThat(book.indexedAt).isNull()
        assertThat(File(book.coverPath!!).readBytes()).isEqualTo(TestEpubs.COVER_BYTES)
        assertThat(File(book.coverPath).parentFile).isEqualTo(coversDir)

        val shelves = RoomShelves(db, StubLibrary(emptyList()), shelf, RoomMediaLibrary(db))
        val row = shelves.shelves.first().single().collections.single()
        assertThat(row.collection.defaultKey).isEqualTo("granger-classics")
        assertThat(row.collection.name).isEqualTo("Granger Classics")
        assertThat(row.books.map { it.id }).containsExactly(book.id)
    }

    private val archiveDir by lazy { File(booksDir, "archive-org").apply { mkdirs() } }

    @Test fun archiveEpubIsFiledUnderArchiveOrgWithItsMetadata() = test {
        val file = TestEpubs.build(archiveDir, "steam_1908.epub", title = "Untitled scan")
        val book = shelf.addDownloaded(file, BookOrigin.ARCHIVE_ORG, "steam_1908",
            BookDetails("Steam Engines", "A. Watt", "1908", listOf("steam", "engines"), "A manual.", "eng"))!!
        assertThat(book.id).isEqualTo("book:archive-org:steam_1908")
        assertThat(book.title).isEqualTo("Steam Engines")
        assertThat(book.author).isEqualTo("A. Watt")
        assertThat(book.subjects).isEqualTo("steam; engines")
        assertThat(book.date).isEqualTo("1908")
        assertThat(book.description).isEqualTo("A manual.")
        assertThat(book.language).isEqualTo("eng")
    }

    @Test fun archiveTextIsConvertedBesideItAndRegistered() = test {
        val txt = File(archiveDir, "x.txt").apply { writeText("Chapter one.\n\nIt was a dark night.") }
        val book = shelf.addDownloaded(txt, BookOrigin.ARCHIVE_ORG, "x",
            BookDetails("Night Book", "", "", emptyList(), "", ""))!!
        assertThat(File(archiveDir, "x.epub").exists()).isTrue()
        assertThat(book.filePath).endsWith("x.epub")
        assertThat(book.title).isEqualTo("Night Book")
        assertThat(book.id).isEqualTo("book:archive-org:x")
    }

    private fun comic(name: String): File {
        val bitmap = android.graphics.Bitmap.createBitmap(20, 30, android.graphics.Bitmap.Config.ARGB_8888)
        val jpeg = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        return File(archiveDir, name).also { file ->
            java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
                for (page in listOf("01.jpg", "02.jpg")) {
                    zip.putNextEntry(java.util.zip.ZipEntry(page)); zip.write(jpeg); zip.closeEntry()
                }
            }
        }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun anArchiveComicIsFiledAsAPdfOfItsPages() = test {
        val book = pdfShelf.addDownloaded(comic("dkr.cbr"), BookOrigin.ARCHIVE_ORG, "dkr",
            BookDetails("The Dark Knight Returns", "", "", emptyList(), "", ""))!!
        assertThat(book.format).isEqualTo(BookFormat.PDF)
        assertThat(book.filePath).endsWith("dkr.pdf")
        assertThat(book.title).isEqualTo("The Dark Knight Returns")

        // Removed, it takes its comic with it, so the next refresh doesn't file it again.
        assertThat(pdfShelf.remove(book.id)).isTrue()
        pdfShelf.refresh()
        assertThat(pdfShelf.book(book.id)).isNull()
        assertThat(File(archiveDir, "dkr.cbr").exists()).isFalse()
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun refreshConvertsAStrayArchiveComic() = test {
        comic("strip.cbz")
        pdfShelf.refresh()
        assertThat(File(archiveDir, "strip.pdf").exists()).isTrue()
        assertThat(pdfShelf.book("book:archive-org:strip")!!.format).isEqualTo(BookFormat.PDF)
    }

    @Test fun archiveTextOverTheLimitIsRefused() = test {
        val small = RoomBookshelf(db, { listOf(booksDir) }, { importDir }, coversDir, dispatcher, { 1_000L }, maxTextBytes = 10)
        val txt = File(archiveDir, "big.txt").apply { writeText("x".repeat(100)) }
        assertThat(small.addDownloaded(txt, BookOrigin.ARCHIVE_ORG, "big")).isNull()
        assertThat(File(archiveDir, "big.epub").exists()).isFalse()
    }

    @Test fun blankDetailFieldsKeepTheEpubsOwn() = test {
        val file = TestEpubs.build(archiveDir, "b.epub", title = "Own Title", authors = listOf("Own Author"))
        val book = shelf.addDownloaded(file, BookOrigin.ARCHIVE_ORG, "b",
            BookDetails("Better Title", "", "", emptyList(), "", ""))!!
        assertThat(book.title).isEqualTo("Better Title")
        assertThat(book.author).isEqualTo("Own Author")
        assertThat(book.date).isEqualTo("2020-01-02")
    }

    @Test fun refreshRegistersAStrayArchiveEpub() = test {
        TestEpubs.build(archiveDir, "a.b_c.epub", title = "Stray")
        File(archiveDir, "a.b_c.json").writeText("{}")
        File(archiveDir, ".incoming-1.epub").writeText("partial")
        shelf.refresh()
        val books = shelf.books.first()
        assertThat(books.map { it.id }).containsExactly("book:archive-org:a.b_c")
        assertThat(books.single().origin).isEqualTo(BookOrigin.ARCHIVE_ORG)
    }

    @Test fun refreshConvertsAStrayArchiveText() = test {
        File(archiveDir, "scan.txt").writeText("Hello there.\n\nSecond paragraph.")
        shelf.refresh()
        assertThat(File(archiveDir, "scan.epub").exists()).isTrue()
        val book = shelf.book("book:archive-org:scan")!!
        assertThat(book.status).isEqualTo(PackStatus.OK)
    }

    @Test fun removingAConvertedTextBookDeletesItsTextSoRefreshDoesNotBringItBack() = test {
        val txt = File(archiveDir, "gone.txt").apply { writeText("Hello there.\n\nSecond paragraph.") }
        val book = shelf.addDownloaded(txt, BookOrigin.ARCHIVE_ORG, "gone")!!
        assertThat(shelf.remove(book.id)).isTrue()
        shelf.refresh()
        assertThat(shelf.book(book.id)).isNull()
        assertThat(File(archiveDir, "gone.txt").exists()).isFalse()
        assertThat(File(archiveDir, "gone.epub").exists()).isFalse()
    }

    @Test fun addDownloadedRejectsAFileThatIsNotAnEpub() = test {
        val file = File(grangerDir, "junk_2026-10-01.epub").apply { writeText("junk") }
        assertThat(shelf.addDownloaded(file, BookOrigin.GRANGER, "junk")).isNull()
        assertThat(shelf.books.first()).isEmpty()
    }

    @Test fun redownloadKeepsPositionAndMarks() = test {
        val first = shelf.addDownloaded(TestEpubs.build(grangerDir, "pride_2026-10-01.epub"), BookOrigin.GRANGER, "pride")!!
        shelf.savePosition(first.id, "{\"p\":1}", 0.4, "Chapter 3")
        val markId = shelf.addMark(mark(first.id))
        assertThat(shelf.remove(first.id)).isTrue()

        val second = shelf.addDownloaded(
            TestEpubs.build(grangerDir, "pride_2026-11-01.epub", title = "Pride (revised)"), BookOrigin.GRANGER, "pride",
        )!!
        assertThat(second.id).isEqualTo(first.id)
        assertThat(second.title).isEqualTo("Pride (revised)")
        assertThat(shelf.position(second.id)!!.chapterTitle).isEqualTo("Chapter 3")
        assertThat(shelf.positions.first()[second.id]!!.totalProgression).isEqualTo(0.4)
        assertThat(shelf.marks(second.id).first().map { it.id }).containsExactly(markId)
    }

    @Test fun marksCanBeUpdatedAndDeleted() = test {
        val book = shelf.addDownloaded(TestEpubs.build(grangerDir, "a_2026-10-01.epub"), BookOrigin.GRANGER, "a")!!
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
        assertThat(shelf.import("scan.pdf") { binary.inputStream() }).isInstanceOf(ImportResult.Damaged::class.java)
        assertThat(ImportResult.Unsupported.message("story.docx"))
            .isEqualTo("story.docx isn't an EPUB, PDF, TXT or Markdown file.")

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

    @Test fun aBrokenPdfIsDamagedWhateverItsName() = test {
        val pdf = File(src, "paper.epub").apply { writeText("%PDF-1.7\nplain looking text without zero bytes") }
        assertThat(shelf.import("paper.epub") { pdf.inputStream() }).isInstanceOf(ImportResult.Damaged::class.java)
        // Whatever it's called: the header decides, not the name.
        assertThat(shelf.import("paper.txt") { pdf.inputStream() }).isInstanceOf(ImportResult.Damaged::class.java)
        assertThat(shelf.import("paper") { pdf.inputStream() }).isInstanceOf(ImportResult.Damaged::class.java)
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

    @Test fun aGutenbergBookBecomingIndexedIsAChange() = test {
        val g = book("1", BookOrigin.GUTENBERG)
        val emitted = kotlinx.coroutines.flow.flowOf(
            listOf(g),
            listOf(g.copy(indexedAt = 5L)),
            // Indexed again: still searchable in full, nothing to redraw.
            listOf(g.copy(indexedAt = 9L)),
        ).ignoringIndexedAt().toList()
        assertThat(emitted.map { it.single().indexedAt }).containsExactly(null, 5L).inOrder()
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
            val cover = File(shelf.addDownloaded(file, BookOrigin.GRANGER, ref)!!.coverPath!!)
            assertThat(cover.parentFile).isEqualTo(coversDir)
            assertThat(cover.readBytes()).isEqualTo(TestEpubs.COVER_BYTES)
        }
        assertThat(coversDir.listFiles()!!.map { it.extension }).containsExactly("img", "img", "jpg")
    }

    @Test fun refreshMarksMissingAndRegistersStrays() = test {
        val alpha = shelf.addDownloaded(TestEpubs.build(grangerDir, "alpha_2026-01-01.epub"), BookOrigin.GRANGER, "alpha")!!
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
            TestEpubs.build(grangerDir, "x_2026-01-01.epub", cover = TestEpubs.CoverStyle.EPUB3_PROPERTY), BookOrigin.GRANGER, "x",
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
