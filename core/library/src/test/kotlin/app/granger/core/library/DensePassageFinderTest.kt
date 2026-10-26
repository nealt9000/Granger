package app.granger.core.library

import app.granger.core.books.TestEpubs
import app.granger.core.books.XhtmlText
import app.granger.core.research.HtmlPassages
import app.granger.core.research.SourceKind
import app.granger.core.text.Chunkers
import app.granger.core.vectors.ChunkKey
import app.granger.core.vectors.VectorFile
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
class DensePassageFinderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = inMemoryDatabase()
    private val dispatcher = StandardTestDispatcher()
    private val pool = Executors.newFixedThreadPool(2)
    private val booksDir by lazy { tmp.newFolder("books") }
    private val shelf by lazy {
        RoomBookshelf(db, { listOf(booksDir) }, { File(booksDir, "imports") }, File(tmp.root, "covers"), dispatcher)
    }

    @After fun close() {
        db.close()
        pool.shutdown()
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private val knot = "<html><head><title>Reef knot</title></head><body><h1 id=\"top\">Reef knot</h1><p>" +
        "The reef knot binds two ropes. ".repeat(10) + "</p><h2 id=\"Uses\">Uses</h2><p>" + "Sailors reef sails with it. ".repeat(10) + "</p></body></html>"
    private val bend = "<html><head><title>Sheet bend</title></head><body><p>" + "A bend joins ropes of unequal size. ".repeat(10) + "</p></body></html>"

    private fun pack(name: String) = Pack("uuid-$name", "/$name.zim", "Knots", "", "eng", "2026-07-20", 1, true, PackStatus.OK, 0, name = name)

    private class Archives(packs: List<Pack>, val archives: Map<String, FakeZimArchive>) : Library by StubLibrary(packs) {
        override fun archive(packId: String) = archives[packId]
    }

    private fun unit(vararg v: Float) = v

    private fun vectors(name: String, rows: List<Triple<FloatArray, ChunkKey, String>>): VectorFile {
        val file = File(tmp.root, "$name.gvec")
        VectorFile.write(file, rows.map { it.first }, 4, rows.map { it.third }, rows.map { it.second }, Chunkers.VERSION, "0".repeat(64), "0".repeat(64))
        return VectorFile.open(file)!!
    }

    private fun knotsFinder(rows: List<Triple<FloatArray, ChunkKey, String>>, packName: String = "wikipedia_en_knots"): DensePassageFinder {
        val p = pack("wikipedia_en_knots")
        val archive = FakeZimArchive("uuid-wikipedia_en_knots", paths = setOf("Reef_knot", "Sheet_bend"), html = mapOf("Reef_knot" to knot, "Sheet_bend" to bend))
        val source = VectorSource(vectors("knots", rows), SourceKind.ARTICLE, packName)
        return DensePassageFinder(Archives(listOf(p), mapOf(p.id to archive)), StubBookshelf(), FakeShelves(), { listOf(source) }, pool, dispatcher, k = 2)
    }

    private val reefUses = HtmlPassages.split(knot)[1]

    @Test fun articleHitsBecomePassagesBestFirst() = test {
        val finder = knotsFinder(
            listOf(
                Triple(unit(1f, 0f, 0f, 0f), ChunkKey("Reef_knot", 1), Chunkers.hash(reefUses.text)),
                Triple(unit(0f, 1f, 0f, 0f), ChunkKey("Sheet_bend", 0), Chunkers.hash(HtmlPassages.split(bend)[0].text)),
            ),
        )
        val found = finder.find(floatArrayOf(0.9f, 0.1f, 0f, 0f))
        assertThat(found.map { it.location }).containsExactly("Reef_knot", "Sheet_bend").inOrder()
        val reef = found.first()
        assertThat(reef.kind).isEqualTo(SourceKind.ARTICLE)
        assertThat(reef.sourceId).isEqualTo("uuid-wikipedia_en_knots")
        assertThat(reef.sourceTitle).isEqualTo("Reef knot")
        assertThat(reef.heading).isEqualTo("Uses")
        assertThat(reef.anchor).isEqualTo("Uses")
        assertThat(reef.text).isEqualTo(reefUses.text)
        assertThat(reef.cosine!!).isWithin(0.05).of(0.99)
        assertThat(finder.lastTimings!!.let { it.rows to it.files }).isEqualTo(2L to 1)
    }

    @Test fun aStaleChunkIsDroppedAndCounted() = test {
        val finder = knotsFinder(listOf(Triple(unit(1f, 0f, 0f, 0f), ChunkKey("Reef_knot", 1), "0123456789abcdef")))
        assertThat(finder.find(floatArrayOf(1f, 0f, 0f, 0f))).isEmpty()
        assertThat(finder.mismatches.get()).isEqualTo(1)
    }

    @Test fun vectorsForAPackThatIsNotInstalledAreIgnored() = test {
        val finder = knotsFinder(listOf(Triple(unit(1f, 0f, 0f, 0f), ChunkKey("Reef_knot", 1), Chunkers.hash(reefUses.text))), packName = "wikipedia_en_top")
        assertThat(finder.find(floatArrayOf(1f, 0f, 0f, 0f))).isEmpty()
    }

    @Test fun bookHitsBecomeBookPassages() = test {
        val epub = TestEpubs.build(File(booksDir, "granger").apply { mkdirs() }, "gc-walden_2026-01-01.epub", title = "Walden",
            chapters = listOf("<p>" + "I went to the woods because I wished to live deliberately. ".repeat(12) + "</p>"))
        val book = shelf.addDownloaded(epub, BookOrigin.GRANGER, "gc-walden")!!
        val chunk = ZipFile(epub).use { XhtmlText.chunks(it, "OEBPS/c001.xhtml") }.first()
        val source = VectorSource(vectors("walden", listOf(Triple(unit(0f, 0f, 1f, 0f), ChunkKey("OEBPS/c001.xhtml", 0), Chunkers.hash(chunk.text)))), SourceKind.BOOK, book.id)
        val finder = DensePassageFinder(StubLibrary(emptyList()), StubBookshelf(listOf(book)), FakeShelves(), { listOf(source) }, pool, dispatcher)
        val found = finder.find(floatArrayOf(0f, 0f, 1f, 0f)).single()
        assertThat(found.kind).isEqualTo(SourceKind.BOOK)
        assertThat(found.sourceId).isEqualTo(book.id)
        assertThat(found.sourceTitle).isEqualTo("Walden")
        assertThat(found.location).isEqualTo("OEBPS/c001.xhtml")
        assertThat(found.text).isEqualTo(chunk.text)
    }
}
