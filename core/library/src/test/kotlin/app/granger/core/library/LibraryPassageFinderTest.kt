package app.granger.core.library

import app.granger.core.books.TestEpubs
import app.granger.core.research.SourceKind
import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimHit
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
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
        override suspend fun moveAlbum(album: AlbumSummary, toCollectionId: String) = Unit
        override suspend fun moveAudiobook(book: Audiobook, toCollectionId: String) = Unit
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
        bookLimit: Int = 40,
        media: MediaIndex? = null,
    ) = LibraryPassageFinder(Archives(packs, archives), index, StubBookshelf(books), FakeShelves(rows), dispatcher, docs = docs, bookLimit = bookLimit, media = media)

    private val media = MemoryMedia()
    private fun TestScope.mediaIndex() = MediaIndex(media, backgroundScope, dispatcher)

    private fun planets() = listOf("Mars", "Venus", "Jupiter").mapIndexed { i, title ->
        musicTrack("p$i", album = "The Planets", albumArtist = "Gustav Holst", title = title, trackNo = i + 1)
    }

    private fun page(text: String) = "<h2 id=\"s\">Section</h2><p>$text</p>"

    @Test fun holstQuestionFindsTheMediaCard() = test {
        media.trackList.value = planets() + musicTrack("w1", album = "The Wall", albumArtist = "Pink Floyd", title = "Hey You")
        val found = finder(emptyList(), emptyMap(), media = mediaIndex()).find(listOf("holst"))
        val card = found.single { it.kind == SourceKind.MEDIA }
        assertThat(card.sourceId).isEqualTo("album:" + planets().first().albumKey)
        assertThat(card.location).isEmpty()
        assertThat(card.sourceTitle).isEqualTo("The Planets")
        assertThat(card.packTitle).isEqualTo("Your music")
        assertThat(card.text).startsWith("Album: The Planets\nArtist: Gustav Holst")
    }

    @Test fun aSongTitleFocusesItsAlbumsCard() = test {
        media.trackList.value = planets()
        val card = finder(emptyList(), emptyMap(), media = mediaIndex()).find(listOf("holst", "venus")).single { it.kind == SourceKind.MEDIA }
        assertThat(card.location).isEqualTo(planets()[1].id)
        assertThat(card.heading).isEqualTo("Venus")
        assertThat(card.text).startsWith("Song: Venus (2 of 3)\nAlbum: The Planets")
    }

    @Test fun atMostTwoMediaCards() = test {
        media.trackList.value = (1..4).map { musicTrack("m$it", album = "Mars Album $it", albumArtist = "Band $it", title = "Mars") }
        media.bookList.value = listOf(phoneAudiobook("b", "Mars Stories"))
        val found = finder(emptyList(), emptyMap(), media = mediaIndex()).find(listOf("mars", "mars album"))
        assertThat(found.count { it.kind == SourceKind.MEDIA }).isEqualTo(2)
    }

    @Test fun mediaCardsMatchAnyKeywordAsWholeWords() = test {
        media.trackList.value = planets()
        val index = mediaIndex()
        // One keyword's words must all be there; another keyword that doesn't match doesn't stop it.
        assertThat(finder(emptyList(), emptyMap(), media = index).find(listOf("gustav holst", "steam engine")).map { it.kind })
            .containsExactly(SourceKind.MEDIA)
        // Whole words only: "hol" is no prefix match.
        assertThat(finder(emptyList(), emptyMap(), media = index).find(listOf("hol"))).isEmpty()
    }

    @Test fun aKeywordThatMatchesNoWorkIsTriedWordByWord() = test {
        media.trackList.value = planets() + musicTrack("m1", album = "The Dark Side of the Moon", albumArtist = "Pink Floyd", title = "Money")
        val index = mediaIndex()
        // "from" is a stopword; "money" finds the song's album.
        assertThat(finder(emptyList(), emptyMap(), media = index).find(listOf("money from")).map { it.sourceTitle })
            .containsExactly("The Dark Side of the Moon")
        // "pink floyd money" matches as a whole; "holst steam" doesn't, so its words are tried and "holst" finds The Planets.
        assertThat(finder(emptyList(), emptyMap(), media = index).find(listOf("pink floyd money", "holst steam")).map { it.sourceTitle })
            .containsExactly("The Dark Side of the Moon", "The Planets")
        // One word is never split further, and a stopword alone finds nothing.
        assertThat(finder(emptyList(), emptyMap(), media = index).find(listOf("steamboat", "from with"))).isEmpty()
    }

    @Test fun noMediaIndexMeansNoMediaPassages() = test {
        media.trackList.value = planets()
        assertThat(finder(emptyList(), emptyMap()).find(listOf("holst"))).isEmpty()
    }

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

    @Test fun cardMatchingRunsOnTheIoDispatcher() = test {
        // Counts the work handed to it. The article search is one such hop; the card matching (a regex over every
        // book and pack, off the main thread) is another.
        class CountingDispatcher : CoroutineDispatcher() {
            var hops = 0
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                hops++
                dispatcher.dispatch(context, block)
            }
        }
        val io = CountingDispatcher()
        val p = pack("wiki", "Wikipedia", fulltext = false)
        val found = LibraryPassageFinder(Archives(listOf(p), emptyMap()), index, StubBookshelf(emptyList()), FakeShelves(emptyList()), io)
            .find(listOf("wikipedia"))
        assertThat(found.single().kind).isEqualTo(SourceKind.CARD)
        assertThat(io.hops).isAtLeast(2)
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

    @Test fun aPackInTheKiwixFolderCarriesItsOwnCollectionName() = test {
        val p = pack("wiki", "Wikipedia")
        val folder = collection("Kiwix").copy(children = listOf(collection("Wikipedia", listOf(p))))
        val found = finder(listOf(p), emptyMap(), rows = listOf(folder)).find(listOf("wikipedia"))
        assertThat(found.single { it.kind == SourceKind.CARD }.meta.collection).isEqualTo("Wikipedia")
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

    private suspend fun addBook(ref: String, title: String, vararg chapters: String): Book =
        shelf.addDownloaded(
            TestEpubs.build(File(booksDir, "granger").apply { mkdirs() }, "${ref}_2026-01-01.epub", title = title, chapters = chapters.toList()),
            BookOrigin.GRANGER, ref,
        )!!

    @Test fun namedBookIsSearchedWithTheOtherKeywords() = test {
        val kjv = addBook("kjv", "The King James Bible", "<p>For the love of money is the root of all evil.</p>")
        val commentary = addBook("comm", "Commentary on Scripture", *Array(6) { "<p>bible money evil $it</p>" })
        index.indexPending()

        val found = finder(emptyList(), emptyMap(), books = listOf(kjv, commentary), bookLimit = 3)
            .find(listOf("bible", "money", "evil")).filter { it.kind == SourceKind.BOOK }
        assertThat(found.map { it.sourceId }).contains(kjv.id)
        assertThat(found.first { it.sourceId == kjv.id }.text).contains("root of all evil")
        assertThat(found.distinctBy { Triple(it.sourceId, it.location, it.text) }).hasSize(found.size)
    }

    @Test fun quranMatchesKoranTitle() = test {
        val koran = addBook("koran", "The Koran", "<p>charity is a duty</p>", "<p>nothing</p>")
        index.indexPending()

        // bookLimit 0: only the scoped search inside the named book can find anything.
        val found = finder(emptyList(), emptyMap(), books = listOf(koran), bookLimit = 0).find(listOf("quran", "charity", "duty"))
        assertThat(found.filter { it.kind == SourceKind.BOOK }.map { it.text }).containsExactly("charity is a duty")
    }

    @Test fun gitaMatchesBhagavadTitle() = test {
        val gita = addBook("gita", "The Bhagavad Gita", "<p>act without attachment</p>")
        index.indexPending()

        val found = finder(emptyList(), emptyMap(), books = listOf(gita), bookLimit = 0).find(listOf("gita", "attachment"))
        assertThat(found.filter { it.kind == SourceKind.BOOK }.map { it.text }).containsExactly("act without attachment")
    }

    @Test fun aChunkFoundByBothSearchesAppearsOnce() = test {
        val kjv = addBook("kjv", "The King James Bible", "<p>money is the root of evil</p>")
        index.indexPending()

        val found = finder(emptyList(), emptyMap(), books = listOf(kjv)).find(listOf("bible", "money", "evil"))
        assertThat(found.filter { it.kind == SourceKind.BOOK }).hasSize(1)
    }

    @Test fun genericTitleWordsDoNotName() = test {
        val works = addBook("works", "The Complete Works of X", "<p>filler</p>", "<p>zebra stripes</p>")
        index.indexPending()

        // "complete" names nothing, so no scoped search for "zebra" runs and the (limit 0) general search finds nothing.
        val found = finder(emptyList(), emptyMap(), books = listOf(works), bookLimit = 0).find(listOf("complete", "zebra"))
        assertThat(found.filter { it.kind == SourceKind.BOOK }).isEmpty()
        val named = finder(emptyList(), emptyMap(), books = listOf(works), bookLimit = 0).find(listOf("works", "x", "zebra"))
        assertThat(named.filter { it.kind == SourceKind.BOOK }.map { it.text }).containsExactly("zebra stripes")
    }
}
