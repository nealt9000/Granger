package app.granger.core.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.granger.core.text.hex
import app.granger.core.text.sha1
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Saved for now (online/offline spec §3, §5; Plan O2 Task 1), shared since Plan I3: what was read online, kept so Back
 * is instant and it still opens with no signal, removed oldest-opened first once the store passes its cap.
 *
 * The same layout as Android's `SavedStore` (files under [dir] named by the SHA-1 of their key, beside an `index.db`
 * with one `saved` table), so Android can move onto this class without touching what's on the phone. The iPhone uses
 * it from I3; Android's switch waits for another branch that edits SavedStore (BK). Blocking; safe from several threads.
 */
class SavedForNow(private val dir: String, private val clock: () -> Long) {
    class Saved(val bytes: ByteArray, val mime: String, val savedAt: Long)

    private val lock = TryLock()
    private var connection: SQLiteConnection? = null
    private var opened = false
    private var total: Long = -1

    private fun db(): SQLiteConnection? {
        if (!opened) {
            opened = true
            connection = runCatching {
                SystemFileSystem.createDirectories(Path(dir))
                openWritableConnection(Path(dir, "index.db").toString()).also {
                    it.execSQL("CREATE TABLE IF NOT EXISTS saved(k TEXT PRIMARY KEY, kind TEXT NOT NULL, mime TEXT NOT NULL, bytes INTEGER NOT NULL, savedAt INTEGER NOT NULL, openedAt INTEGER NOT NULL)")
                    it.execSQL("CREATE INDEX IF NOT EXISTS saved_opened ON saved(openedAt)")
                }
            }.getOrNull()
        }
        return connection
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }

    fun put(key: String, kind: String, bytes: ByteArray, mime: String) = locked {
        val d = db() ?: return@locked
        runCatching { SystemFileSystem.sink(fileOf(key)).buffered().use { it.write(bytes) } }.onFailure { return@locked }
        val old = sizeOf(d, key)
        val now = clock()
        d.prepare("INSERT OR REPLACE INTO saved(k, kind, mime, bytes, savedAt, openedAt) VALUES (?, ?, ?, ?, ?, ?)").use {
            it.bindText(1, key); it.bindText(2, kind); it.bindText(3, mime); it.bindLong(4, bytes.size.toLong())
            it.bindLong(5, now); it.bindLong(6, now); it.step()
        }
        if (total >= 0) total += bytes.size - old
    }

    /** The saved bytes for [key], marked as opened now; null when there are none. */
    fun get(key: String): Saved? = locked {
        val d = db() ?: return@locked null
        val row = d.prepare("SELECT mime, savedAt FROM saved WHERE k = ?").use {
            it.bindText(1, key)
            if (it.step()) it.getText(0) to it.getLong(1) else null
        } ?: return@locked null
        val bytes = runCatching { SystemFileSystem.source(fileOf(key)).buffered().use { it.readByteArray() } }.getOrNull()
            ?: run { forget(d, key); return@locked null }
        d.prepare("UPDATE saved SET openedAt = ? WHERE k = ?").use { it.bindLong(1, clock()); it.bindText(2, key); it.step() }
        Saved(bytes, row.first, row.second)
    }

    fun has(key: String): Boolean = locked {
        val d = db() ?: return@locked false
        d.prepare("SELECT 1 FROM saved WHERE k = ?").use { it.bindText(1, key); it.step() }
    }

    fun remove(key: String) = locked { db()?.let { forget(it, key) } ?: Unit }

    /** Bytes in the store. */
    fun size(): Long = locked { sizeLocked() }

    private fun sizeLocked(): Long {
        if (total < 0) total = db()?.prepare("SELECT COALESCE(SUM(bytes), 0) FROM saved")?.use { if (it.step()) it.getLong(0) else 0L } ?: 0L
        return total
    }

    /** Removes the oldest-opened until the store is at most [capBytes]. */
    fun trim(capBytes: Long) = locked {
        val d = db() ?: return@locked
        if (sizeLocked() <= capBytes) return@locked
        val gone = ArrayList<String>()
        var left = sizeLocked()
        d.prepare("SELECT k, bytes FROM saved ORDER BY openedAt ASC").use {
            while (left > capBytes && it.step()) { gone += it.getText(0); left -= it.getLong(1) }
        }
        for (k in gone) forget(d, k)
    }

    fun clear() = locked {
        db()?.execSQL("DELETE FROM saved")
        runCatching {
            SystemFileSystem.list(Path(dir)).filter { it.name != "index.db" && !it.name.startsWith("index.db-") }
                .forEach { runCatching { SystemFileSystem.delete(it) } }
        }
        total = 0
    }

    private fun forget(d: SQLiteConnection, key: String) {
        val old = sizeOf(d, key)
        d.prepare("DELETE FROM saved WHERE k = ?").use { it.bindText(1, key); it.step() }
        runCatching { SystemFileSystem.delete(fileOf(key), mustExist = false) }
        if (total >= 0) total -= old
    }

    private fun sizeOf(d: SQLiteConnection, key: String): Long =
        d.prepare("SELECT bytes FROM saved WHERE k = ?").use { it.bindText(1, key); if (it.step()) it.getLong(0) else 0L }

    private fun fileOf(key: String) = Path(dir, sha1(key.encodeToByteArray()).hex())
}

/** A read-write connection to [path], created if missing (Android: framework SQLite; iOS: bundled). */
internal expect fun openWritableConnection(path: String): SQLiteConnection
