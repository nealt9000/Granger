package app.granger.core.library

import app.granger.core.books.TestEpubs
import app.granger.core.library.GutenbergFixtures.FixtureBook
import app.granger.core.library.GutenbergFixtures.PF_BOOKS
import app.granger.core.zim.ZimHit
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
class GutenbergBooksTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = inMemoryDatabase()
    private val dispatcher = StandardTestDispatcher()
    private val src by lazy { tmp.newFolder("src") }
    private val booksDir by lazy { File(tmp.root, "books/gutenberg") }
    private val coversDir by lazy { File(tmp.root, "covers") }
    private val archives = HashMap<String, FakeZimArchive>()
    private var clock = 1_000L
    private val books by lazy { GutenbergBooks(db, { archives[it] }, { booksDir }, coversDir, dispatcher, { clock }) }

    @After fun close() = db.close()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    /** Installs [archive] as an OK pack row and returns that row. */
    private suspend fun install(archive: FakeZimArchive, status: PackStatus = PackStatus.OK): Pack {
        archives[archive.uuid] = archive
        return GutenbergFixtures.pack(archive, status).also { db.packDao().upsert(it) }
    }

    private suspend fun rows() = db.bookDao().byOrigin(BookOrigin.GUTENBERG).sortedBy { it.title }

    private fun id(gutenbergId: Int) = BookIds.of(BookOrigin.GUTENBERG, gutenbergId.toString())

    @Test fun parsesTheScrapersListWithQuotesColonsAndEscapes() {
        val js = """var json_data = [["A Middle High German Primer", "Joseph Wright", "110", 22636, "PF"], """ +
            """["An Irishman's Difficulties with the Dutch Language", "J. Irwin (John Irwin) Brown", "110", 43349, "PF"], """ +
            """["Pennsylvania Dutch: A Dialect of South German", "Samuel Stehman Haldeman", "110", 48417, "PF"], """ +
            """["Ährenlese: A German Reader", "Anonymous", "110", 49503, "PF"], """ +
            """["Say \"Hello\"", "", "110", 7, "PF"], ["No id", "X", "110", null, "PF"], "junk"];"""
        assertThat(GutenbergList.parse(js)).containsExactly(
            GutenbergListing("A Middle High German Primer", "Joseph Wright", "22636"),
            GutenbergListing("An Irishman's Difficulties with the Dutch Language", "J. Irwin (John Irwin) Brown", "43349"),
            GutenbergListing("Pennsylvania Dutch: A Dialect of South German", "Samuel Stehman Haldeman", "48417"),
            GutenbergListing("Ährenlese: A German Reader", "Anonymous", "49503"),
            GutenbergListing("Say \"Hello\"", "", "7"),
        ).inOrder()
        assertThat(GutenbergList.parse("var json_data = ;")).isEmpty()
        assertThat(GutenbergList.parse("var json_data = [[\"broken\";")).isEmpty()
    }

    @Test fun theEpubPathIsTheListedTitleElseTheBooksOwnPage() {
        val renamed = FixtureBook("Fathers/Sons: A Study", "Anon", 77, epubName = "Fathers-Sons: A Study")
        val archive = GutenbergFixtures.archive(
            src, PF_BOOKS + renamed,
            suggestions = listOf(ZimHit("Fathers-Sons: A Study_cover.77", "Fathers-Sons: A Study"), ZimHit("Home", "Home")),
        )
        assertThat(GutenbergList.epubPath(archive, GutenbergListing(PF_BOOKS[0].title, "", "43349")))
            .isEqualTo("An Irishman's Difficulties with the Dutch Language.43349.epub")
        assertThat(GutenbergList.epubPath(archive, GutenbergListing(renamed.title, "", "77")))
            .isEqualTo("Fathers-Sons: A Study.77.epub")
        assertThat(GutenbergList.epubPath(archive, GutenbergListing("Not here", "", "5"))).isNull()
        // An HTML page at the EPUB's path isn't an EPUB.
        assertThat(GutenbergList.epubPath(archive, GutenbergListing("Home", "", "1"))).isNull()
    }

    @Test fun coversAreFoundByTheScrapersNames() {
        val archive = GutenbergFixtures.archive(src)
        assertThat(GutenbergList.coverPath(archive, "43349")).isEqualTo("43349_cover.jpg")
        assertThat(GutenbergList.coverPath(archive, "3299")).isEqualTo("3299_3299-cover.png")
        assertThat(GutenbergList.coverPath(archive, "49503")).isNull()
    }

    @Test fun syncListsEveryBookWithoutCopyingAnything() = test {
        val pack = install(GutenbergFixtures.archive(src))
        books.sync(listOf(pack))
        val rows = rows()
        assertThat(rows.map { it.title }).containsExactly(
            "An Irishman's Difficulties with the Dutch Language", "Mr. Honey's Banking Dictionary (English-German)",
            "Pennsylvania Dutch: A Dialect of South German With an Infusion of English",
            "Ährenlese: A German Reader with Practical Exercises",
        ).inOrder()
        val irish = rows.first()
        assertThat(irish.id).isEqualTo("book:gutenberg:43349")
        assertThat(irish.author).isEqualTo("J. Irwin (John Irwin) Brown")
        assertThat(irish.sourceRef).isEqualTo("gutenberg_en_lcc-pf/An Irishman's Difficulties with the Dutch Language.43349.epub")
        assertThat(irish.filePath).isEqualTo(File(booksDir, "43349.epub").absolutePath)
        assertThat(irish.format).isEqualTo(BookFormat.EPUB)
        assertThat(irish.status).isEqualTo(PackStatus.OK)
        assertThat(irish.language).isEqualTo("eng")
        assertThat(irish.sizeBytes).isGreaterThan(0L)
        assertThat(irish.indexedAt).isNull()
        assertThat(irish.onShelf).isTrue()
        assertThat(booksDir.exists()).isFalse()
    }

    @Test fun aBookWhoseEpubIsntInThePackIsntListed() = test {
        val pack = install(GutenbergFixtures.archive(src, PF_BOOKS + FixtureBook("Lost Book", "Nobody", 99, epub = false)))
        books.sync(listOf(pack))
        assertThat(rows().map { it.id }).doesNotContain(id(99))
        assertThat(rows()).hasSize(4)
    }

    @Test fun aSecondSyncKeepsEachRowAsItIs() = test {
        val pack = install(GutenbergFixtures.archive(src))
        books.sync(listOf(pack))
        val first = rows()
        clock = 5_000L
        books.sync(listOf(pack))
        assertThat(rows()).isEqualTo(first)
        assertThat(rows().map { it.addedAt }.distinct()).containsExactly(1_000L)
    }

    @Test fun onlyGutenbergPacksAreRead() = test {
        val other = pack("u-wiki").copy(name = "wikipedia_en_top")
        books.sync(listOf(other))
        assertThat(rows()).isEmpty()
    }

    @Test fun aBookInTwoPacksIsOneRowAndStaysWhileEitherIsInstalled() = test {
        val shared = PF_BOOKS[0]
        val pf = install(GutenbergFixtures.archive(src))
        val all = install(
            GutenbergFixtures.archive(src, listOf(shared, FixtureBook("Other Book", "Someone", 12)), uuid = "u-all",
                name = "gutenberg_en_all", description = "All books"),
        )
        books.sync(listOf(all, pf))
        assertThat(rows()).hasSize(5)
        // Filed under its subject pack, not the everything-pack.
        assertThat(db.bookDao().get(id(shared.id))!!.sourceRef).startsWith("gutenberg_en_lcc-pf/")

        db.packDao().delete(pf.id)
        books.sync(listOf(all))
        assertThat(rows().map { it.id }).containsExactly(id(shared.id), id(12))
        assertThat(db.bookDao().get(id(shared.id))!!.sourceRef).startsWith("gutenberg_en_all/")

        db.packDao().delete(all.id)
        books.sync(emptyList())
        assertThat(rows()).isEmpty()
    }

    @Test fun aRemovedPacksBooksGoWithTheirCopiesButKeepTheirPlaces() = test {
        val pack = install(GutenbergFixtures.archive(src))
        books.sync(listOf(pack))
        val irish = db.bookDao().get(id(43349))!!
        assertThat(books.restore(irish)).isTrue()
        db.bookDao().upsertPosition(BookPosition(irish.id, "{}", 0.4, "Chapter 2", 9L))
        db.bookDao().insertMark(BookAnnotation(0, irish.id, MarkKind.BOOKMARK, "{}", "", "", "", 1L, 1L))

        db.packDao().delete(pack.id)
        books.sync(emptyList())
        assertThat(rows()).isEmpty()
        assertThat(File(irish.filePath).exists()).isFalse()
        assertThat(coversDir.listFiles().orEmpty()).isEmpty()
        assertThat(db.bookDao().position(irish.id)!!.totalProgression).isEqualTo(0.4)

        // Installed again, the book picks up where it was.
        val again = install(GutenbergFixtures.archive(src))
        books.sync(listOf(again))
        assertThat(db.bookDao().get(irish.id)).isNotNull()
        assertThat(db.bookDao().position(irish.id)!!.chapterTitle).isEqualTo("Chapter 2")
    }

    @Test fun aPositionSurvivesANewSnapshot() = test {
        val march = install(GutenbergFixtures.archive(src, uuid = "u-march"))
        books.sync(listOf(march))
        val before = db.bookDao().get(id(48417))!!
        db.bookDao().upsertPosition(BookPosition(before.id, "{\"href\":\"c001.xhtml\"}", 0.25, "One", 9L))

        // The library replaces the old row with the new snapshot's (same ZIM name, new UUID).
        db.packDao().delete(march.id)
        val april = install(GutenbergFixtures.archive(src, uuid = "u-april"))
        books.sync(listOf(april))
        val after = db.bookDao().get(before.id)!!
        assertThat(after.sourceRef).isEqualTo(before.sourceRef)
        assertThat(after.addedAt).isEqualTo(before.addedAt)
        assertThat(db.bookDao().position(before.id)!!.totalProgression).isEqualTo(0.25)
    }

    @Test fun aMissingPackKeepsItsRowsAndItsOpenedBooksStillRead() = test {
        val pack = install(GutenbergFixtures.archive(src))
        books.sync(listOf(pack))
        assertThat(books.restore(db.bookDao().get(id(43349))!!)).isTrue()

        val missing = pack.copy(status = PackStatus.MISSING)
        db.packDao().upsert(missing)
        books.sync(listOf(missing))
        val byId = rows().associateBy { it.id }
        assertThat(byId).hasSize(4)
        assertThat(byId.getValue(id(43349)).status).isEqualTo(PackStatus.OK)
        assertThat(byId.getValue(id(3299)).status).isEqualTo(PackStatus.MISSING)

        db.packDao().upsert(pack)
        books.sync(listOf(pack))
        assertThat(rows().map { it.status }.distinct()).containsExactly(PackStatus.OK)
    }

    @Test fun openingCopiesTheEpubOutOnceWithItsCover() = test {
        val archive = GutenbergFixtures.archive(src)
        val pack = install(archive)
        books.sync(listOf(pack))
        val book = db.bookDao().get(id(43349))!!
        val path = book.sourceRef.substringAfter('/')
        archive.reads.clear()

        assertThat(books.restore(book)).isTrue()
        val file = File(book.filePath)
        assertThat(file.readBytes()).isEqualTo(GutenbergFixtures.epubBytes(src, PF_BOOKS[0]))
        val opened = db.bookDao().get(book.id)!!
        assertThat(opened.sizeBytes).isEqualTo(file.length())
        assertThat(File(opened.coverPath!!).readBytes()).isEqualTo(TestEpubs.COVER_BYTES)
        assertThat(booksDir.list()!!.filter { it.startsWith(".incoming-") }).isEmpty()
        val copies = archive.reads.count { it == path }

        assertThat(books.restore(opened)).isTrue()
        assertThat(archive.reads.count { it == path }).isEqualTo(copies)
        // Nothing else was copied.
        assertThat(booksDir.list()!!.toList()).containsExactly("43349.epub")
    }

    @Test fun restoreIsOnlyForGutenbergBooksWithAReadablePack() = test {
        assertThat(books.restore(book("hamlet"))).isFalse()
        val pack = install(GutenbergFixtures.archive(src))
        books.sync(listOf(pack))
        val book = db.bookDao().get(id(43349))!!
        archives.clear()
        assertThat(books.restore(book)).isFalse()
        assertThat(File(book.filePath).exists()).isFalse()
    }

    @Test fun aTappedEpubLinkFindsItsRowListingThePackFirstIfNeeded() = test {
        val pack = install(GutenbergFixtures.archive(src))
        val found = books.bookFor(pack.id, "Pennsylvania Dutch: A Dialect of South German With an Infusion of English.48417.epub")
        assertThat(found!!.id).isEqualTo(id(48417))
        assertThat(books.bookFor(pack.id, "Home")).isNull()
        assertThat(books.bookFor(pack.id, "Something.123.epub")).isNull()
    }

    @Test fun fiveThousandBooksSyncInUnderTwoSeconds() = test {
        val many = (1..5_000).map { FixtureBook("Book number $it: a title", "Author ${it % 97}", 100_000 + it, cover = null) }
        val epub = GutenbergFixtures.epubBytes(src, many.first())
        val files = HashMap<String, Pair<String, ByteArray>>()
        files[GutenbergList.PATH] = "text/javascript" to GutenbergFixtures.listJs(many).toByteArray()
        for (b in many) files["${b.title}.${b.id}.epub"] = GutenbergList.EPUB_MIME to epub
        val archive = FakeZimArchive("u-big", "Project Gutenberg Library", name = "gutenberg_en_lcc-q",
            description = "Science", files = files)
        val pack = install(archive)
        val started = System.nanoTime()
        books.sync(listOf(pack))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertThat(rows()).hasSize(5_000)
        assertThat(ms).isLessThan(2_000L)
        println("GutenbergBooksTest: 5,000 rows synced in $ms ms")
    }
}
