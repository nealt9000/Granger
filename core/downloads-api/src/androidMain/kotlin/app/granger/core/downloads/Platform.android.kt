package app.granger.core.downloads

import android.content.Context
import androidx.room.Room
import java.io.File
import java.security.MessageDigest

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

internal actual fun sha256File(path: String, onBytes: (Long) -> Unit): String {
    val digest = MessageDigest.getInstance("SHA-256")
    File(path).inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        var read = input.read(buffer)
        var inFile = 0L
        while (read >= 0) {
            digest.update(buffer, 0, read)
            inFile += read
            onBytes(inFile)
            read = input.read(buffer)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
