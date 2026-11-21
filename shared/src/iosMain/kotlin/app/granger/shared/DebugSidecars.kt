package app.granger.shared

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * A Classic's `.glink` and a pack's `.gqid` of one link each, in the builders' schemas (tools/links/build.py,
 * tools/qid/build.py), for debug builds and UI tests: no Classic's links download in a test (Plan I5 Task 7).
 */
internal object DebugSidecars {
    /** A links file built from the EPUB whose sha256 is [sha]: [quote] in [href] names [qid], at tier 1. */
    fun writeLinks(path: String, sha: String, bookId: String, href: String, quote: String, qid: Long) {
        SystemFileSystem.delete(Path(path), mustExist = false)
        val c = BundledSQLiteDriver().open(path)
        try {
            c.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            c.execSQL("INSERT INTO meta VALUES ('kind', 'glink'), ('schemaVersion', '1'), ('sourceSha256', '${sql(sha)}')")
            c.execSQL("CREATE TABLE book(id TEXT NOT NULL, qid INTEGER, year INTEGER)")
            c.execSQL("INSERT INTO book VALUES ('${sql(bookId)}', NULL, NULL)")
            c.execSQL("CREATE TABLE mentions(book TEXT, href TEXT, start INTEGER, len INTEGER, quote TEXT, before TEXT, qid INTEGER, tier INTEGER, conf REAL)")
            c.execSQL("INSERT INTO mentions VALUES ('${sql(bookId)}', '${sql(href)}', 0, ${quote.length}, '${sql(quote)}', '', $qid, 1, 1.0)")
        } finally {
            c.close()
        }
    }

    /** A QID index with one article: [articlePath] is [qid]. */
    fun writeQids(path: String, articlePath: String, qid: Long) {
        SystemFileSystem.delete(Path(path), mustExist = false)
        val c = BundledSQLiteDriver().open(path)
        try {
            c.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            c.execSQL("INSERT INTO meta VALUES ('kind', 'gqid'), ('schemaVersion', '1'), ('sourceSha256', '')")
            c.execSQL("CREATE TABLE articles(path TEXT PRIMARY KEY, qid INTEGER NOT NULL, rank INTEGER NOT NULL, other TEXT)")
            c.execSQL("CREATE TABLE links(qid INTEGER, prop TEXT, value TEXT)")
            c.execSQL("INSERT INTO articles VALUES ('${sql(articlePath)}', $qid, 10, NULL)")
        } finally {
            c.close()
        }
    }

    private fun sql(text: String) = text.replace("'", "''")
}

/** What the iPhone build turns on, as Android's `Features` (release values). */
object IosFeatures {
    /** The Linked Library: the Classics' reference links, In your books, and the QID indexes they read (Android: on). */
    const val LINKED_CLASSICS = true
}
