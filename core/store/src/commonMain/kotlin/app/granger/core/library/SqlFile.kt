package app.granger.core.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

/**
 * A sidecar SQLite file (`.gqid`, `.gtime`) opened read-only on both phones (Plan I3 Task 2), with the cursor-style
 * calls the readers were written against on Android (`rawQuery`, `moveToNext`, `getLong`…). Android opens it through
 * the framework SQLite, as before; iOS through the bundled one.
 *
 * Android's `SQLiteDatabase` let several threads read at once, and Explore reads its indexes in parallel. A raw
 * connection is single-threaded, so a file keeps a few connections, each behind its own lock: a query takes a free
 * one, or waits for one if all are busy.
 */
class SqlFile private constructor(private val pool: List<Pooled>) : AutoCloseable {
    private class Pooled(val connection: SQLiteConnection) {
        val lock = TryLock()
    }

    private var next = 0

    /** Runs [sql] with [args] bound as text (as Android's `rawQuery` binds them). Close the rows (`use`) to free the connection. */
    fun rawQuery(sql: String, args: Array<String>?): Rows {
        val pooled = pool.firstOrNull { it.lock.tryLock() } ?: pool[(next++).mod(pool.size)].also { it.lock.lock() }
        return try {
            val statement = pooled.connection.prepare(sql)
            args?.forEachIndexed { i, a -> statement.bindText(i + 1, a) }
            Rows(statement) { pooled.lock.unlock() }
        } catch (e: Throwable) {
            pooled.lock.unlock()
            throw e
        }
    }

    override fun close() {
        for (p in pool) {
            p.lock.lock()
            try { p.connection.close() } finally { p.lock.unlock() }
        }
    }

    companion object {
        /** [path] read-only with up to [connections] connections; null when it can't be opened. */
        fun openReadOnly(path: String, connections: Int = 3): SqlFile? = runCatching {
            val first = openReadOnlyConnection(path)
            // A file that isn't SQLite fails here, on the first read, rather than at open.
            first.prepare("SELECT count(*) FROM sqlite_master").use { it.step() }
            SqlFile(listOf(Pooled(first)) + List(connections - 1) { Pooled(openReadOnlyConnection(path)) })
        }.getOrNull()

        /** A string as an SQL literal (Android's `DatabaseUtils.sqlEscapeString`). */
        fun escape(s: String): String = "'" + s.replace("'", "''") + "'"
    }
}

/** One query's rows, read as Android's `Cursor` was: `moveToFirst`/`moveToNext`, then the column getters. */
class Rows internal constructor(private val statement: SQLiteStatement, private val release: () -> Unit) : AutoCloseable {
    private var closed = false

    fun moveToFirst(): Boolean = statement.step()
    fun moveToNext(): Boolean = statement.step()
    fun getLong(i: Int): Long = statement.getLong(i)
    fun getInt(i: Int): Int = statement.getLong(i).toInt()
    fun getDouble(i: Int): Double = statement.getDouble(i)
    fun getString(i: Int): String = statement.getText(i)
    fun isNull(i: Int): Boolean = statement.isNull(i)

    override fun close() {
        if (closed) return
        closed = true
        try { statement.close() } finally { release() }
    }
}

/** A lock that can be tried (`ReentrantLock` on Android, `NSLock` on iOS). */
expect class TryLock() {
    fun tryLock(): Boolean
    fun lock()
    fun unlock()
}

/** A read-only connection to [path] (Android: framework SQLite; iOS: bundled). */
internal expect fun openReadOnlyConnection(path: String): SQLiteConnection
