@file:OptIn(ExperimentalForeignApi::class)

package app.granger.core.library

import app.granger.core.text.hex
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.Foundation.NSLog
import platform.Foundation.timeIntervalSince1970

// The shelf's file calls on the iPhone, through kotlinx-io (and Foundation for free space), as java.io.File answers them.
internal actual object Disk {
    private fun meta(path: String) = try { SystemFileSystem.metadataOrNull(Path(path)) } catch (_: IOException) { null }

    actual fun exists(path: String): Boolean = meta(path) != null
    actual fun isFile(path: String): Boolean = meta(path)?.isRegularFile == true
    actual fun length(path: String): Long = meta(path)?.takeIf { it.isRegularFile }?.size ?: 0L
    actual fun lastModified(path: String): Long =
        ((NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(platform.Foundation.NSFileModificationDate) as? NSDate)
            ?.timeIntervalSince1970 ?: 0.0).times(1000).toLong()

    actual fun delete(path: String): Boolean {
        if (meta(path) == null) return false
        return try {
            SystemFileSystem.delete(Path(path), mustExist = true)
            true
        } catch (_: IOException) {
            false
        }
    }

    actual fun mkdirs(path: String) {
        try { SystemFileSystem.createDirectories(Path(path)) } catch (_: IOException) {}
    }

    actual fun list(dir: String): List<String> =
        if (meta(dir)?.isDirectory != true) emptyList()
        else try { SystemFileSystem.list(Path(dir)).map { child(dir, it.name) } } catch (_: IOException) { emptyList() }

    actual fun readBytes(path: String): ByteArray = SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }

    actual fun readHead(path: String, max: Int): ByteArray = SystemFileSystem.source(Path(path)).buffered().use { source ->
        val buf = ByteArray(max)
        var n = 0
        while (n < max) {
            val read = source.readAtMostTo(buf, n, max)
            if (read < 0) break
            n += read
        }
        buf.copyOf(n)
    }

    actual fun writeBytes(path: String, bytes: ByteArray) = sink(path).use { it.write(bytes) }

    actual fun source(path: String): Source = SystemFileSystem.source(Path(path)).buffered()

    actual fun sink(path: String): Sink = SystemFileSystem.sink(Path(path)).buffered()

    actual fun replace(from: String, to: String) {
        try {
            SystemFileSystem.atomicMove(Path(from), Path(to))
        } catch (e: IOException) {
            // Another volume: copied, then the original removed.
            copyOver(from, to)
            SystemFileSystem.delete(Path(from), mustExist = false)
        }
    }

    actual fun renameTo(from: String, to: String): Boolean = try {
        SystemFileSystem.atomicMove(Path(from), Path(to))
        true
    } catch (_: IOException) {
        false
    }

    actual fun copyOver(from: String, to: String) {
        SystemFileSystem.source(Path(from)).buffered().use { input ->
            sink(to).use { out -> input.transferTo(out) }
        }
    }

    actual fun absolute(path: String): String {
        val absolute = if (path.startsWith("/")) path else NSFileManager.defaultManager.currentDirectoryPath.trimEnd('/') + "/" + path
        return normal(absolute)
    }

    actual fun parent(path: String): String? {
        val p = normal(path)
        val at = p.lastIndexOf('/')
        return when {
            at > 0 -> p.substring(0, at)
            at == 0 && p.length > 1 -> "/"
            else -> null
        }
    }

    actual fun child(dir: String, name: String): String = normal(if (dir.isEmpty()) name else "$dir/$name")

    actual fun same(a: String, b: String): Boolean = absolute(a) == absolute(b)

    actual fun freeBytes(dir: String): Long {
        val attributes = NSFileManager.defaultManager.attributesOfFileSystemForPath(dir, null) ?: return 0L
        return (attributes[NSFileSystemFreeSize] as? NSNumber)?.longLongValue ?: 0L
    }

    /** As java.io.File normalises a path: no doubled or trailing '/'. */
    private fun normal(path: String): String {
        val collapsed = path.replace(Regex("/+"), "/")
        return if (collapsed.length > 1) collapsed.trimEnd('/') else collapsed
    }
}

internal actual class Sha256 actual constructor() {
    private val context = nativeHeap.alloc<CC_SHA256_CTX>().also { CC_SHA256_Init(it.ptr) }

    actual fun update(bytes: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        bytes.usePinned { CC_SHA256_Update(context.ptr, it.addressOf(offset), length.convert()) }
    }

    actual fun hex(): String {
        val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
        digest.usePinned { CC_SHA256_Final(it.addressOf(0), context.ptr) }
        nativeHeap.free(context)
        return digest.asByteArray().hex()
    }
}

actual fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

actual fun randomId(): String = NSUUID().UUIDString.lowercase()

// The text is NSLog's format, its % doubled: a Kotlin String passed through NSLog's C varargs as %@ isn't an NSString,
// and NSLog crashed reading it (a book's index line on the Simulator, Plan I5 Task 5).
internal actual fun logInfo(message: String) {
    NSLog("Granger: $message".replace("%", "%%"))
}

internal actual fun logWarn(message: String, error: Throwable?) {
    NSLog("Granger: $message${error?.let { " ($it)" } ?: ""}".replace("%", "%%"))
}
