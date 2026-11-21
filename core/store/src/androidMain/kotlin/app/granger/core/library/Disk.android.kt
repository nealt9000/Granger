package app.granger.core.library

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

// The java.io calls the shelf made before Plan I5, one for one.
internal actual object Disk {
    actual fun exists(path: String): Boolean = File(path).exists()
    actual fun isFile(path: String): Boolean = File(path).isFile
    actual fun length(path: String): Long = File(path).length()
    actual fun delete(path: String): Boolean = File(path).delete()
    actual fun mkdirs(path: String) {
        File(path).mkdirs()
    }
    actual fun list(dir: String): List<String> = File(dir).listFiles().orEmpty().map { it.path }
    actual fun readBytes(path: String): ByteArray = File(path).readBytes()
    actual fun readHead(path: String, max: Int): ByteArray = File(path).inputStream().use { input ->
        ByteArray(max).let { buf -> buf.copyOf(input.read(buf).coerceAtLeast(0)) }
    }
    actual fun writeBytes(path: String, bytes: ByteArray) = File(path).writeBytes(bytes)
    actual fun source(path: String): Source = FileInputStream(File(path)).asSource().buffered()
    actual fun sink(path: String): Sink = FileOutputStream(File(path)).asSink().buffered()
    actual fun replace(from: String, to: String) {
        Files.move(File(from).toPath(), File(to).toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    actual fun renameTo(from: String, to: String): Boolean = File(from).renameTo(File(to))
    actual fun copyOver(from: String, to: String) {
        File(from).copyTo(File(to), overwrite = true)
    }
    actual fun absolute(path: String): String = File(path).absolutePath
    actual fun parent(path: String): String? = File(path).parentFile?.path
    actual fun child(dir: String, name: String): String = File(dir, name).path
    actual fun same(a: String, b: String): Boolean = File(a).absoluteFile == File(b).absoluteFile
    actual fun freeBytes(dir: String): Long = File(dir).usableSpace
}

internal actual class Sha256 actual constructor() {
    private val digest = MessageDigest.getInstance("SHA-256")
    actual fun update(bytes: ByteArray, offset: Int, length: Int) = digest.update(bytes, offset, length)
    actual fun hex(): String = digest.digest().joinToString("") { "%02x".format(it) }
}

actual fun epochMillis(): Long = System.currentTimeMillis()

actual fun randomId(): String = UUID.randomUUID().toString()

internal actual fun logInfo(message: String) {
    runCatching { android.util.Log.i("Granger", message) }
}

internal actual fun logWarn(message: String, error: Throwable?) {
    runCatching { if (error == null) android.util.Log.w("Granger", message) else android.util.Log.w("Granger", message, error) }
}
