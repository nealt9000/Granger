package app.granger.core.library

import app.granger.core.books.TestEpubs
import app.granger.core.research.Keywords
import app.granger.core.research.ResearchLanguage
import app.granger.core.research.SourceKind
import app.granger.core.zim.ZimHit
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The library side of the Spanish layer (Plan L4) leaves English alone: every English eval question finds exactly the
 * passages, and gets exactly the citation quotes, it got before, whether the finder and quotes are given English alone
 * (the default) or what [ResearchLanguage.of] says. The shelf has titles with Spanish-looking words in them, so the
 * named-book, card and media stopword checks are tried on English.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryLanguageTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = inMemoryDatabase()
    private val dispatcher = StandardTestDispatcher()
    private val booksDir by lazy { tmp.newFolder("books") }
    private val shelf by lazy {
        RoomBookshelf(db, { listOf(booksDir) }, { File(booksDir, "imports") }, File(tmp.root, "covers"), dispatcher, pdfCovers = { _, _ -> null })
    }
    private val index = BookIndex(db, dispatcher)
    private val media = MemoryMedia()

    @After fun close() = db.close()

    private val english: List<String> by lazy {
        listOf("tune.json", "heldout.json", "media.json").flatMap { name ->
            val items = JSONArray(File("../../tools/eval/dataset/$name").readText())
            (0 until items.length()).map { items.getJSONObject(it) }
        }.flatMap { q ->
            listOf("question", "priorQuestion").mapNotNull { key -> (q.opt(key) as? String)?.ifBlank { null } }
        }.distinct() + AMBIGUOUS
    }

    private class Archives(initial: List<Pack>, val archives: Map<String, FakeZimArchive>) : Library by StubLibrary(initial) {
        override fun archive(packId: String) = archives[packId]
    }

    private suspend fun addBook(ref: String, title: String, vararg chapters: String): Book =
        shelf.addDownloaded(
            TestEpubs.build(File(booksDir, "granger").apply { mkdirs() }, "${ref}_2026-01-01.epub", title = title, chapters = chapters.toList()),
            BookOrigin.GRANGER, ref,
        )!!

    @Test fun englishQuestionsFindAndQuoteTheSame() = runTest(dispatcher) {
        val books = listOf(
            addBook("quixote", "Don Quixote de la Mancha", "<p>The knight rode against the windmills of La Mancha.</p>"),
            addBook("lobos", "Los Lobos: A Band History", "<p>Los Lobos formed in East Los Angeles in 1973.</p>", "<p>Los dos hermanos.</p>"),
            addBook("bible", "The King James Bible", "<p>For the love of money is the root of all evil.</p>"),
            addBook("origin", "On the Origin of Species", "<p>Natural selection acts by the preservation of variations.</p>"),
            addBook("una", "Una Historia del Mar", "<p>The sea has carried trade for as long as there have been ships.</p>"),
            addBook("nino", "El Niño and the Climate", "<p>El Niño warms the eastern Pacific every few years.</p>"),
            addBook("solo", "Solo Flight: Lindbergh Over the Atlantic", "<p>Lindbergh landed in Paris in 1927.</p>"),
            addBook("works", "The Complete Works of William Shakespeare", "<p>To be, or not to be, that is the question.</p>"),
        )
        index.indexPending()
        media.trackList.value = listOf("Mars", "Venus", "Jupiter").mapIndexed { i, t ->
            musicTrack("p$i", album = "The Planets", albumArtist = "Gustav Holst", title = t, trackNo = i + 1)
        } + listOf("Con Amor", "Para Todos", "Money", "Sobre las Olas").mapIndexed { i, t ->
            musicTrack("l$i", album = "Todo Mundo", albumArtist = "Los Lobos", title = t, trackNo = i + 1)
        }
        val pack = Pack("wiki", "/wiki.zim", "Wikipedia", "Wikipedia in English", "eng", "2026-10-15", 1, true, PackStatus.OK, 0)
        val archive = FakeZimArchive(
            "wiki", paths = setOf("Water"), results = listOf(ZimHit("Water", "Water", score = 5)),
            html = mapOf("Water" to "<p>Water boils at 100 degrees. The son of the miller was at the mill con brio.</p>"),
        )
        val mediaIndex = MediaIndex(media, backgroundScope, dispatcher)
        val finder = LibraryPassageFinder(
            Archives(listOf(pack), mapOf("wiki" to archive)), index, StubBookshelf(books), FakeShelves(), dispatcher, media = mediaIndex,
        )
        val works = media.trackList.value.map { it.albumKey }.distinct().mapNotNull { mediaIndex.work("album:$it") }
        assertWithMessage("albums").that(works).hasSize(2)

        val en = Keywords.ENGLISH
        val changed = mutableListOf<String>()
        val kinds = mutableMapOf<Any, Int>()
        for (q in english) {
            val of = ResearchLanguage.of(q)
            fun <T> same(what: String, a: T, b: T) { if (a != b) changed += "$what: $q ($of): $a -> $b" }
            // The plain keywords, and phrases as the rewrite gives them: the whole question (stopwords and all) is one.
            val extracted = Keywords.extract(q, languages = en)
            for (keywords in listOf(extracted, listOf(extracted.joinToString(" ")), listOf(Keywords.normalize(q)))) {
                val plain = finder.find(keywords)
                same("find $keywords", plain, finder.find(keywords, of))
                same("find en $keywords", plain, finder.find(keywords, en))
                plain.forEach { kinds.merge(it.kind, 1, Int::plus) }
                for (w in works) same("bestPart ${w.title} $keywords", MediaCards.bestPart(w, keywords), MediaCards.bestPart(w, keywords, of))
                for (p in plain) {
                    same("quote", PassageAddress.quoteOf(p.copy(language = null), q, 1), PassageAddress.quoteOf(p, q, 1, of))
                    same("cited", PassageAddress.cited(p.copy(language = null), q, 1), PassageAddress.cited(p, q, 1, of))
                }
            }
        }
        // The run reaches every kind of passage the finder makes, not only the pack's article.
        assertWithMessage("questions").that(english.size).isGreaterThan(300)
        assertWithMessage("kinds found: $kinds").that(kinds.keys).containsAtLeast(SourceKind.ARTICLE, SourceKind.BOOK, SourceKind.CARD, SourceKind.MEDIA)
        assertWithMessage(changed.joinToString("\n")).that(changed).isEmpty()
    }

    private companion object {
        /** English questions without English function words, some with Spanish-looking ones (as LanguageLayerTest's). */
        val AMBIGUOUS = listOf(
            "Han dynasty founder", "Lake Como villas", "Victorian era fashion", "hay fever remedies", "solo flight Lindbergh",
            "Los Angeles population", "La Paz altitude", "El Niño effects", "Con Edison founded", "Don Quixote windmills",
            "La Mancha region", "Los Lobos band", "Todo Mundo song", "Para handball", "Una Stubbs", "Sobre las Olas waltz",
            "money root evil bible", "origin species selection", "Los Lobos formed", "Una Historia ships",
        )
    }
}
