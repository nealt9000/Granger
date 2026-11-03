package app.granger.core.library

import app.granger.core.books.PdfFixtures
import app.granger.core.books.TestEpubs
import app.granger.core.research.SourceKind
import app.granger.core.vectors.VectorFile
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan R2 Task 9: the books you add get vectors made on the phone, and the search by meaning finds them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookVectorsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = inMemoryDatabase()
    private val dispatcher = StandardTestDispatcher()
    private val pool = Executors.newFixedThreadPool(2)
    private val booksDir by lazy { tmp.newFolder("books") }
    private val vectorsDir by lazy { File(tmp.root, "vectors-own") }
    private val shelf by lazy {
        RoomBookshelf(db, { listOf(booksDir) }, { File(booksDir, "imports") }, File(tmp.root, "covers"), dispatcher, { 1_000L }, pdfCovers = { _, _ -> null })
    }

    @Before fun pdfBox() = PdfFixtures.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())

    @After fun close() {
        db.close()
        pool.shutdown()
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private val sha = "a".repeat(64)
    private val embedded = mutableListOf<Pair<String, String>>()

    /** Four dims: woods and wishes point one way, anything else another. */
    private fun vectors(embedder: () -> String? = { sha }, available: Boolean = true) = BookVectors(
        shelf, vectorsDir,
        embed = { docs -> if (!available) null else docs.also { embedded += it }.map { (_, text) -> if ("woods" in text) floatArrayOf(0f, 0f, 1f, 0f) else floatArrayOf(1f, 0f, 0f, 0f) } },
        embedderSha = { embedder() }, io = dispatcher, batch = 2, dims = 4,
    )

    private suspend fun importWalden(): String {
        val src = TestEpubs.build(tmp.newFolder(), "walden.epub", title = "Walden", chapters = listOf(
            "<p>" + "I went to the woods because I wished to live deliberately. ".repeat(12) + "</p>",
            "<p>" + "The mass of men lead lives of quiet desperation, it is said. ".repeat(12) + "</p>",
        ))
        return (shelf.import("walden.epub") { src.inputStream() } as ImportResult.Imported).bookId
    }

    @Test fun anImportedBookIsEmbeddedTitledAndFoundByMeaning() = test {
        val id = importWalden()
        val vectors = vectors()
        assertThat(vectors.embedPending()).isEqualTo(1)
        assertThat(embedded.map { it.first }.toSet()).containsExactly("Walden")
        val file = VectorFile.open(vectors.fileFor(id)!!)!!
        assertThat(file.header.embedderSha256).isEqualTo(sha)
        assertThat(file.header.count).isEqualTo(embedded.size)
        file.close()

        val finder = DensePassageFinder(StubLibrary(emptyList()), shelf, FakeShelves(), vectors.sources, pool, dispatcher, k = 1)
        val found = finder.find(floatArrayOf(0f, 0f, 1f, 0f)).single()
        assertThat(found.kind).isEqualTo(SourceKind.BOOK)
        assertThat(found.sourceId).isEqualTo(id)
        assertThat(found.text).contains("I went to the woods")
        assertThat(finder.mismatches.get()).isEqualTo(0)
    }

    @Test fun aBookIsEmbeddedOnceUntilTheEmbedderChanges() = test {
        importWalden()
        var current = sha
        val vectors = vectors(embedder = { current })
        assertThat(vectors.embedPending()).isEqualTo(1)
        assertThat(vectors.embedPending()).isEqualTo(0)
        // Another embedder: the old file isn't listed, and the book is embedded again.
        current = "b".repeat(64)
        assertThat(vectors.sources.current()).isEmpty()
        assertThat(vectors.embedPending()).isEqualTo(1)
        assertThat(vectors.sources.current()).hasSize(1)
    }

    @Test fun withoutAnEmbedderNothingIsWritten() = test {
        val id = importWalden()
        val vectors = vectors(available = false)
        assertThat(vectors.embedPending()).isEqualTo(0)
        assertThat(vectors.fileFor(id)!!.exists()).isFalse()
        assertThat(vectors(embedder = { null }).embedPending()).isEqualTo(0)
    }

    @Test fun onlyBooksYouAddAreEmbedded() = test {
        val epub = TestEpubs.build(File(booksDir, "granger").apply { mkdirs() }, "gc-walden_2026-01-01.epub", title = "Walden",
            chapters = listOf("<p>" + "I went to the woods. ".repeat(40) + "</p>"))
        shelf.addDownloaded(epub, BookOrigin.GRANGER, "gc-walden")!!
        assertThat(vectors().embedPending()).isEqualTo(0)
        assertThat(vectors().fileFor(BookIds.of(BookOrigin.GRANGER, "gc-walden"))).isNull()
    }

    @Test fun aPdfIsEmbeddedByPageAndRebuiltWithItsPage() = test {
        val src = PdfFixtures.text(tmp.newFolder())
        val id = (shelf.import("water.pdf") { src.inputStream() } as ImportResult.Imported).bookId
        val vectors = vectors()
        assertThat(vectors.embedPending()).isEqualTo(1)
        val finder = DensePassageFinder(StubLibrary(emptyList()), shelf, FakeShelves(), vectors.sources, pool, dispatcher, k = 1)
        val found = finder.find(floatArrayOf(1f, 0f, 0f, 0f)).single()
        assertThat(found.sourceId).isEqualTo(id)
        assertThat(found.location).startsWith("page/")
        assertThat(found.heading).startsWith("p. ")
        assertThat(finder.mismatches.get()).isEqualTo(0)
    }
}
