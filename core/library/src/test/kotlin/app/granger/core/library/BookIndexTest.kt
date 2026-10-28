package app.granger.core.library

import app.granger.core.books.PdfFixtures
import app.granger.core.books.TestEpubs
import com.google.common.truth.Truth.assertThat
import java.io.File
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
class BookIndexTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = inMemoryDatabase()
    private val dispatcher = StandardTestDispatcher()
    private val booksDir by lazy { tmp.newFolder("books") }
    private val grangerDir by lazy { File(booksDir, "granger").apply { mkdirs() } }
    private val shelf by lazy {
        // Robolectric has no real file descriptors for the platform's PDF renderer.
        RoomBookshelf(db, { listOf(booksDir) }, { File(booksDir, "imports") }, File(tmp.root, "covers"), dispatcher,
            pdfCovers = { _, _ -> null })
    }
    private val index = BookIndex(db, dispatcher, now = { 5L })

    @After fun close() = db.close()

    @org.junit.Before fun pdfBox() = PdfFixtures.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())

    private suspend fun addPdf(ref: String, file: File): Book =
        shelf.addDownloaded(file, BookOrigin.ARCHIVE_ORG, ref)!!

    @Test fun aPdfIsIndexedByPage() = test {
        val water = addPdf("water", PdfFixtures.text(File(booksDir, "archive-org"), "water.pdf"))
        index.indexPending()

        val hit = index.search("slow sand filter").single()
        assertThat(hit.bookId).isEqualTo(water.id)
        assertThat(hit.href).isEqualTo("page/2")
        assertThat(hit.snippet).contains("<b>")
        assertThat(hit.quote).isNotNull()
        val passage = index.passages(listOf("sand filter")).single()
        assertThat(passage.href).isEqualTo("page/2")
        assertThat(passage.text).contains("ninety percent")
        val book = shelf.book(water.id)!!
        assertThat(book.indexedAt).isEqualTo(5L)
        assertThat(book.searchable).isTrue()
        // Running heads aren't in the index.
        assertThat(index.search("water book")).isEmpty()
    }

    @Test fun aScanIsMarkedUnsearchableAndIndexesNothing() = test {
        val scan = addPdf("scan", PdfFixtures.imageOnly(File(booksDir, "archive-org"), "scan.pdf"))
        index.indexPending()
        val book = shelf.book(scan.id)!!
        assertThat(book.indexedAt).isEqualTo(5L)
        assertThat(book.searchable).isFalse()
        assertThat(index.chunks(scan.id)).isEmpty()
    }

    @Test fun packDocumentsAreNeverIndexed() = test {
        val file = PdfFixtures.text(File(booksDir, "packs/z"), "doc.pdf")
        val doc = shelf.registerPackDocument("z", "docs/water.pdf", "Water", file)!!
        index.indexPending()
        assertThat(shelf.book(doc.id)!!.indexedAt).isNull()
        assertThat(index.search("slow sand filter")).isEmpty()
    }

    @Test fun chunkLabelsNameThePageOrTheChapter() = test {
        val water = addPdf("water", PdfFixtures.text(File(booksDir, "archive-org"), "water.pdf"))
        val moby = add("moby", "<p>Call me Ishmael.</p>", "<p>The whale surfaced at dawn.</p>", "<p>The end.</p>")
        index.indexPending()
        val pdf = shelf.book(water.id)!!
        assertThat(chunkLabel(pdf, "page/2", index.chunks(pdf.id))).isEqualTo("Page 2")
        val chunks = index.chunks(moby.id)
        assertThat(chunks.map { it.href })
            .containsExactly("OEBPS/c001.xhtml", "OEBPS/c002.xhtml", "OEBPS/c003.xhtml").inOrder()
        assertThat(chunkLabel(moby, "OEBPS/c003.xhtml", chunks)).isEqualTo("Chapter 3")
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private suspend fun add(ref: String, vararg chapters: String, title: String = ref): Book =
        shelf.addDownloaded(TestEpubs.build(grangerDir, "${ref}_2026-01-01.epub", title = title, chapters = chapters.toList()), BookOrigin.GRANGER, ref)!!

    private fun chunkSequence(): Long =
        db.query("SELECT seq FROM sqlite_sequence WHERE name = 'book_chunks'", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    @Test fun indexesAndFindsAWord() = test {
        val moby = add("moby", "<p>Call me Ishmael.</p>", "<p>The whale surfaced at dawn.</p>", title = "Moby-Dick")
        index.indexPending()

        val hit = index.search("whale").single()
        assertThat(hit.bookId).isEqualTo(moby.id)
        assertThat(hit.bookTitle).isEqualTo("Moby-Dick")
        assertThat(hit.author).isEqualTo("Ann Author")
        assertThat(hit.href).isEqualTo("OEBPS/c002.xhtml")
        assertThat(hit.snippet).contains("<b>whale</b>")
        // The last word is a prefix.
        assertThat(index.search("surf").map { it.href }).containsExactly("OEBPS/c002.xhtml")
        assertThat(index.search("call ishm").map { it.href }).containsExactly("OEBPS/c001.xhtml")
        assertThat(shelf.book(moby.id)!!.indexedAt).isEqualTo(5L)
    }

    @Test fun hitsAreGroupedByBookAndLimited() = test {
        val many = add("many", "<p>fox one</p>", "<p>fox two</p>", "<p>fox three</p>", "<p>fox four</p>")
        val few = add("few", "<p>fox alone</p>")
        index.indexPending()

        val hits = index.search("fox", limit = 4, perBook = 3)
        assertThat(hits.map { it.bookId }).containsExactly(many.id, many.id, many.id, few.id).inOrder()
        // Each hit says how many places its book matched, though only three are listed.
        assertThat(hits.map { it.matches }).containsExactly(4, 4, 4, 1).inOrder()
        assertThat(index.search("fox", limit = 2).map { it.bookId }).containsExactly(many.id, many.id)
    }

    @Test fun indexingIsResumableAndIdempotent() = test {
        val a = add("alpha", "<p>apples everywhere</p>")
        index.indexPending()
        val b = add("beta", "<p>bananas everywhere</p>")
        index.beforeCommit = { if (it == b.id) error("disk full") }
        index.indexPending()

        assertThat(index.search("apples").map { it.bookId }).containsExactly(a.id)
        assertThat(index.search("bananas")).isEmpty()
        assertThat(db.bookDao().notIndexed().map { it.id }).containsExactly(b.id)

        index.beforeCommit = {}
        index.indexPending()
        assertThat(index.search("bananas").map { it.bookId }).containsExactly(b.id)
        assertThat(index.search("everywhere").map { it.bookId }).containsExactly(a.id, b.id)
        assertThat(db.bookDao().notIndexed()).isEmpty()
    }

    @Test fun bookChangedDuringIndexingStaysPending() = test {
        val moby = add("moby", "<p>The whale surfaced.</p>")
        // A re-download lands while the old file is being indexed.
        index.beforeCommit = {
            db.openHelper.writableDatabase.execSQL("UPDATE books SET filePath = ? WHERE id = ?", arrayOf("/new/moby.epub", moby.id))
        }
        index.indexPending()
        assertThat(db.bookDao().notIndexed().map { it.id }).containsExactly(moby.id)
    }

    @Test fun userTypedTrailingStarStillMatches() = test {
        add("talk", "<p>He said hello there.</p>")
        index.indexPending()
        assertThat(index.search("hello*")).hasSize(1)
        assertThat(index.search("hel**")).hasSize(1)
        assertThat(index.search("said hello*")).hasSize(1)
    }

    @Test fun reindexedBookMatchesOnce() = test {
        add("moby", "<p>The whale surfaced.</p>")
        index.indexPending()
        val chunks = db.bookDao().chunkCount()
        add("moby", "<p>The whale surfaced.</p>")
        index.indexPending()

        assertThat(index.search("whale")).hasSize(1)
        assertThat(db.bookDao().chunkCount()).isEqualTo(chunks)
    }

    @Test fun removedBookNeverMatches() = test {
        val moby = add("moby", "<p>The whale surfaced.</p>")
        index.indexPending()
        assertThat(shelf.remove(moby.id)).isTrue()
        index.indexPending()
        assertThat(index.search("whale")).isEmpty()
    }

    @Test fun missingFileIsSkippedAndLeftPending() = test {
        val moby = add("moby", "<p>The whale surfaced.</p>")
        File(moby.filePath).delete()
        index.indexPending()
        assertThat(db.bookDao().notIndexed().map { it.id }).containsExactly(moby.id)
        assertThat(index.search("whale")).isEmpty()
    }

    @Test fun snippetIsEmptyWhenTheFileIsGone() = test {
        val moby = add("moby", "<p>The whale surfaced.</p>")
        index.indexPending()
        File(moby.filePath).delete()
        val hit = index.search("whale").single()
        assertThat(hit.snippet).isEmpty()
    }

    @Test fun compactionRebuildsWhenOrphansDominate() = test {
        val compacting = BookIndex(db, dispatcher, now = { 5L }, compactThreshold = 1)
        add("moby", "<p>The whale surfaced.</p>")
        compacting.indexPending()
        add("moby", "<p>The whale surfaced.</p>")
        compacting.indexPending()
        // One orphan for one live chunk: not yet dominant.
        assertThat(chunkSequence()).isEqualTo(2L)

        add("moby", "<p>The whale surfaced.</p>")
        compacting.indexPending()
        // Two orphans for one live chunk: rebuilt from scratch, ids start again.
        assertThat(db.bookDao().chunkCount()).isEqualTo(1)
        assertThat(chunkSequence()).isEqualTo(1L)
        assertThat(compacting.search("whale")).hasSize(1)
        assertThat(db.bookDao().notIndexed()).isEmpty()
    }

    @Test fun quotesInQueryAreSafe() = test {
        add("talk", "<p>He said \"hello there\" and left.</p>")
        index.indexPending()

        assertThat(index.search("\"hello")).hasSize(1)
        assertThat(index.search("hello\" \"left")).hasSize(1)
        assertThat(index.search("hello\" OR \"left")).isEmpty()
        assertThat(index.search("\"\"\"")).isEmpty()
        assertThat(index.search("   ")).isEmpty()
        assertThat(index.search("")).isEmpty()
        assertThat(index.search("- * : ( )")).isEmpty()
        assertThat(index.search("NEAR hello*")).isEmpty()
    }

    @Test fun passagesMatchAnyKeyword() = test {
        add("moby", "<p>Call me Ishmael.</p>", "<p>The whale surfaced at dawn.</p>")
        index.indexPending()

        val found = index.passages(listOf("whale", "nonexistentword"))
        assertThat(found).hasSize(1)
        assertThat(found.single().text).contains("whale")
        assertThat(found.single().href).isEqualTo("OEBPS/c002.xhtml")
        assertThat(index.passages(listOf("ishmael", "whale"))).hasSize(2)
    }

    @Test fun passagesMatchAPhrasesWordsAnywhereInAChunk() = test {
        add("merchant", "<p>The quality of mercy is not strained, said Portia.</p>", "<p>Mercy alone.</p>", "<p>Portia alone.</p>")
        index.indexPending()

        assertThat(index.passages(listOf("portia mercy")).map { it.href }).containsExactly("OEBPS/c001.xhtml")
        assertThat(index.passages(listOf("portia dialogue"))).isEmpty()
        // A chunk two phrases match comes back once.
        assertThat(index.passages(listOf("portia mercy", "quality mercy", "mercy alone")).map { it.href })
            .containsExactly("OEBPS/c001.xhtml", "OEBPS/c002.xhtml")
        // Query syntax inside a keyword is just words.
        assertThat(index.passages(listOf("mercy OR nothing"))).isEmpty()
    }

    @Test fun passagesComeInChunkOrder() = test {
        add("many", "<p>fox one</p>", "<p>fox two</p>", "<p>fox three</p>")
        index.indexPending()

        assertThat(index.passages(listOf("fox"), limit = 2).map { it.href }).containsExactly("OEBPS/c001.xhtml", "OEBPS/c002.xhtml").inOrder()
    }

    @Test fun oneDamagedBookDoesNotDropTheOthers() = test {
        val bad = add("bad", "<p>fox in the bad book ${"padding ".repeat(200)}</p>")
        add("good", "<p>fox in the good book</p>")
        index.indexPending()
        // A path the zip reader rejects outright (not just an unreadable file): this book's passages go, not the other's.
        db.openHelper.writableDatabase.execSQL("UPDATE books SET filePath = ? WHERE id = ?", arrayOf<Any>("bad\u0000path", bad.id))

        val found = index.passages(listOf("fox"))
        assertThat(found.map { it.text }).containsExactly("fox in the good book")
    }

    @Test fun passagesCarryTheBook() = test {
        val moby = add("moby", "<p>The whale surfaced at dawn.</p>", title = "Moby-Dick")
        index.indexPending()

        val passage = index.passages(listOf("whale")).single()
        assertThat(passage.book.id).isEqualTo(moby.id)
        assertThat(passage.book.title).isEqualTo("Moby-Dick")
        assertThat(passage.progression).isAtLeast(0.0)
    }

    @Test fun passagesOfNothingIsEmpty() = test {
        add("moby", "<p>The whale surfaced at dawn.</p>")
        index.indexPending()

        assertThat(index.passages(emptyList())).isEmpty()
        assertThat(index.passages(listOf("\""))).isEmpty()
        assertThat(index.passages(listOf("  ", "-"))).isEmpty()
    }

    @Test fun passagesPreferTheChunkWithTheRareWord() = test {
        add("a", *Array(30) { "<p>death and age number $it</p>" })
        add("b", "<p>Methuselah lived nine hundred sixty and nine years and he died</p>")
        index.indexPending()

        val found = index.passages(listOf("methuselah", "death", "age"))
        assertThat(found.first().text).contains("Methuselah")
    }

    @Test fun passagesMatchingMoreKeywordsRankHigher() = test {
        add("a", "<p>wine one</p>", "<p>wine two</p>", "<p>wine and drunk</p>", "<p>wine three</p>")
        index.indexPending()

        assertThat(index.passages(listOf("wine", "drunk")).first().text).isEqualTo("wine and drunk")
    }

    @Test fun passagesKeepAtMostPerBook() = test {
        add("a", *Array(10) { "<p>fox $it</p>" })
        index.indexPending()

        assertThat(index.passages(listOf("fox"), perBook = 4)).hasSize(4)
    }

    @Test fun commonKeywordDoesNotCrowdOutARareOne() = test {
        add("a", *Array(200) { "<p>bible verse $it</p>" })
        add("b", "<p>a tattoo here</p>")
        index.indexPending()

        val found = index.passages(listOf("bible", "tattoo"), limit = 10, perBook = 10)
        assertThat(found.map { it.text }).contains("a tattoo here")
    }

    @Test fun bm25DecodesMatchinfo() {
        val ints = intArrayOf(1, 1, 100, 2, 15, 10)
        val blob = ByteArray(ints.size * 4)
        ints.forEachIndexed { i, v -> for (b in 0..3) blob[4 * i + b] = (v shr (8 * b)).toByte() }
        val idf = Math.log((100 - 10 + 0.5) / (10 + 0.5) + 1)
        assertThat(bm25(blob)).isWithin(1e-9).of(idf * 2 * 2.2 / (2 + 1.2))
    }

    @Test fun bestChunkAtAHighIdIsFoundPastACommonWord() = test {
        add("a", *Array(200) { "<p>bible verse $it</p>" }, "<p>bible tattoo</p>")
        index.indexPending()

        // Old per-keyword id order gave "bible" one slot (chunk 1) ahead of the tattoo chunk, which has the highest id.
        assertThat(index.passages(listOf("bible", "tattoo"), limit = 3, perBook = 3).first().text).isEqualTo("bible tattoo")
        assertThat(index.passages(listOf("bible tattoo", "bible"), limit = 3, perBook = 3).first().text).isEqualTo("bible tattoo")
    }

    @Test fun passagesInSearchesOnlyThatBook() = test {
        add("a", "<p>money evil money evil</p>", "<p>money</p>")
        val b = add("b", "<p>money</p>", "<p>money and evil</p>", "<p>evil</p>", "<p>money evil money evil money evil</p>")
        index.indexPending()

        val found = index.passagesIn(b.id, listOf("money evil"))
        assertThat(found.map { it.book.id }.toSet()).containsExactly(b.id)
        assertThat(found.map { it.text }).containsExactly("money evil money evil money evil", "money and evil").inOrder()
        assertThat(index.passagesIn(b.id, listOf("money"), limit = 2)).hasSize(2)
        assertThat(index.passagesIn(b.id, emptyList())).isEmpty()
    }
}
