package app.granger.core.library

import app.granger.core.books.PdfFixtures
import app.granger.core.books.TestEpubs
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.first
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

/** Plan KB: nautilus packs' documents as book rows, from the real `database.js` of Kiwix's 9 packs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NautilusBooksTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = inMemoryDatabase()
    private val dispatcher = StandardTestDispatcher()
    private val src by lazy { tmp.newFolder("src") }
    private val booksRoot by lazy { File(tmp.root, "books") }
    private val coversDir by lazy { File(tmp.root, "covers") }
    private val archives = HashMap<String, FakeZimArchive>()
    private val cover = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    private val books by lazy {
        NautilusBooks(db, { archives[it] }, { File(booksRoot, "nautilus") }, coversDir, dispatcher, { 1_000L }, pdfCovers = { _, _ -> cover })
    }
    private val shelf by lazy {
        RoomBookshelf(db, { listOf(booksRoot) }, { File(booksRoot, "imports") }, coversDir, dispatcher, { 1_000L }, pdfCovers = { _, _ -> cover })
    }

    @Before fun pdfBox() = PdfFixtures.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())

    @After fun close() = db.close()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun fixture(name: String): String =
        javaClass.getResource("/nautilus/$name.database.js")!!.readText(Charsets.UTF_8)

    private val packNames = listOf(
        "prunelle_en_budding-authors", "prunelle_en_draw-your-african-story", "prunelle_en_interactive-books",
        "usda-2015_en", "zimgit-food-preparation_en", "zimgit-knots_en", "zimgit-medicine_en", "zimgit-post-disaster_en",
        "zimgit-water_en",
    )

    @Test fun everyRealDatabaseReadsAsRecords() {
        val records = packNames.associateWith { NautilusDatabase.records(fixture(it)) }
        assertThat(records.mapValues { it.value.size }).containsExactly(
            "prunelle_en_budding-authors", 19, "prunelle_en_draw-your-african-story", 20,
            "prunelle_en_interactive-books", 11, "usda-2015_en", 8, "zimgit-food-preparation_en", 17,
            "zimgit-knots_en", 11, "zimgit-medicine_en", 12, "zimgit-post-disaster_en", 108, "zimgit-water_en", 7,
        )
        val water = records.getValue("zimgit-water_en").first()
        assertThat(water).isEqualTo(
            NautilusRecord("00000", "Distillation For Home Water Treatment", "For people with a water quality problem",
                "Michigan State University", listOf("Water (1).pdf"), 0),
        )
        assertThat(records.values.flatten().map { it.order }.take(3)).containsExactly(0, 1, 2).inOrder()
        // A folder and curly quotes in the path; "-" for no author.
        val hunter = records.getValue("prunelle_en_draw-your-african-story").first()
        assertThat(hunter.files).containsExactly("Anglais (20 titres)/’Mvoo′ Laa′na the hunter.pdf")
        assertThat(hunter.author).isEmpty()
        // A title with an apostrophe, written in double quotes.
        assertThat(records.getValue("prunelle_en_interactive-books").map { it.title })
            .contains("Miyou the bee-Miyou l'abeille")
        // The old map still reads, for pack documents outside a listed pack's rows.
        assertThat(NautilusDatabase.parse(fixture("zimgit-water_en"))["Water (2).pdf"]).isEqualTo("Purifying Water During an Emergency")
    }

    @Test fun aRecordWithSeveralFilesGivesARowEachCountedInItsTitle() = test {
        val js = """var DATABASE = [
            {'_id': '00000', 'ti': 'Field Manual', 'dsc': 'Three parts', 'aut': 'Army', 'fp': ['fm-1.pdf', 'fm-2.pdf', 'fm-3.pdf']},
            {'_id': '00001', 'ti': 'Notes', 'dsc': '', 'aut': '-', 'fp': ['notes.txt']},
            ];"""
        val pack = install("u-fm", "zimgit-manuals_en", js, listOf("fm-1.pdf", "fm-2.pdf", "fm-3.pdf", "notes.txt"))
        books.sync(listOf(pack))
        val rows = db.bookDao().byOrigin(BookOrigin.NAUTILUS).sortedBy { it.title }
        assertThat(rows.map { it.title }).containsExactly("Field Manual (1 of 3)", "Field Manual (2 of 3)", "Field Manual (3 of 3)").inOrder()
        assertThat(rows.map { it.id }).contains("book:nautilus:zimgit-manuals_en/fm-2.pdf")
        assertThat(rows.first().author).isEqualTo("Army")
        assertThat(rows.first().description).isEqualTo("Three parts")
        // A file the reader can't open isn't listed.
        assertThat(rows.map { it.sourceRef }).doesNotContain("zimgit-manuals_en/files/notes.txt")
    }

    @Test fun thePacksDocumentsAreListedFromItsRealDatabase() = test {
        val pack = install("u-food", "zimgit-food-preparation_en", fixture("zimgit-food-preparation_en"))
        books.sync(listOf(pack))
        val rows = db.bookDao().byOrigin(BookOrigin.NAUTILUS)
        assertThat(rows).hasSize(17)
        val first = rows.single { it.id == "book:nautilus:zimgit-food-preparation_en/Food Preparation (1).pdf" }
        assertThat(first.title).isEqualTo("Philmont Trail Cookbook")
        assertThat(first.author).isEqualTo("Various")
        assertThat(first.sourceRef).isEqualTo("zimgit-food-preparation_en/files/Food Preparation (1).pdf")
        assertThat(first.format).isEqualTo(BookFormat.PDF)
        assertThat(first.filePath).endsWith(".pdf")
        assertThat(File(first.filePath).parentFile).isEqualTo(File(booksRoot, "nautilus"))
        assertThat(File(first.filePath).exists()).isFalse()
        assertThat(first.onShelf).isTrue()
        assertThat(Filing.defaultKey(first)).isEqualTo("kiwixbooks-zimgit-food-preparation_en")
    }

    @Test fun prunellesEpubsAreEpubRowsAndOpenAsEpubs() = test {
        val js = fixture("prunelle_en_interactive-books")
        val records = NautilusDatabase.records(js)
        val epub = TestEpubs.build(tmp.newFolder("epub"), name = "toy.epub", title = "A Toy War", authors = listOf("Blandine Carsalade"),
            cover = TestEpubs.CoverStyle.EPUB3_PROPERTY)
        val files = records.flatMap { it.files }.associate { fp ->
            "${NautilusDatabase.FILES}$fp" to ("application/epub+zip" to epub.readBytes())
        }
        val archive = FakeZimArchive("u-pru", "Prunelle books", name = "prunelle_en_interactive-books", scraper = "nautiluszim 1.1.1",
            files = files + (NautilusDatabase.PATH to ("text/javascript" to js.toByteArray())))
        archives[archive.uuid] = archive
        val pack = pack(archive).also { db.packDao().upsert(it) }
        books.sync(listOf(pack))
        val rows = db.bookDao().byOrigin(BookOrigin.NAUTILUS)
        assertThat(rows).hasSize(11)
        assertThat(rows.map { it.format }.distinct()).containsExactly(BookFormat.EPUB)
        val toy = rows.single { it.title == "A toy war" }
        assertThat(toy.filePath).endsWith(".epub")
        assertThat(books.restore(toy)).isTrue()
        val opened = db.bookDao().get(toy.id)!!
        assertThat(File(opened.filePath).isFile).isTrue()
        assertThat(opened.coverPath).isNotNull()
        assertThat(opened.indexedAt).isNull()
    }

    @Test fun openingAPdfCopiesItOutWithItsFirstPageAsTheCover() = test {
        val pack = install("u-w", "zimgit-water_en", fixture("zimgit-water_en"))
        books.sync(listOf(pack))
        val row = db.bookDao().get("book:nautilus:zimgit-water_en/Water (1).pdf")!!
        assertThat(books.restore(row)).isTrue()
        val opened = db.bookDao().get(row.id)!!
        assertThat(File(opened.filePath).isFile).isTrue()
        assertThat(File(opened.coverPath!!).readBytes()).isEqualTo(cover)
        assertThat(opened.title).isEqualTo("Distillation For Home Water Treatment")
    }

    @Test fun aScannedPdfIsntCountedSearchable() = test {
        val pack = install("u-w", "zimgit-water_en", fixture("zimgit-water_en"), scanned = setOf("Water (2).pdf"))
        books.sync(listOf(pack))
        val text = db.bookDao().get("book:nautilus:zimgit-water_en/Water (1).pdf")!!
        val scan = db.bookDao().get("book:nautilus:zimgit-water_en/Water (2).pdf")!!
        assertThat(books.restore(text)).isTrue()
        assertThat(books.restore(scan)).isTrue()
        BookIndex(db, dispatcher, now = { 5L }).indexPending()
        assertThat(db.bookDao().get(text.id)!!.let { it.indexedAt != null && it.searchable }).isTrue()
        val indexedScan = db.bookDao().get(scan.id)!!
        assertThat(indexedScan.indexedAt).isEqualTo(5L)
        assertThat(indexedScan.searchable).isFalse()
    }

    @Test fun aLinkOnThePacksPageOpensItsRowWhateverTheFileType() = test {
        val pack = install("u-w", "zimgit-water_en", fixture("zimgit-water_en"))
        val web = pack("u-web").copy(name = "wikipedia_en_top", scraper = "mwoffliner 2.1.1").also { db.packDao().upsert(it) }
        val links = PackBookLinks({ db.packDao().getAll() }, listOf(books))
        // Not synced yet: the link lists the pack first.
        val book = links.bookFor(pack.id, "files/Water (6).pdf")!!
        assertThat(book.id).isEqualTo("book:nautilus:zimgit-water_en/Water (6).pdf")
        assertThat(book.title).isEqualTo("Water")
        assertThat(links.isBookPack(pack.id)).isTrue()
        assertThat(links.bookFor(pack.id, "files/Not listed.pdf")).isNull()
        assertThat(links.bookFor(pack.id, "home")).isNull()
        assertThat(links.bookFor(web.id, "files/Water (6).pdf")).isNull()
        assertThat(links.isBookPack(web.id)).isFalse()
    }

    @Test fun anEarlierPackDocumentHandsItsPlaceMarksTabsAndCopyToTheRow() = test {
        val pack = install("u-food", "zimgit-food-preparation_en", fixture("zimgit-food-preparation_en"))
        // Read before Plan KB: Food Preparation (2).pdf opened from the pack's page, a hidden PACK row.
        val path = "files/Food Preparation (2).pdf"
        val copy = PdfFixtures.text(File(booksRoot, "packs/u-food"), "copy.pdf")
        val old = shelf.registerPackDocument(pack.id, path, "", copy, listedTitle = "Camping Recipes")!!
        assertThat(old.id).isEqualTo("book:pack:u-food/$path")
        shelf.savePosition(old.id, """{"href":"page/3","locations":{"totalProgression":0.4}}""", 0.4, "Page 3")
        shelf.addMark(BookAnnotation(0, old.id, MarkKind.BOOKMARK, "{}", "", "", "", 1L, 1L))
        val sessions = db.sessionDao()
        val tab = sessions.insertTab(ReadingTab(0, old.id, "", "Camping Recipes", 1L, onRail = true))
        sessions.upsertHistory(HistoryEntry(old.id, "", "Camping Recipes", 1L))
        // Another pack's document is left alone.
        val elsewhere = shelf.registerPackDocument("u-other", path, "", PdfFixtures.text(File(booksRoot, "packs/u-other"), "x.pdf"))!!

        books.sync(listOf(pack))

        val id = "book:nautilus:zimgit-food-preparation_en/Food Preparation (2).pdf"
        val row = db.bookDao().get(id)!!
        assertThat(db.bookDao().get(old.id)).isNull()
        assertThat(books.adopted).isEqualTo(1)
        assertThat(shelf.position(id)!!.totalProgression).isEqualTo(0.4)
        assertThat(shelf.position(old.id)).isNull()
        assertThat(db.bookDao().observeMarks(id).first().map { it.kind }).containsExactly(MarkKind.BOOKMARK)
        assertThat(sessions.tab(tab)!!.packId).isEqualTo(id)
        assertThat(sessions.observeHistory().first().map { it.packId }).containsExactly(id)
        // The copy moved, with its cover, rather than being copied again.
        assertThat(File(row.filePath).isFile).isTrue()
        assertThat(copy.exists()).isFalse()
        assertThat(row.coverPath).isNotNull()
        assertThat(File(row.coverPath!!).isFile).isTrue()
        assertThat(row.title).isEqualTo("Camping Recipes")
        assertThat(row.indexedAt).isNull()
        assertThat(db.bookDao().get(elsewhere.id)).isNotNull()
        // A second sync has nothing left to adopt.
        books.sync(listOf(pack))
        assertThat(books.adopted).isEqualTo(0)
    }

    @Test fun aPackDocumentTheListDoesntNameStays() = test {
        val pack = install("u-w", "zimgit-water_en", fixture("zimgit-water_en"))
        val doc = shelf.registerPackDocument(pack.id, "files/extra.pdf", "Extra", PdfFixtures.text(File(booksRoot, "packs/u-w"), "e.pdf"))!!
        books.sync(listOf(pack))
        assertThat(db.bookDao().get(doc.id)).isNotNull()
    }

    @Test fun aRemovedPacksDocumentsGo() = test {
        val pack = install("u-w", "zimgit-water_en", fixture("zimgit-water_en"))
        books.sync(listOf(pack))
        assertThat(db.bookDao().byOrigin(BookOrigin.NAUTILUS)).hasSize(7)
        db.packDao().delete(pack.id)
        books.sync(emptyList())
        assertThat(db.bookDao().byOrigin(BookOrigin.NAUTILUS)).isEmpty()
    }

    /**
     * Installs a nautilus pack whose `database.js` is [js], holding a small PDF for each listed file ([files], else
     * every one the list names); [scanned] ones have no text layer.
     */
    private suspend fun install(
        uuid: String, name: String, js: String, files: List<String>? = null, scanned: Set<String> = emptySet(),
    ): Pack {
        val listed = files ?: NautilusDatabase.records(js).flatMap { it.files }
        val text = PdfFixtures.text(src, "text.pdf").readBytes()
        val scan = PdfFixtures.imageOnly(src, "scan.pdf").readBytes()
        val entries = listed.associate { fp ->
            "${NautilusDatabase.FILES}$fp" to ("application/pdf" to if (fp in scanned) scan else text)
        }
        val archive = FakeZimArchive(uuid, name, name = name, scraper = "nautiluszim 1.1.1",
            files = entries + (NautilusDatabase.PATH to ("text/javascript" to js.toByteArray())))
        archives[uuid] = archive
        return pack(archive).also { db.packDao().upsert(it) }
    }

    private fun pack(archive: FakeZimArchive) = Pack(
        id = archive.uuid, filePath = "/packs/${archive.uuid}.zim", title = archive.metadata.title,
        description = archive.metadata.description, language = "eng", date = "2025-04-11", sizeBytes = 1_000L,
        hasFulltextIndex = true, status = PackStatus.OK, addedAt = 0L, name = archive.metadata.name,
        scraper = archive.metadata.scraper,
    )
}
