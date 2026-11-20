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
 * The framework SQLite (as before Plan I3). This driver version takes no open flags, so the file is opened read-write
 * (sidecars live in the app's own storage) and `query_only` makes SQLite refuse any write, as a read-only open did.
 * A missing file isn't created: it's checked first.
 */
internal actual fun openReadOnlyConnection(path: String): androidx.sqlite.SQLiteConnection {
    require(java.io.File(path).isFile) { "no such file: $path" }
    return androidx.sqlite.driver.AndroidSQLiteDriver().open(path).apply {
        prepare("PRAGMA query_only = 1").use { it.step() }
    }
}
