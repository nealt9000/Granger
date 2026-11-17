package app.granger.core.library

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.granger.core.books.PdfFixtures
import app.granger.core.books.TestEpubs
import com.google.common.truth.Truth.assertThat
import java.io.File
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

/**
 * Plan KB2 Task 2.1: Kiwix packs with a `.gbooks` manifest as book rows, from the fixture manifests that
 * tools/bookpacks/build.py made from the fixture ZIMs (their `meta.sourceSha256` is each ZIM's sha256).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestBooksTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = inMemoryDatabase()
    private val dispatcher = StandardTestDispatcher()
    private val src by lazy { tmp.newFolder("src") }
    private val booksRoot by lazy { File(tmp.root, "books") }
    private val coversDir by lazy { File(tmp.root, "covers") }
    private val archives = HashMap<String, FakeZimArchive>()
    private val cover = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    private val books by lazy {
        ManifestBooks(db, { archives[it] }, { File(booksRoot, "kiwix") }, coversDir, dispatcher, { 1_000L }, pdfCovers = { _, _ -> cover })
    }
    private val manifests by lazy { BookManifests(db, { archives[it] }, dispatcher) }

    @Before fun pdfBox() = PdfFixtures.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())

    @After fun close() = db.close()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    /** A fixture manifest copied out where SQLite can open it. */
    private fun manifest(name: String, to: File = File(tmp.root, "sidecars/$name")): File {
        to.parentFile!!.mkdirs()
        javaClass.getResourceAsStream("/gbooks/$name")!!.use { input -> to.outputStream().use { input.copyTo(it) } }
        return to
    }

    private val example = "example.org_en_all.gbooks"
    private val storybooks = "storybooks_mul_all.gbooks"

    /** The fixture items' paths, as the zimit fixture ZIM holds them. */
    private val examplePaths = listOf(
        "www.example.org/files/field-radio.pdf?tmstv=1786&nonce=ab12",
        "www.example.org/manuals/files/knots.pdf",
        "www.example.org/unlinked/sea-survival.pdf",
        "www.example.org/manuals/files/water-pt1.pdf",
        "www.example.org/manuals/files/water-pt2.pdf",
        "www.example.org/library/a_treatise_on_hedges_1856.pdf",
        "www.example.org/library/bee_keeping_for_all_1902.pdf",
    )

    private suspend fun install(uuid: String, name: String, files: Map<String, Pair<String, ByteArray>> = emptyMap(), counter: String = ""): Pack {
        val archive = FakeZimArchive(uuid, "Example web site", name = name, files = files, counter = counter)
        archives[uuid] = archive
        val pack = Pack(
            id = uuid, filePath = "/packs/$uuid.zim", title = archive.metadata.title, description = "", language = "eng",
            date = "2026-10-01", sizeBytes = 1_000L, hasFulltextIndex = true, status = PackStatus.OK, addedAt = 0L, name = name,
            scraper = "zimit 3.0",
        )
        db.packDao().upsert(pack)
        return pack
    }

    private fun pdfs(paths: List<String>): Map<String, Pair<String, ByteArray>> {
        val text = PdfFixtures.text(src, "text.pdf").readBytes()
        return paths.associateWith { "application/pdf" to text }
    }

    private suspend fun installExample(uuid: String = "u-ex"): Pack {
        install(uuid, "example.org_en_all", pdfs(examplePaths))
        manifests.attach(mapOf(uuid to ManifestFile(manifest(example), ZIMIT_SHA)))
        return db.packDao().get(uuid)!!
    }

    private suspend fun rows() = db.bookDao().byOrigin(BookOrigin.KIWIX).sortedBy { it.ord }

    @Test fun aVerifiedManifestMakesThePackABookPack() = test {
        val pack = installExample()
        assertThat(pack.bookManifest).isEqualTo(File(tmp.root, "sidecars/$example").absolutePath)
        assertThat(pack.bookSource).isEqualTo(BookOrigin.KIWIX)
        assertThat(pack.isBookPack).isTrue()
        assertThat(Filing.defaultKey(pack)).isEqualTo("kiwixbooks-example.org_en_all")
        assertThat(Filing.folderKey(pack)).isNull()
        // Its collection is named by its manifest, not by the pack's own title.
        assertThat(Filing.defaultName("kiwixbooks-example.org_en_all", pack)).isEqualTo("Example Library")
        // Nothing changes the second time.
        assertThat(manifests.attach(mapOf(pack.id to ManifestFile(manifest(example), ZIMIT_SHA)))).isEqualTo(0)
    }

    @Test fun rowsComeFromTheManifestWithTitlesGroupsOrderAndParts() = test {
        val pack = installExample()
        books.sync(listOf(pack))
        val rows = rows()
        assertThat(rows.map { it.title }).containsExactly(
            "Field Radio Basics", "Knots: A Visual Guide", "Sea Survival Handbook", "Water Purification (Part 1 of 2)",
            "Water Purification (Part 2 of 2)", "A Treatise On Hedges (1856)", "Bee Keeping For All (1902)",
        ).inOrder()
        assertThat(rows.map { it.ord }).containsExactly(0, 1, 2, 3, 4, 5, 6).inOrder()
        assertThat(rows.map { it.groupName }).containsExactly("", "", "", "", "", "Farming and Gardens", "Farming and Gardens").inOrder()
        val radio = rows.first()
        // The key drops the volatile query; the row keeps the entry's real path to copy it from.
        assertThat(radio.id).isEqualTo("book:kiwix:example.org_en_all/www.example.org/files/field-radio.pdf")
        assertThat(radio.sourceRef).isEqualTo("example.org_en_all/www.example.org/files/field-radio.pdf?tmstv=1786&nonce=ab12")
        assertThat(radio.format).isEqualTo(BookFormat.PDF)
        assertThat(radio.sizeBytes).isEqualTo(3583)
        assertThat(radio.language).isEqualTo("eng")
        assertThat(radio.workKey).isNull()
        assertThat(radio.onShelf).isTrue()
        assertThat(radio.origin.isPackBook).isTrue()
        assertThat(File(radio.filePath).parentFile).isEqualTo(File(booksRoot, "kiwix"))
        assertThat(File(radio.filePath).exists()).isFalse()
        assertThat(Filing.defaultKey(radio)).isEqualTo("kiwixbooks-example.org_en_all")
        assertThat(Filing.defaultName("kiwixbooks-example.org_en_all", radio, listOf(pack))).isEqualTo("Example Library")
        // The two parts of a work with no whole file: one work, in order.
        val parts = rows.filter { it.workKey != null }
        assertThat(parts.map { it.workKey }.toSet()).containsExactly("www.example.org/manuals/files/water")
        assertThat(parts.map { it.workTitle }.toSet()).containsExactly("Water Purification")
        assertThat(parts.map { it.partNo }).containsExactly(1, 2).inOrder()
    }

    @Test fun aKolibriManifestGivesEpubsInTheirLanguagesAndLevels() = test {
        val epub = TestEpubs.build(tmp.newFolder("epub"), name = "goat.epub", title = "The Hungry Goat").readBytes()
        install("u-st", "storybooks_mul_all", mapOf("e1.epub" to ("application/epub+zip" to epub), "e2.epub" to ("application/epub+zip" to epub)))
        manifests.attach(mapOf("u-st" to ManifestFile(manifest(storybooks), KOLIBRI_SHA)))
        val pack = db.packDao().get("u-st")!!
        books.sync(listOf(pack))
        val rows = rows()
        assertThat(rows.map { Triple(it.title, it.language, it.groupName) }).containsExactly(
            Triple("The Hungry Goat", "en", "Level 1"), Triple("Mbuzi Mwenye Njaa", "sw", "Level 2"),
        ).inOrder()
        assertThat(rows.map { it.format }.toSet()).containsExactly(BookFormat.EPUB)
        assertThat(rows.first().id).isEqualTo("book:kiwix:storybooks_mul_all/a1111111111111111111111111111111")
        assertThat(rows.first().filePath).endsWith(".epub")
        // An EPUB opens as an EPUB, read with its own inspector.
        assertThat(books.restore(rows.first())).isTrue()
        assertThat(File(db.bookDao().get(rows.first().id)!!.filePath).isFile).isTrue()
        val collection = BookManifest.collection(File(pack.bookManifest!!))!!
        assertThat(collection.title).isEqualTo("Example Storybooks")
        assertThat(collection.noun).isEqualTo("books")
        assertThat(collection.groups).containsExactly("Level 1", "Level 2").inOrder()
    }

    @Test fun aManifestBuiltFromAnotherFileIsIgnored() = test {
        install("u-ex", "example.org_en_all", pdfs(examplePaths))
        manifests.attach(mapOf("u-ex" to ManifestFile(manifest(example), "0".repeat(64))))
        val pack = db.packDao().get("u-ex")!!
        assertThat(pack.bookManifest).isNull()
        assertThat(pack.bookSource).isNull()
        books.sync(listOf(pack))
        assertThat(rows()).isEmpty()
        // Nor is a file that isn't a book manifest at all.
        val other = File(tmp.root, "not.gbooks").apply { writeText("hello") }
        assertThat(BookManifest.verify(other, ZIMIT_SHA)).isFalse()
    }

    @Test fun aManifestThatGoesTakesItsPacksBooksWithIt() = test {
        val pack = installExample()
        books.sync(listOf(pack))
        assertThat(rows()).hasSize(7)
        manifests.attach(emptyMap())
        val plain = db.packDao().get(pack.id)!!
        assertThat(plain.bookManifest).isNull()
        books.sync(listOf(plain))
        assertThat(rows()).isEmpty()
    }

    @Test fun aNewSnapshotKeepsTheBooksIdsAndPlaces() = test {
        val old = installExample("u-old")
        books.sync(listOf(old))
        val before = rows().map { it.id }
        val radio = rows().first()
        db.bookDao().upsertPosition(BookPosition(radio.id, "{}", 0.4, "Chapter 2", 5L))
        // The new snapshot: another file, its own manifest (the same keys), the old pack gone.
        db.packDao().delete(old.id)
        val newer = File(tmp.root, "sidecars/example_v2.gbooks")
        manifest(example, newer)
        val newSha = "a".repeat(64)
        SQLiteDatabase.openDatabase(newer.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.update("meta", ContentValues().apply { put("value", newSha) }, "key = 'sourceSha256'", null)
            // The site moved a file: the key stays.
            it.update("items", ContentValues().apply { put("path", "www.example.org/files/field-radio.pdf?tmstv=2001") }, "key = 'www.example.org/files/field-radio.pdf'", null)
        }
        install("u-new", "example.org_en_all", pdfs(examplePaths.drop(1) + "www.example.org/files/field-radio.pdf?tmstv=2001"))
        manifests.attach(mapOf("u-new" to ManifestFile(newer, newSha)))
        val pack = db.packDao().get("u-new")!!
        assertThat(pack.bookManifest).isEqualTo(newer.absolutePath)
        books.sync(listOf(pack))
        val after = rows()
        assertThat(after.map { it.id }).containsExactlyElementsIn(before).inOrder()
        assertThat(after.first().id).isEqualTo(radio.id)
        assertThat(after.first().sourceRef).isEqualTo("example.org_en_all/www.example.org/files/field-radio.pdf?tmstv=2001")
        assertThat(db.bookDao().position(radio.id)?.chapterTitle).isEqualTo("Chapter 2")
        assertThat(books.restore(after.first())).isTrue()
    }

    @Test fun removingThePackRemovesItsRowsAndTheirCopies() = test {
        val pack = installExample()
        books.sync(listOf(pack))
        val radio = rows().first()
        assertThat(books.restore(radio)).isTrue()
        val copy = File(db.bookDao().get(radio.id)!!.filePath)
        assertThat(copy.isFile).isTrue()
        db.packDao().delete(pack.id)
        books.sync(emptyList())
        assertThat(rows()).isEmpty()
        assertThat(copy.exists()).isFalse()
    }

    @Test fun aMissingPackKeepsItsManifestAndItsBooks() = test {
        val pack = installExample()
        books.sync(listOf(pack))
        val missing = pack.copy(status = PackStatus.MISSING)
        db.packDao().upsert(missing)
        manifests.attach(emptyMap())
        assertThat(db.packDao().get(pack.id)!!.bookManifest).isEqualTo(pack.bookManifest)
        books.sync(listOf(db.packDao().get(pack.id)!!))
        assertThat(rows()).hasSize(7)
        assertThat(rows().map { it.status }.toSet()).containsExactly(PackStatus.MISSING)
    }

    @Test fun restoreCopiesAPdfWhosePathHasAQueryString() = test {
        val pack = installExample()
        books.sync(listOf(pack))
        val radio = rows().first()
        assertThat(books.restore(radio)).isTrue()
        val row = db.bookDao().get(radio.id)!!
        assertThat(File(row.filePath).isFile).isTrue()
        assertThat(File(row.filePath).name).isEqualTo(PackBooks.sha1("example.org_en_all/www.example.org/files/field-radio.pdf") + ".pdf")
        assertThat(row.coverPath).isNotNull()
        assertThat(archives.getValue("u-ex").reads).contains("www.example.org/files/field-radio.pdf?tmstv=1786&nonce=ab12")
    }

    @Test fun aLinkOnThePacksOwnPageOpensItsRow() = test {
        val pack = installExample()
        val links = PackBookLinks({ db.packDao().getAll() }, listOf(books))
        assertThat(links.isBookPack(pack.id)).isTrue()
        val byPath = links.bookFor(pack.id, "www.example.org/manuals/files/knots.pdf")
        assertThat(byPath?.title).isEqualTo("Knots: A Visual Guide")
        // A link to the file with another snapshot's query still finds it, by its key.
        assertThat(links.bookFor(pack.id, "www.example.org/files/field-radio.pdf?tmstv=99")?.title).isEqualTo("Field Radio Basics")
        assertThat(links.bookFor(pack.id, "www.example.org/index.html")).isNull()
    }

    @Test fun gutenbergAndNautilusPacksAreLeftAlone() = test {
        install("u-g", "gutenberg_en_lcc-m")
        db.packDao().upsert(db.packDao().get("u-g")!!.copy(scraper = "gutenberg2zim 3.0", bookManifest = "/stale.gbooks"))
        manifests.attach(mapOf("u-g" to ManifestFile(manifest(example), ZIMIT_SHA)))
        val pack = db.packDao().get("u-g")!!
        assertThat(pack.bookManifest).isNull()
        assertThat(pack.bookSource).isEqualTo(BookOrigin.GUTENBERG)
    }

    @Test fun bookSourceIsKiwixFromTheColumnAfterTheScraperRules() {
        val site = pack("u-a").copy(name = "example.org_en_all", scraper = "zimit")
        assertThat(site.bookSource).isNull()
        assertThat(site.copy(bookManifest = "/x.gbooks").bookSource).isEqualTo(BookOrigin.KIWIX)
        assertThat(site.copy(bookManifest = Pack.BOOK_FILES).bookSource).isEqualTo(BookOrigin.KIWIX)
        assertThat(site.copy(scraper = "nautiluszim 1.1", bookManifest = "/x.gbooks").bookSource).isEqualTo(BookOrigin.NAUTILUS)
        assertThat(BookOrigin.KIWIX.isPackBook).isTrue()
        assertThat(BookIds.of(BookOrigin.KIWIX, "a/b")).isEqualTo("book:kiwix:a/b")
    }

    // --- Task 2.2: a pure container with no manifest lists its files ---

    private fun entry(path: String, mime: String = "application/pdf", size: Long = 2_000) =
        app.granger.core.zim.ZimEntry(path, path.substringAfterLast('/'), mime, size)

    @Test fun aPureContainerWithNoManifestListsItsFilesTitledByTheirNames() = test {
        val paths = listOf("files/A_Treatise%20on_Hedges.pdf", "files/bee-keeping.epub", "files/Field_Radio.pdf?x=1")
        val text = PdfFixtures.text(src, "text.pdf").readBytes()
        val archive = FakeZimArchive(
            "u-box", "Box of manuals", name = "manuals.example_en_all", counter = "text/html=3;application/pdf=2;application/epub+zip=1;image/png=9",
            files = paths.associateWith { "application/pdf" to text },
            listed = listOf(entry(paths[0]), entry(paths[1], "application/epub+zip"), entry("files/cover.png", "image/png"), entry(paths[2])),
        )
        archives["u-box"] = archive
        db.packDao().upsert(
            Pack("u-box", "/packs/u-box.zim", "Box of manuals", "", "eng", "2026-10-01", 1_000L, true, PackStatus.OK, 0L, name = "manuals.example_en_all", scraper = "zimit"),
        )
        manifests.attach(emptyMap())
        val pack = db.packDao().get("u-box")!!
        assertThat(pack.bookManifest).isEqualTo(Pack.BOOK_FILES)
        assertThat(pack.bookSource).isEqualTo(BookOrigin.KIWIX)
        assertThat(BookManifest.titleOf(pack)).isNull()
        books.sync(listOf(pack))
        val rows = rows()
        assertThat(rows.map { it.title }).containsExactly("A Treatise on Hedges", "bee-keeping", "Field Radio").inOrder()
        assertThat(rows.map { it.format }).containsExactly(BookFormat.PDF, BookFormat.EPUB, BookFormat.PDF).inOrder()
        assertThat(rows.first().id).isEqualTo("book:kiwix:manuals.example_en_all/files/A_Treatise%20on_Hedges.pdf")
        assertThat(rows.map { it.groupName }.toSet()).containsExactly(null)
        assertThat(Filing.defaultName("kiwixbooks-manuals.example_en_all", pack)).isEqualTo("Box of manuals")
        // A link to one of its files opens its row.
        assertThat(PackBookLinks({ db.packDao().getAll() }, listOf(books)).bookFor("u-box", "files/Field_Radio.pdf?x=1")?.title).isEqualTo("Field Radio")
        assertThat(books.restore(rows.last())).isTrue()
        // A manifest built from another file doesn't take over; a verified one does.
        manifests.attach(mapOf("u-box" to ManifestFile(manifest(example), "b".repeat(64))))
        assertThat(db.packDao().get("u-box")!!.bookManifest).isEqualTo(Pack.BOOK_FILES)
        manifests.attach(mapOf("u-box" to ManifestFile(manifest(example), ZIMIT_SHA)))
        assertThat(db.packDao().get("u-box")!!.bookManifest).isEqualTo(File(tmp.root, "sidecars/$example").absolutePath)
    }

    @Test fun aWebsiteWithManyDocumentsStaysAWebsite() = test {
        // The World Factbook: 839 PDFs beside 830 pages. The rule doesn't loosen.
        install("u-wf", "theworldfactbook_en_all", counter = "text/html=830;application/pdf=839;image/jpeg=2000")
        // Two documents aren't a library; nor is a pack whose Counter says nothing.
        install("u-two", "two.example_en_all", counter = "text/html=1;application/pdf=2")
        install("u-none", "none.example_en_all")
        manifests.attach(emptyMap())
        assertThat(db.packDao().getAll().map { it.bookManifest }.toSet()).containsExactly(null)
        assertThat(BookFiles.isContainer("text/html=10;application/pdf=3")).isTrue()
        assertThat(BookFiles.isContainer("text/html=11;application/pdf=300")).isFalse()
        assertThat(BookFiles.isContainer("text/html; charset=utf-8=4;application/epub+zip=3")).isTrue()
        assertThat(BookFiles.parseCounter("text/html=12;bad;image/png=x;application/pdf=3")).containsExactly("text/html", 12, "application/pdf", 3)
    }

    @Test fun aPackThatCantBeOpenedKeepsItsFilesListing() = test {
        install("u-box", "box_en_all", counter = "text/html=1;application/pdf=5")
        manifests.attach(emptyMap())
        assertThat(db.packDao().get("u-box")!!.bookManifest).isEqualTo(Pack.BOOK_FILES)
        // Read once per snapshot: the archive going away later doesn't flip it.
        archives.remove("u-box")
        manifests.attach(emptyMap())
        assertThat(db.packDao().get("u-box")!!.bookManifest).isEqualTo(Pack.BOOK_FILES)
        // A fresh reader that can't open it keeps what the row says.
        BookManifests(db, { null }, dispatcher).attach(emptyMap())
        assertThat(db.packDao().get("u-box")!!.bookManifest).isEqualTo(Pack.BOOK_FILES)
    }

    @Test fun fileNameTitles() {
        assertThat(BookFiles.titleOf("a/b/Water_Purification%E2%80%94Part_1.pdf")).isEqualTo("Water Purification—Part 1")
        assertThat(BookFiles.titleOf("a/C%2B%2B_Primer.epub")).isEqualTo("C++ Primer")
        assertThat(BookFiles.titleOf("viewcontent.cgi?article=1028")).isEqualTo("viewcontent")
        assertThat(BookFiles.titleOf("x/100%_sure.pdf")).isEqualTo("100% sure")
        assertThat(BookFiles.titleOf("x/.pdf")).isEqualTo(".pdf")
    }

    private companion object {
        /** sha256 of core/library/src/test/resources/gbooks/zimit-books.zim and kolibri-books.zim. */
        const val ZIMIT_SHA = "71ee36ed0e7093ba1a02d6c6ceaba82dced94239323c20fe86e6aeb633bb9858"
        const val KOLIBRI_SHA = "d1fe093dc0f681f48242d88f4d8a673da98299093b411217e45644549d7ba62d"
    }
}
