package app.granger.core.library

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.granger.core.books.TextToEpub
import app.granger.core.books.outputTo
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plan I5 Task 1: the shared book shelf on the iPhone. An EPUB is imported into a shelf in the library database, found
 * by a word in it through the book index, and a Classic's reference links are read from a `.glink` file and the
 * mentions index built from it.
 */
class BookshelfIosTest {
    private val root = NSTemporaryDirectory().trimEnd('/') + "/shelf-" + NSUUID().UUIDString

    private fun dir(name: String) = "$root/$name".also { SystemFileSystem.createDirectories(Path(it)) }

    /** A two-chapter text book made into an EPUB, as Android makes one from a `.txt` import. */
    private fun epub(): String {
        val path = dir("made") + "/walden.epub"
        val text = "Chapter 1\n\nI went to the woods because I wished to live deliberately.\n\n" +
            "Chapter 2\n\nThe pond was a perfect forest mirror, set round with stones."
        outputTo(path).use { TextToEpub.convert(text, "Walden", false, it, language = "en") }
        return path
    }

    @Test fun anImportedBookIsFoundByAWordInIt() = runBlocking {
        val db = openLibraryDatabase(dir("db") + "/library.db")
        val books = dir("books")
        val shelf = BookshelfStore(db, { listOf(books) }, { "$books/imports" }, dir("covers"), now = { 1_000L })
        val source = epub()

        val result = shelf.importFrom("walden.epub") { SystemFileSystem.source(Path(source)).buffered() }
        val id = assertIs<ImportResult.Imported>(result).bookId
        val book = assertNotNull(shelf.book(id))
        assertEquals("Walden", book.title)
        assertEquals(BookFormat.EPUB, book.format)
        assertTrue(book.filePath.startsWith("$books/imports/"), book.filePath)
        assertTrue(SystemFileSystem.exists(Path(book.filePath)))
        // The same file again is the same book.
        assertIs<ImportResult.AlreadyThere>(shelf.importFrom("again.epub") { SystemFileSystem.source(Path(source)).buffered() })

        val index = BookIndex(db)
        index.indexPending()
        val hits = index.search("deliberately")
        assertEquals(listOf(id), hits.map { it.bookId }.distinct())
        assertTrue("deliberately" in hits.first().snippet, hits.first().snippet)
        assertTrue(index.search("zeppelin").isEmpty())
        assertEquals(listOf(id), index.passages(listOf("forest mirror")).map { it.book.id })

        assertTrue(shelf.remove(id))
        assertNull(shelf.book(id))
        assertTrue(index.search("deliberately").isEmpty())
        db.close()
    }

    /** A `.glink` with tools/links/build.py's schema: [mentions] are (qid, href, start). */
    private fun glink(work: Long?, vararg mentions: Triple<Long, String, Int>): String {
        val path = dir("links") + "/" + NSUUID().UUIDString + ".glink"
        val c = BundledSQLiteDriver().open(path)
        c.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        c.execSQL("INSERT INTO meta VALUES ('kind', 'glink'), ('schemaVersion', '1'), ('sourceSha256', 'sha')")
        c.execSQL("CREATE TABLE book(id TEXT NOT NULL, qid INTEGER, year INTEGER)")
        c.execSQL("INSERT INTO book VALUES ('pp', ${work ?: "NULL"}, 1813)")
        c.execSQL("CREATE TABLE mentions(book TEXT, href TEXT, start INTEGER, len INTEGER, quote TEXT, before TEXT, qid INTEGER, tier INTEGER, conf REAL)")
        for ((qid, href, start) in mentions) {
            c.execSQL("INSERT INTO mentions VALUES ('pp', '$href', $start, 6, 'London', 'in ', $qid, 1, 1.0)")
        }
        c.close()
        return path
    }

    @Test fun aClassicsLinksAreReadAndIndexed() {
        val path = glink(170583L, Triple(84L, "c2.xhtml", 5), Triple(84L, "c1.xhtml", 9), Triple(90L, "c1.xhtml", 40))

        val links = assertNotNull(BookLinks.open(path, "sha"))
        links.use {
            assertEquals("pp", it.bookId)
            assertEquals(170583L, it.workQid)
            assertEquals(1813, it.year)
            assertEquals(listOf("c1.xhtml" to 9, "c1.xhtml" to 40, "c2.xhtml" to 5), it.mentions(maxTier = 1).map { m -> m.href to m.start })
            assertEquals(2, it.countOf(84L))
        }
        // Built from another EPUB than the installed one: no links.
        assertNull(BookLinks.open(path, "other"))
        assertNull(BookLinks.open("$root/missing.glink"))

        val index = MentionIndex(dir("idx") + "/mentions.sqlite")
        assertEquals(1, index.sync(mapOf("book:granger:pp" to LinksFile(path, "sha"))))
        assertEquals(listOf("book:granger:pp" to 2), index.counts(84L))
        assertEquals(listOf("c1.xhtml", "c2.xhtml"), index.passages("book:granger:pp", 84L, 10).map { it.href })
        assertEquals(mapOf("book:granger:pp" to 170583L), index.works())
        // Nothing changed: nothing copied. A book gone: its mentions go.
        assertEquals(0, index.sync(mapOf("book:granger:pp" to LinksFile(path, "sha"))))
        assertEquals(0, index.sync(emptyMap()))
        assertTrue(index.counts(84L).isEmpty())
        index.close()
    }
}
