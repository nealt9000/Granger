package app.granger.core.downloads

import android.content.Context
import androidx.room.Room
import java.io.File

actual class QueueLock actual constructor() {
    actual fun <T> with(block: () -> T): T = synchronized(this, block)
}

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun freeBytesAt(path: String): Long {
    var f: File? = File(path)
    while (f != null && !f.exists()) f = f.parentFile
    return f?.usableSpace ?: 0L
}

/** The phone's download records (`downloads.db`), in Room's framework-SQLite mode as before Plan I4. */
fun DownloadDatabase.Companion.create(context: Context): DownloadDatabase =
    Room.databaseBuilder(context, DownloadDatabase::class.java, "downloads.db").addMigrations(DownloadDatabase.MIGRATION_3_4).build()
