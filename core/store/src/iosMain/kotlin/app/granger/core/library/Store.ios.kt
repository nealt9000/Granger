package app.granger.core.library

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

actual suspend fun <R> RoomDatabase.inTransaction(block: suspend () -> R): R =
    useWriterConnection { it.immediateTransaction { block() } }

/** The iPhone's library database at [path], on the bundled SQLite, with the same migrations and seed as Android's. */
fun openLibraryDatabase(path: String): LibraryDatabase =
    Room.databaseBuilder<LibraryDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(*LibraryDatabase.MIGRATIONS)
        .addCallback(LibraryDatabase.SeedCallback)
        .build()

actual class TryLock actual constructor() {
    private val lock = platform.Foundation.NSLock()
    actual fun tryLock(): Boolean = lock.tryLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
}

internal actual fun openReadOnlyConnection(path: String): androidx.sqlite.SQLiteConnection =
    BundledSQLiteDriver().open(path, androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY)
