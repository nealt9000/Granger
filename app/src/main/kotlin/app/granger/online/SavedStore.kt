package app.granger.online

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.security.MessageDigest

/**
 * Saved for now (online/offline spec §3, §5; Plan O2 Task 1): what was read online (prepared pages, pictures,
 * summaries, books opened without keeping), kept so Back is instant and it still opens with no signal, removed oldest-
 * opened first once the store passes its cap. Never counted as offline coverage: it's a cache, not a choice.
 *
 * Files under [dir] by the SHA-1 of their key, with a small SQLite index beside them. In the app's files, not its cache,
 * so Android doesn't clear it behind the reader's back (builder's call 2). Blocking; safe from several threads.
 */
class SavedStore(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    enum class Kind { PAGE, PICTURE, SUMMARY, BOOK }

    class Saved(val bytes: ByteArray, val mime: String, val savedAt: Long)

    private val lock = Any()
    private val db: SQLiteDatabase? by lazy { open() }
    /** Bytes in the store, kept in step so a put needn't sum the index. */
    @Volatile private var total: Long = -1

    private fun open(): SQLiteDatabase? = runCatching {
        dir.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(File(dir, "index.db"), null).also {
            it.execSQL("CREATE TABLE IF NOT EXISTS saved(k TEXT PRIMARY KEY, kind TEXT NOT NULL, mime TEXT NOT NULL, bytes INTEGER NOT NULL, savedAt INTEGER NOT NULL, openedAt INTEGER NOT NULL)")
            it.execSQL("CREATE INDEX IF NOT EXISTS saved_opened ON saved(openedAt)")
        }
    }.getOrNull()

    fun put(key: String, kind: Kind, bytes: ByteArray, mime: String) = synchronized(lock) {
        val d = db ?: return@synchronized
        val file = fileOf(key)
        runCatching { file.writeBytes(bytes) }.onFailure { return@synchronized }
        val old = sizeOf(key)
        val now = clock()
        d.insertWithOnConflict("saved", null, ContentValues().apply {
            put("k", key); put("kind", kind.name); put("mime", mime); put("bytes", bytes.size.toLong()); put("savedAt", now); put("openedAt", now)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (total >= 0) total += bytes.size - old
    }

    /** [file] moved into the store under [key] (a book opened without keeping); true when it's in. */
    fun putFile(key: String, kind: Kind, file: File, mime: String): Boolean = synchronized(lock) {
        val d = db ?: return@synchronized false
        val to = fileOf(key)
        if (!file.renameTo(to) && runCatching { file.copyTo(to, overwrite = true); file.delete() }.isFailure) return@synchronized false
        val old = sizeOf(key)
        val now = clock()
        d.insertWithOnConflict("saved", null, ContentValues().apply {
            put("k", key); put("kind", kind.name); put("mime", mime); put("bytes", to.length()); put("savedAt", now); put("openedAt", now)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (total >= 0) total += to.length() - old
        true
    }

    /** The saved bytes for [key], marked as opened now; null when there are none. */
    fun get(key: String): Saved? = synchronized(lock) {
        val d = db ?: return@synchronized null
        val row = d.rawQuery("SELECT mime, savedAt FROM saved WHERE k = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null }
            ?: return@synchronized null
        val bytes = runCatching { fileOf(key).readBytes() }.getOrNull() ?: run { forget(key); return@synchronized null }
        d.execSQL("UPDATE saved SET openedAt = ? WHERE k = ?", arrayOf<Any>(clock(), key))
        Saved(bytes, row.first, row.second)
    }

    /** The file behind [key] (a saved book, opened in place), marked as opened now; null when it isn't saved. */
    fun file(key: String): File? = synchronized(lock) {
        if (!has(key)) return@synchronized null
        db?.execSQL("UPDATE saved SET openedAt = ? WHERE k = ?", arrayOf<Any>(clock(), key))
        fileOf(key).takeIf { it.isFile }
    }

    /** The saved size of [key] in bytes; null when it isn't saved. */
    fun bytesOf(key: String): Long? = synchronized(lock) {
        db?.rawQuery("SELECT bytes FROM saved WHERE k = ?", arrayOf(key))?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    fun has(key: String): Boolean = synchronized(lock) {
        db?.rawQuery("SELECT 1 FROM saved WHERE k = ?", arrayOf(key))?.use { it.moveToFirst() } == true
    }

    /** Takes [key] out of the store, its file with it (Keep moves a saved book onto the shelf). */
    fun remove(key: String) = synchronized(lock) { forget(key) }

    /** Bytes in the store. */
    fun size(): Long = synchronized(lock) {
        if (total < 0) total = db?.rawQuery("SELECT COALESCE(SUM(bytes), 0) FROM saved", null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
        total
    }

    /** Removes the oldest-opened until the store is at most [capBytes]. */
    fun trim(capBytes: Long) = synchronized(lock) {
        val d = db ?: return@synchronized
        if (size() <= capBytes) return@synchronized
        val gone = ArrayList<String>()
        var left = size()
        d.rawQuery("SELECT k, bytes FROM saved ORDER BY openedAt ASC", null).use { c ->
            while (left > capBytes && c.moveToNext()) { gone += c.getString(0); left -= c.getLong(1) }
        }
        for (k in gone) forget(k)
    }

    fun clear() = synchronized(lock) {
        db?.execSQL("DELETE FROM saved")
        dir.listFiles()?.filter { it.name != "index.db" && !it.name.startsWith("index.db-") }?.forEach { it.delete() }
        total = 0
    }

    private fun forget(key: String) {
        val old = sizeOf(key)
        db?.execSQL("DELETE FROM saved WHERE k = ?", arrayOf(key))
        fileOf(key).delete()
        if (total >= 0) total -= old
    }

    private fun sizeOf(key: String): Long =
        db?.rawQuery("SELECT bytes FROM saved WHERE k = ?", arrayOf(key))?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L

    private fun fileOf(key: String) = File(dir, sha1(key))

    companion object {
        /** A saved page under this age opens at once even online (builder's call 2); older ones are fetched fresh. */
        const val FRESH_MS = 60L * 60 * 1000

        fun pageKey(lang: String, title: String) = "$lang:page:$title"
        fun summaryKey(lang: String, title: String) = "$lang:sum:$title"
        fun pictureKey(path: String) = "img:$path"
        fun bookKey(bookId: String) = "book:$bookId"

        internal fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
