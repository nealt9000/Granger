package app.granger.core.library

import app.granger.core.books.TestEpubs
import app.granger.core.zim.ZimHit
import com.google.common.truth.Truth.assertThat
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ZimLibrarySearchTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = inMemoryDatabase()
    private val opener = FakeZimOpener()
    private val dir by lazy { tmp.newFolder("packs") }
    private val dispatcher = StandardTestDispatcher()
    private val library = RoomLibrary(db, opener, { listOf(dir) }, dispatcher)
    private val search = ZimLibrarySearch(library, io = dispatcher, titlesPerPack = 2, fullTextLimit = 3)

    private val med = FakeZimArchive(
        "u-med", "WikiMed",
        suggestions = listOf(ZimHit("Aspirin", "Aspirin"), ZimHit("Asthma", "Asthma"), ZimHit("Ash", "Ash")),
        results = listOf(ZimHit("Aspirin", "Aspirin", "<b>aspirin</b> is", 40), ZimHit("Fever", "Fever", "s", 90)),
    )
    private val classics = FakeZimArchive(
        "u-cls", "Classics",
        suggestions = listOf(ZimHit("As_You_Like_It", "As You Like It")),
        results = listOf(ZimHit("Hamlet", "Hamlet", "s", 70), ZimHit("Lear", "King Lear", "s", 10)),
    )

    @After fun close() = db.close()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        install("med.zim", med)
        install("classics.zim", classics)
        library.refresh()
        block()
    }

    private fun install(name: String, archive: FakeZimArchive) {
        opener.archives[name] = { archive }
        File(dir, name).writeText(name)
    }

    @Test fun titlesComeFromEveryPackWithPackNames() = test {
        val hits = search.titles("as")
        assertThat(hits.map { it.title }).containsExactly("Aspirin", "Asthma", "As You Like It")
        assertThat(hits.first { it.path == "As_You_Like_It" }.packTitle).isEqualTo("Classics")
        assertThat(hits.first { it.path == "Aspirin" }.packId).isEqualTo("u-med")
    }

    @Test fun fullTextIsMergedByScoreAndLimited() = test {
        val hits = search.fullText("anything")
        assertThat(hits.map { it.path }).containsExactly("Fever", "Hamlet", "Aspirin").inOrder()
        assertThat(hits.last().snippet).isEqualTo("<b>aspirin</b> is")
    }

    @Test fun redirectTitlesPointAtTheirArticleAndAreNotRepeated() = test {
        install("music.zim", FakeZimArchive(
            "u-mus", "Music",
            paths = setOf("Georgia_on_My_Mind"),
            redirects = mapOf("Georgia_On_My_Mind" to "Georgia_on_My_Mind", "Georgia_(song)" to "Georgia_on_My_Mind"),
            suggestions = listOf(
                ZimHit("Georgia_On_My_Mind", "Georgia On My Mind"),
                ZimHit("Georgia_(song)", "Georgia (song)"),
                ZimHit("Georgia_on_My_Mind", "Georgia on My Mind"),
            ),
        ))
        library.refresh()
        val hits = search.titles("georgia").filter { it.packId == "u-mus" }
        assertThat(hits.map { it.path }).containsExactly("Georgia_on_My_Mind")
    }

    @Test fun blankQueriesSearchNothing() = test {
        assertThat(search.titles("  ")).isEmpty()
        assertThat(search.fullText("")).isEmpty()
    }

    @Test fun oneFailingPackDoesNotHideOthers() = test {
        med.failSearch = true
        assertThat(search.titles("as").map { it.packId }).containsExactly("u-cls")
        assertThat(search.fullText("x").map { it.packId }).containsExactly("u-cls", "u-cls")
    }

    @Test fun packsWithoutAFullTextIndexAreSkippedForFullText() = test {
        install("noindex.zim", FakeZimArchive("u-no", "No Index", hasFulltextIndex = false,
            results = listOf(ZimHit("X", "X", "s", 100))))
        library.refresh()
        assertThat(search.fullText("x").map { it.packId }).doesNotContain("u-no")
    }

    @Test fun missingAndDamagedPacksAreSkipped() = test {
        File(dir, "med.zim").delete()
        File(dir, "broken.zim").writeText("garbage")
        library.refresh()
        assertThat(search.titles("as").map { it.packId }).containsExactly("u-cls")
    }

    @Test fun booksDelegatesToIndex() = test {
        val booksDir = tmp.newFolder("books")
        val shelf = RoomBookshelf(db, { listOf(booksDir) }, { File(booksDir, "imports") }, File(tmp.root, "covers"), dispatcher)
        val index = BookIndex(db, dispatcher)
        val withBooks = ZimLibrarySearch(library, index, dispatcher)
        val file = TestEpubs.build(booksDir, "moby_2026-01-01.epub", chapters = listOf("<p>The whale surfaced.</p>"))
        shelf.addDownloaded(file, "moby")
        index.indexPending()

        val hits = withBooks.books("whale")
        assertThat(hits.map { it.bookId }).containsExactly(BookIds.of(BookOrigin.GRANGER, "moby"))
        assertThat(hits).isEqualTo(index.search("whale"))
        assertThat(search.books("whale")).isEmpty()
    }
}
