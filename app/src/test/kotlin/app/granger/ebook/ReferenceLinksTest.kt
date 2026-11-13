package app.granger.ebook

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.granger.FakeBookOpener
import app.granger.FakeBookshelf
import app.granger.FakeReadingSessions
import app.granger.InstalledSidecar
import app.granger.MainDispatcherRule
import app.granger.UiText
import app.granger.book
import app.granger.core.library.BookMention
import app.granger.core.library.ReadingTab
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
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
class ReferenceLinksTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val bookshelf = FakeBookshelf()
    private val sessions = FakeReadingSessions()
    private val opener = FakeBookOpener()
    private val shared = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("links-test", Context.MODE_PRIVATE)
    private val prefsStore = ReadingPrefsStore(shared)

    @After fun reset() {
        OpenBooks.current = null
        shared.edit().clear().commit()
    }

    private fun mention(href: String, start: Int, quote: String, qid: Long, tier: Int = 1, before: String = "in ") =
        BookMention(href, start, quote, before, qid, tier)

    private val london = mention("c001.xhtml", 3, "London", 84L)
    private val derby = mention("c001.xhtml", 30, "Derby", 23098L, tier = 2)
    private val lost = mention("c009.xhtml", 0, "Paris", 90L)

    private class FakeLinks(val all: List<BookMention>, val peeks: Map<Long, LinkPeek>) : BookLinkSource {
        override suspend fun mentions(bookId: String, level: LinkLevel) = all.filter { it.tier <= level.maxTier }
        override suspend fun peek(qid: Long, language: String?) = peeks[qid]
    }

    private fun TestScope.reader(links: BookLinkSource?): EbookReaderViewModel {
        opener.publication = app.granger.testPublicationWithContent(
            title = "Pride and Prejudice",
            chapters = linkedMapOf("c001.xhtml" to "<p>In London and the county of Derby.</p>"),
        )
        val file = tmp.root.resolve("b.epub").apply { writeText("epub") }
        bookshelf.bookList.value = listOf(book("b", title = "Pride and Prejudice").copy(filePath = file.path))
        sessions.tabs.value = listOf(ReadingTab(1, "b", "", "Pride and Prejudice", 1L, onRail = true))
        return EbookReaderViewModel(1, null, bookshelf, sessions, opener, prefsStore, this, main.dispatcher, FakeBookSearcher(), SavedStateHandle(), links = links)
    }

    /** Readium parses on its own threads: run the scheduler until [until] holds, within a few seconds. */
    private fun TestScope.settle(until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!until() && System.currentTimeMillis() < deadline) { testScheduler.advanceUntilIdle(); Thread.sleep(10) }
        testScheduler.advanceUntilIdle()
    }

    @Test fun lightDrawsTierOneMoreAddsTierTwoOffDrawsNone() = runTest(main.dispatcher) {
        val vm = reader(FakeLinks(listOf(london, derby, lost), emptyMap()))
        settle { vm.state.value.linkDecorations.isNotEmpty() }
        // A chapter this edition doesn't have is left out.
        assertThat(vm.state.value.hasLinks).isTrue()
        assertThat(vm.state.value.linkDecorations.map { it.id }).containsExactly("84@c001.xhtml#3")
        val drawn = vm.state.value.linkDecorations.single()
        assertThat(drawn.locator.text.highlight).isEqualTo("London")
        assertThat(drawn.locator.text.before).isEqualTo("in ")
        assertThat(drawn.style).isEqualTo(ReferenceLinkStyle(BookLink))

        vm.updatePrefs(vm.state.value.prefs.copy(referenceLinks = LinkLevel.MORE))
        settle { vm.state.value.linkDecorations.size == 2 }
        assertThat(vm.state.value.linkDecorations.map { LinkDecorations.qidOf(it.id) }).containsExactly(84L, 23098L)

        vm.updatePrefs(vm.state.value.prefs.copy(referenceLinks = LinkLevel.OFF))
        settle { vm.state.value.linkDecorations.isEmpty() }
        assertThat(vm.state.value.linkDecorations).isEmpty()
        assertThat(vm.state.value.hasLinks).isTrue()     // the setting stays offered, to turn them back on
    }

    @Test fun aBookWithoutLinksOffersNoSetting() = runTest(main.dispatcher) {
        val vm = reader(FakeLinks(emptyList(), emptyMap()))
        settle { vm.state.value.publication != null }
        assertThat(vm.state.value.hasLinks).isFalse()
        assertThat(vm.state.value.linkDecorations).isEmpty()
    }

    @Test fun aTapOpensTheCardOrSaysTheArticleIsGone() = runTest(main.dispatcher) {
        val card = LinkPeek(84L, "top", "London", "London", "London is the capital of England.", null)
        val vm = reader(FakeLinks(listOf(london), mapOf(84L to card)))
        settle { vm.state.value.linkDecorations.isNotEmpty() }
        vm.openLink("84@c001.xhtml#3")
        settle { vm.state.value.peek != null }
        assertThat(vm.state.value.peek).isEqualTo(card)
        vm.closePeek()
        assertThat(vm.state.value.peek).isNull()

        vm.openLink("90@c001.xhtml#9")
        settle { vm.state.value.notice != null }
        assertThat(vm.state.value.notice).isEqualTo(UiText.Res(app.granger.R.string.ebook_link_gone))
        assertThat(vm.state.value.peek).isNull()
        vm.openLink("not-a-link")     // another group's id: nothing happens
    }

    @Test fun contextIsTheSameParagraphsLastWords() {
        assertThat(LinkDecorations.contextOf("LX.\nPRIDE and PREJUDICE\nby ")).isEqualTo("by ")
        assertThat(LinkDecorations.contextOf("x".repeat(50))).hasLength(LinkDecorations.CONTEXT)
        assertThat(LinkDecorations.contextOf("Chapter 1\n")).isNull()
        assertThat(LinkDecorations.qidOf(LinkDecorations.idOf(london))).isEqualTo(84L)
        assertThat(LinkDecorations.qidOf("12")).isEqualTo(12L)       // a highlight's id is a number too, but in its own group
    }

    // ---- the app's source: real .glink and .gqid files ----

    private var made = 0

    private fun sqlite(name: String, kind: String, source: String, build: (SQLiteDatabase) -> Unit): File {
        val f = File(tmp.root, "$name-${made++}.$kind")
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("INSERT INTO meta VALUES ('kind', '$kind'), ('schemaVersion', '1'), ('sourceSha256', '$source')")
            build(db)
        }
        return f
    }

    private val ppMentions = listOf(london, derby, mention("c002.xhtml", 5, "Bath", 22889L), mention("c003.xhtml", 8, "London", 84L, before = "back to "))

    private fun glink(id: String = "gc-pp", work: Long? = 170583L, mentions: List<BookMention> = ppMentions, source: String = "e".repeat(64)) =
        sqlite(id, "glink", source) { db ->
            db.execSQL("CREATE TABLE book(id TEXT NOT NULL, qid INTEGER, year INTEGER)")
            db.execSQL("INSERT INTO book VALUES (?, ?, 1813)", arrayOf<Any?>(id, work))
            db.execSQL("CREATE TABLE mentions(book TEXT, href TEXT, start INTEGER, len INTEGER, quote TEXT, before TEXT, qid INTEGER, tier INTEGER, conf REAL)")
            for (m in mentions) {
                db.execSQL("INSERT INTO mentions VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1.0)", arrayOf<Any>(id, m.href, m.start, m.quote.length, m.quote, m.before, m.qid, m.tier))
            }
        }

    private fun gqid(source: String, vararg articles: Pair<String, Long>, links: List<Triple<Long, String, String>> = emptyList()) = sqlite("wiki", "gqid", source) { db ->
        db.execSQL("CREATE TABLE articles(path TEXT PRIMARY KEY, qid INTEGER NOT NULL, rank INTEGER NOT NULL, other TEXT)")
        db.execSQL("CREATE TABLE links(qid INTEGER, prop TEXT, value TEXT)")
        articles.forEach { (path, qid) -> db.execSQL("INSERT INTO articles VALUES (?, ?, 10, NULL)", arrayOf<Any>(path, qid)) }
        links.forEach { (qid, prop, value) -> db.execSQL("INSERT INTO links VALUES (?, ?, ?)", arrayOf<Any>(qid, prop, value)) }
    }

    private fun shelf(id: String) = LibraryBook(id, "Title of $id", null)

    private fun mentionIndex() = app.granger.core.library.MentionIndex(File(tmp.root, "mentions-${made++}.sqlite"))

    @Test fun theSourceDrawsOnlyLinksAnInstalledPackCanOpenAndPeeksTheBestPackThatReads() = runBlocking<Unit> {
        val top = gqid("t".repeat(64), "London" to 84L)
        val history = gqid("h".repeat(64), "Derby" to 23098L, "London_(city)" to 84L)
        var packs = listOf("top" to InstalledSidecar(top, "t".repeat(64)), "history" to InstalledSidecar(history, "h".repeat(64)))
        val read = mutableListOf<Pair<String, String>>()
        val books = mapOf("b" to InstalledSidecar(glink(), "e".repeat(64)))
        val source = ReferenceLinksSource(
            linksFiles = { books },
            qidFiles = { packs },
            lead = { pack, path -> read += pack to path; if (pack == "top") null else ArticleLead(path, "A city.", null) },
            bookOf = { shelf(it) },
            index = mentionIndex(),
        )
        // Bath has no article on the phone: not drawn. Derby is More only.
        assertThat(source.mentions("b", LinkLevel.LIGHT).map { it.quote }).containsExactly("London", "London")
        assertThat(source.mentions("b", LinkLevel.MORE).map { it.quote }).containsExactly("London", "Derby", "London").inOrder()
        assertThat(source.mentions("b", LinkLevel.OFF)).isEmpty()
        assertThat(source.mentions("other", LinkLevel.MORE)).isEmpty()
        // Top comes first, but its page doesn't read: the card is History's.
        assertThat(source.peek(84L)).isEqualTo(LinkPeek(84L, "history", "London_(city)", "London_(city)", "A city.", null))
        assertThat(read).containsExactly("top" to "London", "history" to "London_(city)").inOrder()
        assertThat(source.peek(22889L)).isNull()
        // A pack removed: the indexes open again without it.
        packs = packs.drop(1)
        assertThat(source.mentions("b", LinkLevel.MORE).map { it.quote }).containsExactly("London", "Derby", "London").inOrder()
        packs = emptyList()
        assertThat(source.mentions("b", LinkLevel.MORE)).isEmpty()
    }

    @Test fun linksBuiltFromAnotherEpubOrAQidIndexFromAnotherPackAreIgnored() = runBlocking<Unit> {
        val top = gqid("t".repeat(64), "London" to 84L)
        val wrongBook = ReferenceLinksSource(
            linksFiles = { mapOf("b" to InstalledSidecar(glink(), "x".repeat(64))) },
            qidFiles = { listOf("top" to InstalledSidecar(top, "t".repeat(64))) },
            lead = { _, p -> ArticleLead(p, "", null) },
            bookOf = { shelf(it) },
            index = mentionIndex(),
        )
        assertThat(wrongBook.mentions("b", LinkLevel.MORE)).isEmpty()
        val wrongPack = ReferenceLinksSource(
            linksFiles = { mapOf("b" to InstalledSidecar(glink(), "e".repeat(64))) },
            qidFiles = { listOf("top" to InstalledSidecar(top, "y".repeat(64))) },
            lead = { _, p -> ArticleLead(p, "", null) },
            bookOf = { shelf(it) },
            index = mentionIndex(),
        )
        assertThat(wrongPack.mentions("b", LinkLevel.MORE)).isEmpty()
    }

    @Test fun anArticlesFooterHasItsBookThePassagesInYourBooksAndTheAuthorsBooks() = runBlocking<Unit> {
        val austen = 36322L
        val pp = 170583L
        val emma = 191047L
        val top = gqid(
            "t".repeat(64), "London" to 84L, "Jane_Austen" to austen, "Pride_and_Prejudice" to pp, "Moon" to 405L,
            links = listOf(Triple(pp, "P50", "Q$austen"), Triple(austen, "P800", "Q$emma")),
        )
        val books = mapOf(
            "book:granger:gc-pp" to InstalledSidecar(glink("gc-pp", pp), "e".repeat(64)),
            "book:granger:gc-emma" to InstalledSidecar(glink("gc-emma", emma, listOf(mention("c001.xhtml", 1, "London", 84L))), "e".repeat(64)),
            "book:granger:gc-x" to InstalledSidecar(glink("gc-x", null, emptyList()), "e".repeat(64)),
        )
        var gone = emptySet<String>()
        val source = ReferenceLinksSource(
            linksFiles = { books },
            qidFiles = { listOf("top" to InstalledSidecar(top, "t".repeat(64))) },
            lead = { _, p -> ArticleLead(p, "", null) },
            bookOf = { id -> shelf(id).takeIf { id !in gone } },
            index = mentionIndex(),
        )
        // London: P&P mentions it twice (first passages first), Emma once.
        val london = source.footer("top", "London")
        assertThat(london.thisWork).isEmpty()
        assertThat(london.byAuthor).isEmpty()
        assertThat(london.inBooks.map { it.book.bookId to it.count }).containsExactly("book:granger:gc-pp" to 2, "book:granger:gc-emma" to 1).inOrder()
        assertThat(london.inBooks.first().passages.map { it.before }).containsExactly("in ", "back to ").inOrder()
        assertThat(london.inBooksTotal).isEqualTo(2)
        // Jane Austen: her books, one through P50 on the work, one through P800 on her.
        val her = source.footer("top", "Jane_Austen")
        assertThat(her.byAuthor.map { it.bookId }).containsExactly("book:granger:gc-emma", "book:granger:gc-pp")
        assertThat(her.inBooks).isEmpty()
        // The work itself: that book, and not again under its author.
        val work = source.footer("top", "Pride_and_Prejudice")
        assertThat(work.thisWork.map { it.bookId }).containsExactly("book:granger:gc-pp")
        assertThat(work.byAuthor).isEmpty()
        assertThat(source.peek(pp)?.book?.bookId).isEqualTo("book:granger:gc-pp")
        assertThat(source.peek(84L)?.book).isNull()
        // Nothing about the Moon, nor about a page without a QID.
        assertThat(source.footer("top", "Moon").isEmpty).isTrue()
        assertThat(source.footer("top", "Nowhere")).isEqualTo(ArticleFooter.NONE)
        // A book gone from the shelves is left out.
        gone = setOf("book:granger:gc-emma")
        assertThat(source.footer("top", "London").inBooks.map { it.book.bookId }).containsExactly("book:granger:gc-pp")
    }

    @Test fun aSubjectInManyBooksListsTheMostMentioningAndCountsTheRest() = runBlocking<Unit> {
        val top = gqid("t".repeat(64), "London" to 84L)
        // Book n mentions London n times.
        val books = (1..10).associate { n ->
            "book:$n" to InstalledSidecar(glink("b$n", null, (1..n).map { mention("c1.xhtml", it, "London", 84L) }), "e".repeat(64))
        }
        val source = ReferenceLinksSource(
            linksFiles = { books },
            qidFiles = { listOf("top" to InstalledSidecar(top, "t".repeat(64))) },
            lead = { _, p -> ArticleLead(p, "", null) },
            bookOf = { shelf(it) },
            index = mentionIndex(),
        )
        val footer = source.footer("top", "London")
        assertThat(footer.inBooks.map { it.count }).containsExactly(10, 9, 8, 7, 6, 5, 4, 3).inOrder()
        assertThat(footer.inBooksTotal).isEqualTo(10)
        assertThat(footer.inBooks.first().passages).hasSize(ReferenceLinksSource.PASSAGES)
    }
}
