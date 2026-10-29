package app.granger.reader

import app.granger.FakeBookshelf
import app.granger.core.library.BookFormat
import app.granger.core.library.BookOrigin
import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimEntry
import app.granger.core.zim.ZimHit
import app.granger.core.zim.ZimItem
import app.granger.core.zim.ZimMetadata
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackDocumentsTest {
    @get:Rule val tmp = TemporaryFolder()

    /**
     * One PDF entry, [pdfPath]; [copies] counts copies out, and [failCopy] makes them fail. [database], when set, is
     * the pack's `database.js` (a nautilus pack's list of documents), and [databaseReads] counts its reads.
     */
    private class PdfPack(private val pdfPath: String = PATH) : ZimArchive {
        var copies = 0
        var failCopy = false
        var database: String? = null
        var databaseReads = 0
        override val uuid = "zim1"
        override val metadata = ZimMetadata("Water", "", "eng", "", "water")
        override val mainPath: String? = null
        override val hasFulltextIndex = false
        override fun resolvePath(path: String) = path.takeIf { it == pdfPath }
        override fun read(path: String): ZimItem? {
            if (path == "database.js") {
                databaseReads++
                return database?.let { ZimItem(path, "application/javascript", it.toByteArray()) }
            }
            return resolvePath(path)?.let { ZimItem(it, "application/pdf", BYTES) }
        }
        override fun entry(path: String) = resolvePath(path)?.let { ZimEntry(it, "Water treatment", "application/pdf", BYTES.size.toLong()) }
        override fun copyTo(path: String, out: File): Boolean {
            copies++
            if (failCopy || resolvePath(path) == null) return false
            out.writeBytes(BYTES)
            return true
        }
        override fun randomPath(): String? = null
        override fun suggest(query: String, limit: Int) = emptyList<ZimHit>()
        override fun search(query: String, limit: Int) = emptyList<ZimHit>()
        override fun close() {}

        companion object {
            const val PATH = "docs/water.pdf"
            val BYTES = "%PDF-1.4 water".toByteArray()
        }
    }

    private val pack = PdfPack()
    private val bookshelf = FakeBookshelf()
    private val dir by lazy { tmp.newFolder("packs") }

    private fun documents(dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        PackDocuments(bookshelf, { id -> pack.takeIf { id == "zim1" } }, { dir }, dispatcher)

    @Test fun aTappedPdfIsCopiedOutOnceAndKeptAsAPackDocument() = runTest(UnconfinedTestDispatcher()) {
        val docs = documents(UnconfinedTestDispatcher(testScheduler))
        val opened = docs.open("zim1", PdfPack.PATH) as PackDocuments.Opened.Ready
        assertThat(opened.book.origin).isEqualTo(BookOrigin.PACK)
        assertThat(opened.book.format).isEqualTo(BookFormat.PDF)
        assertThat(opened.book.title).isEqualTo("Water treatment")
        val file = File(opened.book.filePath)
        assertThat(file.parentFile).isEqualTo(File(dir, "zim1"))
        assertThat(file.readBytes()).isEqualTo(PdfPack.BYTES)
        assertThat(bookshelf.packDocuments).containsExactly(Triple("zim1", PdfPack.PATH, "Water treatment"))
        assertThat(bookshelf.packDocumentLinkTexts).containsExactly("")

        // A second tap reuses the file; the link's words go with it, to name it if nothing better does.
        docs.open("zim1", PdfPack.PATH, "Treating water")
        assertThat(pack.copies).isEqualTo(1)
        assertThat(bookshelf.packDocumentLinkTexts.last()).isEqualTo("Treating water")
        // Gone (storage cleared): restore copies it out again.
        file.delete()
        assertThat(docs.restore(opened.book)).isTrue()
        assertThat(file.isFile).isTrue()
        assertThat(pack.copies).isEqualTo(2)
        // Its name stands in for the link's words, which are gone.
        assertThat(bookshelf.packDocumentLinkTexts.last()).isEqualTo("Water treatment")
    }

    @Test fun aNautilusPacksListNamesItsPdfsReadOnce() = runTest(UnconfinedTestDispatcher()) {
        // zimgit-food-preparation_en_2025-04-11.zim: the link's words were the file's name (2026-10-29).
        val path = "files/Food Preparation (2).pdf"
        val nautilus = PdfPack(path).apply {
            database = "var DATABASE = [\n{'_id': '00001', 'ti': 'Camping Recipes', 'fp': ['Food Preparation (2).pdf']},\n];"
        }
        val docs = PackDocuments(bookshelf, { nautilus }, { dir }, UnconfinedTestDispatcher(testScheduler))
        docs.open("zim1", path, "Food Preparation (2)")
        docs.open("zim1", path, "Food Preparation (2)")
        assertThat(bookshelf.packDocumentListedTitles).containsExactly("Camping Recipes", "Camping Recipes")
        assertThat(nautilus.databaseReads).isEqualTo(1)
        // A pack without a list lists nothing.
        documents(UnconfinedTestDispatcher(testScheduler)).open("zim1", PdfPack.PATH)
        assertThat(bookshelf.packDocumentListedTitles.last()).isEmpty()
    }

    @Test fun aFailedCopySaysWhyAndLeavesNothing() = runTest(UnconfinedTestDispatcher()) {
        pack.failCopy = true
        val docs = documents(UnconfinedTestDispatcher(testScheduler))
        val failed = docs.open("zim1", PdfPack.PATH) as PackDocuments.Opened.Failed
        assertThat(failed.message).startsWith("Couldn't open this PDF")
        assertThat(File(dir, "zim1").listFiles().orEmpty()).isEmpty()
        assertThat(bookshelf.packDocuments).isEmpty()
        assertThat(docs.open("other", PdfPack.PATH)).isInstanceOf(PackDocuments.Opened.Failed::class.java)
        assertThat(docs.open("zim1", "missing.pdf")).isInstanceOf(PackDocuments.Opened.Failed::class.java)
    }
}
