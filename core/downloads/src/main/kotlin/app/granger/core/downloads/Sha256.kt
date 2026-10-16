package app.granger.core.downloads

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal const val BUFFER_SIZE = 64 * 1024

/** Feeds [file] into [digest] and returns how many bytes it read (0 when there is no file). */
internal suspend fun hashFile(file: File, digest: MessageDigest, onBytes: (Long) -> Unit = {}): Long {
    if (!file.isFile) return 0
    var total = 0L
    FileInputStream(file).use { input ->
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
            total += n
            onBytes(total)
        }
    }
    return total
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
