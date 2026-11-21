package app.granger.ebook

import app.granger.core.books.TextToEpub
import app.granger.core.books.outputTo
import app.granger.core.library.BookIndex
import app.granger.core.library.BookshelfStore
import app.granger.core.library.ImportResult
import app.granger.core.library.PackLibrary
import app.granger.core.library.PackSearch
import app.granger.core.library.openLibraryDatabase
import app.granger.core.zim.ZimOpenResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plan I5 Task 2: the e-book reader's shared model on the iPhone, over the real shelf (a Room database): a highlight
 * and a note are saved and listed, the text settings are kept and read back through their lambdas, search in the book
 * lists the reader's own marks first, and Search's Books finds a word inside a book through [PackSearch].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EbookReaderIosTest {
    private val root = NSTemporaryDirectory().trimEnd('/') + "/reader-" + NSUUID().UUIDString
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeTest fun setUp() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest fun tearDown() {
        appScope.cancel()
        Dispatchers.resetMain()
    }

    private fun dir(name: String) = "$root/$name".also { SystemFileSystem.createDirectories(Path(it)) }

    private fun epub(): String {
        val path = dir("made") + "/walden.epub"
        val text = "Chapter 1\n\nI went to the woods because I wished to live deliberately.\n\n" +
            "Chapter 2\n\nThe pond was a perfect forest mirror, set round with stones."
        outputTo(path).use { TextToEpub.convert(text, "Walden", false, it, language = "en") }
        return path
    }

    /** A search that finds "pond" once, in chapter 2, as Readium Swift's would answer. */
    private class FakeSearcher : BookSearcher {
        override suspend fun search(query: String, max: Int): List<BookTextHit> =
            if (query == "pond") listOf(BookTextHit("""{"href":"c2.xhtml","type":"application/xhtml+xml"}""", "c2.xhtml", null, "The ", "pond", " was")) else emptyList()
    }

    @Test fun aHighlightAndANoteAreSavedListedAndSearched() = runBlocking {
        val db = openLibraryDatabase(dir("db") + "/library.db")
        val books = dir("books")
        val shelf = BookshelfStore(db, { listOf(books) }, { "$books/imports" }, dir("covers"), now = { 1_000L })
        val source = epub()
        val id = assertIs<ImportResult.Imported>(shelf.importFrom("walden.epub") { SystemFileSystem.source(Path(source)).buffered() }).bookId

        val model = EbookReaderModel(shelf, ReadingPrefsStore(), appScope, Dispatchers.Default, clock = { 5_000L })
        val toc = listOf(TocRow("Chapter 1", "c1.xhtml", 0), TocRow("Chapter 2", "c2.xhtml", 0))
        model.openBook(id, FakeSearcher(), toc)

        val first = model.highlight("""{"href":"c1.xhtml","type":"application/xhtml+xml","title":"Chapter 1"}""", "live deliberately", "green")
        assertTrue(first > 0)
        val noted = model.note("""{"href":"c2.xhtml","type":"application/xhtml+xml"}""", "forest mirror")
        assertEquals(noted, model.reader.value.editingHighlight)
        assertTrue(model.reader.value.focusNote)
        val marks = withTimeout(5_000) { model.reader.first { it.highlights.size == 2 } }.highlights
        assertEquals(setOf("live deliberately", "forest mirror"), marks.map { it.text }.toSet())
        assertEquals("green", marks.single { it.id == first }.color)

        // The note's words, then a colour, saved on the sheet.
        val mark = marks.single { it.id == noted }
        model.updateHighlight(mark.copy(note = "Thoreau's pond", color = "blue"))
        val updated = withTimeout(5_000) { model.reader.first { s -> s.highlights.any { it.note == "Thoreau's pond" } } }
        assertEquals("blue", updated.highlights.single { it.id == noted }.color)

        // Search in the book: the reader's own note first, then the text hit, its chapter from the contents.
        model.search("pond")
        val found = withTimeout(5_000) { model.reader.first { !it.search.running && it.search.results.isNotEmpty() } }.search.results
        assertEquals(listOf(noted, null), found.map { it.markId })
        assertEquals("ebook_your_note", (found[0].chapter as app.granger.UiText.Res).key)
        assertEquals(app.granger.UiText.Raw("Chapter 2"), found[1].chapter)
        assertEquals("pond", found[1].match)

        // A bookmark toggles on and off.
        model.toggleBookmark("""{"href":"c1.xhtml","type":"application/xhtml+xml"}""", { "Chapter 1" }) { it.contains("c1.xhtml") }
        withTimeout(5_000) { model.reader.first { it.bookmarks.size == 1 } }
        model.toggleBookmark("""{"href":"c1.xhtml","type":"application/xhtml+xml"}""", { "Chapter 1" }) { it.contains("c1.xhtml") }
        withTimeout(5_000) { model.reader.first { it.bookmarks.isEmpty() } }

        model.deleteHighlight(noted)
        assertNull(model.reader.value.editingHighlight)
        withTimeout(5_000) { model.reader.first { it.highlights.size == 1 } }

        // Search's Books: the shared pack search finds the word inside the book through the book index.
        val index = BookIndex(db)
        index.indexPending()
        val library = PackLibrary(db, { ZimOpenResult.Failed("none") }, { listOf(dir("packs")) }, now = { 7L })
        val hits = PackSearch(library, bookIndex = index).books("deliberately")
        assertEquals(listOf(id), hits.map { it.bookId }.distinct())
        assertTrue(PackSearch(library).books("deliberately").isEmpty())
        db.close()
    }

    @Test fun readingPrefsAreKeptAndReadBack() {
        val kept = mutableMapOf<String, Any>()
        val store = ReadingPrefsStore({ kept[it] }, { kept.putAll(it) })
        assertEquals(ReadingPrefs(), store.prefs.value)
        store.update(ReadingPrefs(typeface = Typeface.SANS, fontScale = 1.3, theme = ReadingTheme.SEPIA, pdfScroll = true, wikiTextZoom = 123, referenceLinks = LinkLevel.MORE))
        assertEquals(true, kept["pdf_scroll"])
        assertEquals(120, kept["wiki_text_zoom"])
        val again = ReadingPrefsStore({ kept[it] }, { kept.putAll(it) }).prefs.value
        assertEquals(Typeface.SANS, again.typeface)
        assertEquals(1.25, again.fontScale)
        assertEquals(ReadingTheme.SEPIA, again.theme)
        assertTrue(again.pdfScroll)
        assertEquals(120, again.wikiTextZoom)
        assertEquals(LinkLevel.MORE, again.referenceLinks)
    }

    @Test fun contentsPlaceAndDictionaryWordsWorkOnTheIPhone() {
        val html = "<html><head><style>p { color: red }</style></head><body><h2 id=\"a\">AAAA</h2><p>aaaa</p>" +
            "<a name=\"b\"></a><p>bbbbbbbb</p><div id='c'>cccc</div></body></html>"
        val starts = TocPlace.anchorStarts(html, setOf("a", "b", "c", "missing"))
        assertEquals(mapOf("a" to 0.0, "b" to 8.0 / 20, "c" to 16.0 / 20), starts)
        val toc = listOf(TocRow("A", "f.xhtml#a", 0), TocRow("B", "f.xhtml#b", 0), TocRow("C", "f.xhtml#c", 0))
        assertEquals(1, TocPlace.index(toc, "f.xhtml", 0.5, mapOf("f.xhtml" to starts)))
        assertEquals(2, pdfTocIndex(listOf(TocRow("x", "d.pdf#page=1", 0), TocRow("y", "d.pdf#page=3", 0), TocRow("z", "d.pdf#page=5", 0)), 6))

        assertEquals("Spanish", Dictionary.anchor("es", "en"))
        assertEquals("Español", Dictionary.anchor("es", "es"))
        assertEquals("brahman", Dictionary.normalise("  “Brahman,”  "))
        assertEquals(42L, LinkMentions.qidOf("42@c1.xhtml#7"))
    }
}
