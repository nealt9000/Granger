package app.granger.core.library

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

actual suspend fun <R> RoomDatabase.inTransaction(block: suspend () -> R): R = withTransaction(block)

/** The phone's library database (`library.db`), in Room's framework-SQLite mode as before Plan I3. */
fun LibraryDatabase.Companion.create(context: Context): LibraryDatabase =
    Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
        .addMigrations(*MIGRATIONS)
        .addCallback(SeedCallback)
        .build()

actual class TryLock actual constructor() {
    private val lock = java.util.concurrent.locks.ReentrantLock()
    actual fun tryLock(): Boolean = lock.tryLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
}

/**
 * The framework SQLite, opened read-only as before Plan I3: `OPEN_READONLY` so nothing can write, and
 * `NO_LOCALIZED_COLLATORS` so the framework doesn't create its `android_metadata` table on open. Opening read-write
 * (as Plan I3 first did, with `query_only` after) let that table be written into every sidecar on first read: the
 * file grew a page, left a journal and no longer matched its checksum, so Outfitting called the pack incomplete.
 * A missing file isn't created: it's checked first.
 */
internal actual fun openReadOnlyConnection(path: String): androidx.sqlite.SQLiteConnection {
    require(java.io.File(path).isFile) { "no such file: $path" }
    val flags = android.database.sqlite.SQLiteDatabase.OPEN_READONLY or android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS
    return androidx.sqlite.driver.AndroidSQLiteConnection(android.database.sqlite.SQLiteDatabase.openDatabase(path, null, flags))
}

internal actual fun openWritableConnection(path: String): androidx.sqlite.SQLiteConnection =
    androidx.sqlite.driver.AndroidSQLiteDriver().open(path)
