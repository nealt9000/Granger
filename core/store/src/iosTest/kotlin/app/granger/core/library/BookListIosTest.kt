package app.granger.core.library

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimHit
import app.granger.core.zim.ZimItem
import app.granger.core.zim.ZimMetadata
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan I4b Task 8: a book pack's books read on the iPhone (the `.gbooks` manifest, Gutenberg's list, [PackBookList]). */
class BookListIosTest {
    private val sha = "a".repeat(64)

    /** A tiny manifest: two books in two sections, one with a reading edition, and a JPEG the reader can't open. */
    private fun manifest(): String {
        val path = NSTemporaryDirectory() + NSUUID().UUIDString + ".gbooks"
        val c = BundledSQLiteDriver().open(path)
        c.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
        c.execSQL("INSERT INTO meta VALUES ('kind', 'gbooks'), ('schemaVersion', '1'), ('sourceSha256', '$sha')")
        c.execSQL("CREATE TABLE collection (key TEXT PRIMARY KEY, value TEXT)")
        c.execSQL("INSERT INTO collection VALUES ('title', 'Field Manuals'), ('noun', 'documents'), ('provider', 'Army')")
        c.execSQL("CREATE TABLE groups (name TEXT, ord INTEGER)")
        c.execSQL("INSERT INTO groups VALUES ('Survival', 1), ('Medicine', 0)")
        c.execSQL(
            "CREATE TABLE items (key TEXT, path TEXT, format TEXT, title TEXT, author TEXT, description TEXT, language TEXT, " +
                "group_name TEXT, ord INTEGER, bytes INTEGER, part_of TEXT, work_key TEXT, work_title TEXT, part_no INTEGER, " +
                "zim_offset INTEGER, sha256 TEXT, edition TEXT)",
        )
        c.execSQL(
            "INSERT INTO items VALUES " +
                "('fm21', 'files/fm21.pdf', 'pdf', 'Survival', 'Army', 'Stay alive', 'en', 'Survival', 2, 1000, NULL, NULL, NULL, NULL, NULL, NULL, NULL), " +
                "('fm4', 'files/fm4.epub', 'EPUB', '', '', '', '', 'Medicine', 1, 2000, '', NULL, NULL, 3, 512, 'b', " +
                "'{\"url\": \"https://x/fm4.epub\", \"sha256\": \"c\", \"bytes\": 42, \"title\": \"First Aid\", \"id\": \"re-fm4\"}'), " +
                "('pic', 'files/pic.jpg', 'jpg', 'A picture', '', '', '', '', 0, 5, NULL, NULL, NULL, NULL, NULL, NULL, NULL)",
        )
        c.close()
        return path
    }

    @Test fun aManifestReads() {
        val path = manifest()
        assertTrue(BookManifest.verify(path, sha.uppercase()))
        assertFalse(BookManifest.verify(path, "b".repeat(64)))

        val items = assertNotNull(BookManifest.items(path))
        assertEquals(listOf("fm4", "fm21"), items.map { it.key })
        val fm4 = items[0]
        assertEquals(BookFormat.EPUB, fm4.format)
        assertNull(fm4.partOf)
        assertEquals(3, fm4.partNo)
        assertEquals(512L, fm4.zimOffset)
        assertEquals(ManifestEdition("https://x/fm4.epub", "c", 42, "First Aid", "re-fm4"), fm4.edition)
        assertNull(items[1].partNo)
        assertNull(items[1].edition)

        val collection = assertNotNull(BookManifest.collection(path))
        assertEquals("Field Manuals", collection.title)
        assertTrue(collection.documents)
        assertEquals(listOf("Medicine", "Survival"), collection.groups)
        assertEquals("fm21", BookManifest.keyOf(path, "files/fm21.pdf"))

        assertNull(BookManifest.items(path + ".missing"))
    }

    @Test fun packBookListListsAManifestPack() {
        val path = manifest()
        val pack = Pack(
            id = "uuid-fm", filePath = "/p/fm.zim", title = "FM", description = "", language = "eng", date = "",
            sizeBytes = 0, hasFulltextIndex = false, status = PackStatus.OK, addedAt = 0, name = "army_fm", bookManifest = path,
        )
        assertEquals("Field Manuals", BookManifest.titleOf(pack))
        val books = assertNotNull(PackBookList.list(pack, Fake(emptyMap())))
        assertEquals(
            listOf(
                PackBookEntry("files/fm4.epub", "fm4", "", BookFormat.EPUB, 2000, "Medicine"),
                PackBookEntry("files/fm21.pdf", "Survival", "Army", BookFormat.PDF, 1000, "Survival"),
            ),
            books,
        )
        assertNull(PackBookList.list(pack.copy(bookManifest = null), Fake(emptyMap())), "a website lists nothing")
    }

    @Test fun gutenbergsListParsesAndLists() {
        val js = """var json_data = [["The Moon: a study", "Nasmyth, James", "110", 22636, "QB"],""" +
            """["Bad id", "X", "1", "abc", "Q"], ["", "No title", "1", "5", "Q"], "junk", ["Walden", "Thoreau", "9", "205.0", "PS"]];"""
        assertEquals(
            listOf(GutenbergListing("The Moon: a study", "Nasmyth, James", "22636"), GutenbergListing("Walden", "Thoreau", "205")),
            GutenbergList.parse(js),
        )
        assertTrue(GutenbergList.parse("var json_data = [[\"broken\";").isEmpty())

        val archive = Fake(
            mapOf(
                GutenbergList.PATH to ("text/javascript" to js.encodeToByteArray()),
                "The Moon: a study.22636.epub" to (GutenbergList.EPUB_MIME to ByteArray(7)),
            ),
        )
        val pack = Pack(
            id = "uuid-g", filePath = "/p/g.zim", title = "G", description = "", language = "eng", date = "", sizeBytes = 0,
            hasFulltextIndex = false, status = PackStatus.OK, addedAt = 0, name = "gutenberg_en_lcc-q", scraper = "gutenberg2zim-3.0.1",
        )
        assertEquals(
            listOf(PackBookEntry("The Moon: a study.22636.epub", "The Moon: a study", "Nasmyth, James", BookFormat.EPUB, 7, null)),
            PackBookList.list(pack, archive),
        )
    }

    /** A pack holding [files] (path to type and bytes). */
    private class Fake(private val files: Map<String, Pair<String, ByteArray>>) : ZimArchive {
        override val uuid = "uuid"
        override val metadata = ZimMetadata("t", "", "eng", "", "n")
        override val mainPath: String? = null
        override val hasFulltextIndex = false
        override fun resolvePath(path: String) = path.takeIf { it in files }
        override fun read(path: String) = files[path]?.let { ZimItem(path, it.first, it.second) }
        override fun randomPath(): String? = null
        override fun suggest(query: String, limit: Int) = emptyList<ZimHit>()
        override fun search(query: String, limit: Int) = emptyList<ZimHit>()
        override fun close() {}
    }
}
