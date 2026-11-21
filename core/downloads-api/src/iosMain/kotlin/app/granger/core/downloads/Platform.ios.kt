package app.granger.core.downloads

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSLock
import platform.Foundation.NSNumber
import platform.Foundation.timeIntervalSince1970

actual class QueueLock actual constructor() {
    private val lock = NSLock()
    actual fun <T> with(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }
}

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

@OptIn(ExperimentalForeignApi::class)
actual fun freeBytesAt(path: String): Long {
    var p: String? = path
    val fm = NSFileManager.defaultManager
    while (p != null && !fm.fileExistsAtPath(p)) p = Files.parent(p)
    val attrs = fm.attributesOfFileSystemForPath(p ?: "/", null) ?: return 0
    return (attrs[NSFileSystemFreeSize] as? NSNumber)?.longLongValue ?: 0
}

/** The iPhone's download records at [path], on the bundled SQLite, with Android's migrations. */
fun openDownloadDatabase(path: String): DownloadDatabase =
    Room.databaseBuilder<DownloadDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(DownloadDatabase.MIGRATION_3_4)
        .build()
