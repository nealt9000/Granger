package app.granger.core.library

import app.granger.core.books.TestEpubs
import app.granger.core.research.SourceKind
import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimHit
import com.google.common.truth.Truth.assertThat
import java.io.File
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
class LibraryPassageFinderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = inMemoryDatabase()
    private val dispatcher = StandardTestDispatcher()
    private val booksDir by lazy { tmp.newFolder("books") }
    private val shelf by lazy {
        RoomBookshelf(db, { listOf(booksDir) }, { File(booksDir, "imports") }, File(tmp.root, "covers"), dispatcher)
    }
    private val index = BookIndex(db, dispatcher)

    @After fun close() = db.close()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun pack(id: String, title: String = id, fulltext: Boolean = true, status: PackStatus = PackStatus.OK) =
        Pack(id, "/$id.zim", title, "About $title", "eng", "2026-10-15", 1, fulltext, status, 0)

    private class Archives(initial: List<Pack>, val archives: Map<String, ZimArchive>) : Library by StubLibrary(initial) {
        override fun archive(packId: String): ZimArchive? = archives[packId]
    }

    private class FakeShelves(val rows: List<CollectionRow>) : Shelves {
        override val shelves: Flow<List<Shelf>> =
            MutableStateFlow(listOf(Shelf(MediaType("m", "Media", true, 0), rows)))
        override fun collection(id: String): Flow<CollectionRow?> = MutableStateFlow(rows.firstOrNull { it.collection.id == id })
        override suspend fun movePack(pack: Pack, toCollectionId: String) = Unit
        override suspend fun moveBook(book: Book, toCollectionId: String) = Unit
        override suspend fun moveCollection(collectionId: String, toMediaTypeId: String) = Unit
        override suspend fun createMediaType(name: String) = ""
        override suspend fun deleteMediaType(id: String) = true
        override suspend fun createCollection(name: String, mediaTypeId: String) = ""
        override suspend fun renameCollection(id: String, name: String) = Unit
        override suspend fun deleteCollection(id: String) = Unit
    }

    private fun collection(name: String, packs: List<Pack> = emptyList(), books: List<Book> = emptyList()) =
        CollectionRow(ShelfCollection("c:$name", name, "m", false, name), packs, books)

    private fun finder(
        packs: List<Pack>,
        archives: Map<String, ZimArchive>,
        rows: List<CollectionRow> = emptyList(),
        books: List<Book> = emptyList(),
        docs: Int = 8,
    ) = LibraryPassageFinder(Archives(packs, archives), index, StubBookshelf(books), FakeShelves(rows), dispatcher, docs = docs)

    private fun page(text: String) = "<h2 id=\"s\">Section</h2><p>$text</p>"

    @Test fun articlePassagesCarryPackAnchorAndCollection() = test {
        val p = pack("wiki", "Wikipedia")
        val archive = FakeZimArchive(
            "wiki", paths = setOf("Boiler"), redirects = mapOf("boiler" to "Boiler"),
            results = listOf(ZimHit("boiler", "Boiler", score = 7)),
            html = mapOf("Boiler" to page("A boiler makes steam.")),
        )
        val found = finder(listOf(p), mapOf("wiki" to archive), rows = listOf(collection("Wikipedia", listOf(p)))).find(listOf("boiler"))

        val a = found.single { it.kind == SourceKind.ARTICLE }
        assertThat(a.sourceId).isEqualTo("wiki")
        assertThat(a.sourceTitle).isEqualTo("Boiler")
        assertThat(a.packTitle).isEqualTo("Wikipedia")
        assertThat(a.location).isEqualTo("Boiler")
        assertThat(a.anchor).isEqualTo("s")
        assertThat(a.text).contains("A boiler makes steam.")
        assertThat(a.searchScore).isEqualTo(7.0)
        assertThat(a.meta.date).isEqualTo("2026-10-15")
        assertThat(a.meta.collection).isEqualTo("Wikipedia")
    }

    @Test fun packWithoutFulltextIndexIsSkipped() = test {
        val archive = FakeZimArchive("a", hasFulltextIndex = false, results = listOf(ZimHit("index", "X")))
        val found = finder(listOf(pack("a", fulltext = false)), mapOf("a" to archive)).find(listOf("x"))
        assertThat(archive.searches).isEqualTo(0)
        assertThat(found.filter { it.kind == SourceKind.ARTICLE }).isEmpty()
    }

    @Test fun missingPackIsSkipped() = test {
        val archive = FakeZimArchive("a", results = listOf(ZimHit("index", "X")))
        val found = finder(listOf(pack("a", status = PackStatus.MISSING)), mapOf("a" to archive)).find(listOf("x"))
        assertThat(archive.searches).isEqualTo(0)
        assertThat(found).isEmpty()
    }

    @Test fun keepsTheTopDocsOverall() = test {
        fun archive(id: String, base: Int): FakeZimArchive {
            val names = (0 until 8).map { "$id$it" }
            return FakeZimArchive(
                id, paths = names.toSet(),
                results = names.mapIndexed { i, n -> ZimHit(n, n, score = i * 2 + base) },
                html = names.associateWith { page("text of $it") },
            )
        }
        val found = finder(
            listOf(pack("a"), pack("b")), mapOf("a" to archive("a", 0), "b" to archive("b", 1)), docs = 8,
        ).find(listOf("text"))

        // Scores: a_i = 2i, b_i = 2i + 1, so the best eight interleave the two packs.
        assertThat(found.map { it.sourceTitle }).containsExactly("b7", "a7", "b6", "a6", "b5", "a5", "b4", "a4")
    }

    @Test fun bookCardForAnAuthorQuery() = test {
        val file = TestEpubs.build(File(booksDir, "granger").apply { mkdirs() }, "origin_2026-01-01.epub", title = "Origin", chapters = listOf("<p>x</p>"))
        val book = shelf.addDownloaded(file, BookOrigin.GRANGER, "origin")!!.copy(author = "Charles Darwin")
        val found = finder(
            emptyList(), emptyMap(), rows = listOf(collection("Granger Classics", books = listOf(book))), books = listOf(book),
        ).find(listOf("darwin"))

        val card = found.single { it.kind == SourceKind.CARD }
        assertThat(card.text).contains("Author: Charles Darwin")
        assertThat(card.text).contains("Collection: Granger Classics")
        assertThat(card.text).contains("In your library as: e-book")
        assertThat(card.sourceId).isEqualTo(book.id)
    }

    @Test fun eachKeywordIsSearchedOnItsOwnAndHitsMerge() = test {
        val archive = FakeZimArchive(
            "wiki", paths = setOf("Boiler"), results = listOf(ZimHit("Boiler", "Boiler", score = 3)),
            html = mapOf("Boiler" to page("A boiler makes steam.")),
        )
        val found = finder(listOf(pack("wiki")), mapOf("wiki" to archive)).find(listOf("boiler pressure", "steam"))

        assertThat(archive.queries).containsExactly("boiler pressure", "steam")
        assertThat(found.filter { it.kind == SourceKind.ARTICLE }).hasSize(1)
    }

    @Test fun bookCardWhenAPhrasesWordsAreSpreadOverItsFields() = test {
        val file = TestEpubs.build(File(booksDir, "granger").apply { mkdirs() }, "origin_2026-01-01.epub", title = "Origin", chapters = listOf("<p>x</p>"))
        val book = shelf.addDownloaded(file, BookOrigin.GRANGER, "origin")!!.copy(author = "Charles Darwin")
        val found = finder(emptyList(), emptyMap(), books = listOf(book)).find(listOf("darwin origin", "darwin beagle"))

        assertThat(found.filter { it.kind == SourceKind.CARD }.map { it.sourceId }).containsExactly(book.id)
    }

    @Test fun packCardOmitsBlankValues() = test {
        val p = pack("wiki", "Wikipedia").copy(description = "")
        val found = finder(listOf(p), emptyMap(), rows = listOf(collection("Wikipedia", listOf(p)))).find(listOf("wikipedia"))
        val card = found.single { it.kind == SourceKind.CARD }
        assertThat(card.text.lines()).containsExactly(
            "Title: Wikipedia", "Date: 2026-10-15", "Collection: Wikipedia", "In your library as: web pack",
        ).inOrder()
    }

    @Test fun bookChunksAreFound() = test {
        val file = TestEpubs.build(File(booksDir, "granger").apply { mkdirs() }, "moby_2026-01-01.epub", title = "Moby", chapters = listOf("<p>The whale surfaced.</p>"))
        val book = shelf.addDownloaded(file, BookOrigin.GRANGER, "moby")!!
        index.indexPending()
        val found = finder(emptyList(), emptyMap(), rows = listOf(collection("Classics", books = listOf(book))), books = listOf(book)).find(listOf("whale"))

        val p = found.single { it.kind == SourceKind.BOOK }
        assertThat(p.sourceId).isEqualTo(book.id)
        assertThat(p.location).isEqualTo("OEBPS/c001.xhtml")
        assertThat(p.text).contains("whale")
        assertThat(p.meta.collection).isEqualTo("Classics")
    }

    @Test fun throwingArchiveDoesNotSinkTheRest() = test {
        val bad = FakeZimArchive("bad", failSearch = true)
        val good = FakeZimArchive("good", paths = setOf("p"), results = listOf(ZimHit("p", "P", score = 1)), html = mapOf("p" to page("hello text")))
        val found = finder(listOf(pack("bad"), pack("good")), mapOf("bad" to bad, "good" to good)).find(listOf("hello"))
        assertThat(found.filter { it.kind == SourceKind.ARTICLE }.map { it.sourceId }).containsExactly("good")
    }

    @Test fun noKeywordsFindsNothing() = test {
        val archive = FakeZimArchive("a", results = listOf(ZimHit("index", "X")))
        val found = finder(listOf(pack("a")), mapOf("a" to archive)).find(emptyList())
        assertThat(found).isEmpty()
        assertThat(archive.searches).isEqualTo(0)
    }
}
