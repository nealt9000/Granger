package app.granger.core.library

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/** An installed Classic's links file and the sha256 of the EPUB it must have been built from. */
data class LinksFile(val file: File, val sourceSha256: String)

/**
 * Every installed Classic's reference links in one local file (Plan LL1 Task 9, Neal 2026-11-13: a whole Classics
 * collection is 622 books, too many files to open for each article). An article's footer is then one indexed query
 * on its QID. [sync] keeps it to the installed `.glink` files, copying a new or changed book in with SQLite's ATTACH and
 * dropping a removed one, so only the first sync after installing a collection does real work. Blocking; call on IO.
 */
class MentionIndex(private val file: File) : Closeable {
    private var db: SQLiteDatabase? = null
    private var synced: Map<String, LinksFile>? = null

    @Synchronized
    private fun db(): SQLiteDatabase = db ?: run {
        file.parentFile?.mkdirs()
        var d = SQLiteDatabase.openOrCreateDatabase(file, null)
        if (d.version != VERSION) {
            d.close()
            file.delete()
            File(file.path + "-journal").delete()
            d = SQLiteDatabase.openOrCreateDatabase(file, null)
            d.execSQL("CREATE TABLE books(book_id TEXT PRIMARY KEY, file TEXT NOT NULL, sha TEXT NOT NULL, work INTEGER)")
            d.execSQL(
                "CREATE TABLE mentions(book_id TEXT NOT NULL, qid INTEGER NOT NULL, href TEXT NOT NULL, start INTEGER NOT NULL, " +
                    "quote TEXT NOT NULL, before TEXT NOT NULL, tier INTEGER NOT NULL)",
            )
            d.execSQL("CREATE INDEX mentions_qid ON mentions(qid, book_id)")
            d.version = VERSION
        }
        d.also { db = it }
    }

    /**
     * Makes the index hold exactly [files] (bookshelf id → links file): removed books and changed files out, new ones
     * in. A file that isn't a links file, or was built from another EPUB, is left out. Returns how many books it copied.
     */
    @Synchronized
    fun sync(files: Map<String, LinksFile>): Int {
        if (files == synced) return 0
        val d = db()
        val held = d.rawQuery("SELECT book_id, file, sha FROM books", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), LinksFile(File(c.getString(1)), c.getString(2))) }
        }
        val stale = held.filter { (id, f) -> files[id] != f }.keys
        if (stale.isNotEmpty()) {
            d.beginTransaction()
            try {
                for (id in stale) {
                    d.execSQL("DELETE FROM mentions WHERE book_id = ?", arrayOf(id))
                    d.execSQL("DELETE FROM books WHERE book_id = ?", arrayOf(id))
                }
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
        var copied = 0
        for ((id, f) in files) {
            if (held[id] == f) continue
            val work = BookLinks.open(f.file, f.sourceSha256)?.use { Result.success(it.workQid) } ?: continue
            if (copy(d, id, f, work.getOrNull())) copied++
        }
        synced = files
        return copied
    }

    private fun copy(d: SQLiteDatabase, id: String, f: LinksFile, work: Long?): Boolean = try {
        d.execSQL("ATTACH DATABASE ? AS g", arrayOf(f.file.path))
        try {
            d.beginTransaction()
            try {
                d.execSQL("INSERT INTO mentions SELECT ?, qid, href, start, quote, before, tier FROM g.mentions", arrayOf(id))
                d.execSQL("INSERT INTO books VALUES (?, ?, ?, ?)", arrayOf<Any?>(id, f.file.path, f.sourceSha256, work))
                d.setTransactionSuccessful()
                true
            } finally {
                d.endTransaction()
            }
        } finally {
            d.execSQL("DETACH DATABASE g")
        }
    } catch (e: android.database.SQLException) {
        false
    }

    /** Each indexed book's Wikidata work, by bookshelf id; books with none are left out. */
    @Synchronized
    fun works(): Map<String, Long> =
        db().rawQuery("SELECT book_id, work FROM books WHERE work IS NOT NULL", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) }
        }

    /** The books that mention [qid] and how often, most first. */
    @Synchronized
    fun counts(qid: Long): List<Pair<String, Int>> =
        db().rawQuery(
            "SELECT book_id, COUNT(*) AS n FROM mentions WHERE qid = ? GROUP BY book_id ORDER BY n DESC, book_id",
            arrayOf(qid.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) } }

    /** [bookId]'s first [limit] mentions of [qid], in book order. */
    @Synchronized
    fun passages(bookId: String, qid: Long, limit: Int): List<BookMention> =
        db().rawQuery(
            "SELECT href, start, quote, before, qid, tier FROM mentions WHERE qid = ? AND book_id = ? ORDER BY href, start LIMIT ?",
            arrayOf(qid.toString(), bookId, limit.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(BookMention(c.getString(0), c.getInt(1), c.getString(2), c.getString(3), c.getLong(4), c.getInt(5)))
            }
        }

    @Synchronized
    override fun close() {
        db?.close()
        db = null
        synced = null
    }

    private companion object {
        const val VERSION = 1
    }
}
