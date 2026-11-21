package app.granger.core.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.execSQL
import kotlin.concurrent.Volatile

/** An installed Classic's links file (its [path]) and the sha256 of the EPUB it must have been built from. */
data class LinksFile(val path: String, val sourceSha256: String)

/**
 * Every installed Classic's reference links in one local file (Plan LL1 Task 9, Neal 2026-11-13: a whole Classics
 * collection is 622 books, too many files to open for each article). An article's footer is then one indexed query
 * on its QID. [sync] keeps it to the installed `.glink` files, copying a new or changed book in with SQLite's ATTACH and
 * dropping a removed one, so only the first sync after installing a collection does real work. Blocking; call on IO.
 *
 * Shared by both phones since Plan I5: one writable connection to [path] (Android: the framework SQLite, opened as
 * `SQLiteDatabase.openOrCreateDatabase` opened it before).
 */
class MentionIndex(private val path: String) : AutoCloseable {
    /** Held by each call. Public for Android's test that holds it as a long sync would. */
    val lock = TryLock()
    private var db: SQLiteConnection? = null
    private var synced: Map<String, LinksFile>? = null

    /** [works] as the last finished sync left it, for [worksNow] while another sync runs. */
    @Volatile private var lastWorks: Map<String, Long>? = null

    private fun db(): SQLiteConnection = db ?: run {
        Disk.parent(path)?.let { Disk.mkdirs(it) }
        var d = openWritableConnection(path)
        if (d.version() != VERSION) {
            d.close()
            Disk.delete(path)
            Disk.delete("$path-journal")
            d = openWritableConnection(path)
            d.execSQL("CREATE TABLE books(book_id TEXT PRIMARY KEY, file TEXT NOT NULL, sha TEXT NOT NULL, work INTEGER)")
            d.execSQL(
                "CREATE TABLE mentions(book_id TEXT NOT NULL, qid INTEGER NOT NULL, href TEXT NOT NULL, start INTEGER NOT NULL, " +
                    "quote TEXT NOT NULL, before TEXT NOT NULL, tier INTEGER NOT NULL)",
            )
            d.execSQL("CREATE INDEX mentions_qid ON mentions(qid, book_id)")
            d.execSQL("PRAGMA user_version = $VERSION")
        }
        d.also { db = it }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }

    /**
     * Makes the index hold exactly [files] (bookshelf id → links file): removed books and changed files out, new ones
     * in. A file that isn't a links file, or was built from another EPUB, is left out. Returns how many books it copied.
     */
    fun sync(files: Map<String, LinksFile>): Int = locked {
        if (files == synced) return 0
        val d = db()
        val held = d.query("SELECT book_id, file, sha FROM books") { st ->
            buildMap { while (st.step()) put(st.getText(0), LinksFile(st.getText(1), st.getText(2))) }
        }
        val stale = held.filter { (id, f) -> files[id] != f }.keys
        if (stale.isNotEmpty()) {
            d.transaction {
                for (id in stale) {
                    d.exec("DELETE FROM mentions WHERE book_id = ?", id)
                    d.exec("DELETE FROM books WHERE book_id = ?", id)
                }
            }
        }
        var copied = 0
        for ((id, f) in files) {
            if (held[id] == f) continue
            val work = BookLinks.open(f.path, f.sourceSha256)?.use { Result.success(it.workQid) } ?: continue
            if (copy(d, id, f, work.getOrNull())) copied++
        }
        synced = files
        lastWorks = queryWorks(d)
        copied
    }

    private fun copy(d: SQLiteConnection, id: String, f: LinksFile, work: Long?): Boolean = try {
        d.exec("ATTACH DATABASE ? AS g", f.path)
        try {
            d.transaction {
                d.exec("INSERT INTO mentions SELECT ?, qid, href, start, quote, before, tier FROM g.mentions", id)
                d.exec("INSERT INTO books VALUES (?, ?, ?, ?)", id, f.path, f.sourceSha256, work)
            }
            true
        } finally {
            d.execSQL("DETACH DATABASE g")
        }
    } catch (e: SQLiteException) {
        false
    }

    /** Each indexed book's Wikidata work, by bookshelf id; books with none are left out. */
    fun works(): Map<String, Long> = locked { queryWorks(db()) }

    /**
     * [works] without waiting: while a sync is copying books (seconds, the first time after a collection arrives), the
     * works as the last finished sync left them, or none before the first. For a link's card, which mustn't wait.
     */
    fun worksNow(): Map<String, Long> {
        if (!lock.tryLock()) return lastWorks.orEmpty()
        return try {
            queryWorks(db())
        } finally {
            lock.unlock()
        }
    }

    private fun queryWorks(d: SQLiteConnection): Map<String, Long> =
        d.query("SELECT book_id, work FROM books WHERE work IS NOT NULL") { st ->
            buildMap { while (st.step()) put(st.getText(0), st.getLong(1)) }
        }

    /** The books that mention [qid] and how often, most first. */
    fun counts(qid: Long): List<Pair<String, Int>> = locked {
        db().query(
            "SELECT book_id, COUNT(*) AS n FROM mentions WHERE qid = ? GROUP BY book_id ORDER BY n DESC, book_id",
            qid.toString(),
        ) { st -> buildList { while (st.step()) add(st.getText(0) to st.getLong(1).toInt()) } }
    }

    /** [bookId]'s first [limit] mentions of [qid], in book order. */
    fun passages(bookId: String, qid: Long, limit: Int): List<BookMention> = locked {
        db().query(
            "SELECT href, start, quote, before, qid, tier FROM mentions WHERE qid = ? AND book_id = ? ORDER BY href, start LIMIT ?",
            qid.toString(), bookId, limit.toString(),
        ) { st ->
            buildList {
                while (st.step()) {
                    add(BookMention(st.getText(0), st.getLong(1).toInt(), st.getText(2), st.getText(3), st.getLong(4), st.getLong(5).toInt()))
                }
            }
        }
    }

    override fun close() = locked {
        db?.close()
        db = null
        synced = null
        lastWorks = null
    }

    private companion object {
        const val VERSION = 1
    }
}

/** The file's `PRAGMA user_version` (Android's `SQLiteDatabase.version`). */
private fun SQLiteConnection.version(): Int = query("PRAGMA user_version") { if (it.step()) it.getLong(0).toInt() else 0 }

/** Runs [sql] with [args] bound as text (as Android's `rawQuery` bound them), reading its rows with [read]. */
private fun <T> SQLiteConnection.query(sql: String, vararg args: String, read: (androidx.sqlite.SQLiteStatement) -> T): T =
    prepare(sql).use { st ->
        args.forEachIndexed { i, a -> st.bindText(i + 1, a) }
        read(st)
    }

/** Runs [sql] with [args] bound by type (as Android's `execSQL(sql, args)` bound them). */
private fun SQLiteConnection.exec(sql: String, vararg args: Any?) {
    prepare(sql).use { st ->
        args.forEachIndexed { i, a ->
            when (a) {
                null -> st.bindNull(i + 1)
                is Long -> st.bindLong(i + 1, a)
                is Int -> st.bindLong(i + 1, a.toLong())
                else -> st.bindText(i + 1, a.toString())
            }
        }
        st.step()
    }
}

/** [block] in one transaction: committed when it returns, rolled back when it throws. */
private inline fun SQLiteConnection.transaction(block: () -> Unit) {
    execSQL("BEGIN IMMEDIATE TRANSACTION")
    try {
        block()
        execSQL("END TRANSACTION")
    } catch (e: Throwable) {
        execSQL("ROLLBACK TRANSACTION")
        throw e
    }
}
