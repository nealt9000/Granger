@file:OptIn(ExperimentalForeignApi::class)

package app.granger.core.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeSymbolicLink
import platform.Foundation.NSLog
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.posix.fsync
import platform.posix.open
import platform.posix.close
import platform.posix.O_RDONLY

// The media rules' file calls on the iPhone (Plan I6 Task 1), through kotlinx-io and Foundation, as java.io.File
// answers them on Android.
actual object MediaDisk {
    private fun meta(path: String) = try { SystemFileSystem.metadataOrNull(Path(path)) } catch (_: IOException) { null }

    actual fun exists(path: String): Boolean = meta(path) != null
    actual fun isFile(path: String): Boolean = meta(path)?.isRegularFile == true
    actual fun isDirectory(path: String): Boolean = meta(path)?.isDirectory == true
    actual fun length(path: String): Long = meta(path)?.takeIf { it.isRegularFile }?.size ?: 0L

    actual fun lastModified(path: String): Long {
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return 0L
        val date = attributes[NSFileModificationDate] as? NSDate ?: return 0L
        return (date.timeIntervalSince1970 * 1000).toLong()
    }

    actual fun list(dir: String): List<String>? =
        if (meta(dir)?.isDirectory != true) null
        else try { SystemFileSystem.list(Path(dir)).map { child(dir, it.name) } } catch (_: IOException) { null }

    actual fun canonical(path: String): String = SystemFileSystem.resolve(Path(path)).toString()

    actual fun child(dir: String, name: String): String = normal(if (dir.isEmpty()) name else "$dir/$name")

    actual fun readText(path: String): String = SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }.decodeToString()

    actual fun writeBytes(path: String, bytes: ByteArray) = SystemFileSystem.sink(Path(path)).buffered().use { it.write(bytes) }

    actual fun writeReplacing(dir: String, name: String, bytes: ByteArray) {
        SystemFileSystem.createDirectories(Path(dir))
        val tmp = child(dir, "$name.${platform.Foundation.NSUUID().UUIDString}.tmp")
        try {
            writeBytes(tmp, bytes)
            val fd = open(tmp, O_RDONLY)
            if (fd >= 0) {
                fsync(fd)
                close(fd)
            }
            SystemFileSystem.atomicMove(Path(tmp), Path(child(dir, name)))
        } finally {
            SystemFileSystem.delete(Path(tmp), mustExist = false)
        }
    }

    actual fun renameTo(from: String, to: String): Boolean = try {
        SystemFileSystem.atomicMove(Path(from), Path(to))
        true
    } catch (_: IOException) {
        false
    }

    actual fun delete(path: String): Boolean {
        if (meta(path) == null) return false
        return try {
            SystemFileSystem.delete(Path(path), mustExist = true)
            true
        } catch (_: IOException) {
            false
        }
    }

    actual fun fileUri(path: String): String = (NSURL.fileURLWithPath(path).absoluteString ?: "file://$path").trimEnd('/')

    // The whole line is NSLog's format, its % doubled (a Kotlin String through C varargs as %@ crashes).
    actual fun logWarn(message: String) {
        NSLog("Granger: $message".replace("%", "%%"))
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

    actual fun isLink(path: String): Boolean =
        NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileType) == NSFileTypeSymbolicLink

    actual fun createNewFile(path: String): Boolean {
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) return false
        if (!NSFileManager.defaultManager.createFileAtPath(path, null, null)) throw IOException("Couldn't create $path")
        return true
    }

    actual fun deleteRecursively(path: String): Boolean =
        !NSFileManager.defaultManager.fileExistsAtPath(path) || NSFileManager.defaultManager.removeItemAtPath(path, null)

    // removeItemAtPath deletes a symlink as a link, never what it points to.
    actual fun deleteTree(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }

    /** As java.io.File normalises a path: no doubled or trailing '/'. */
    private fun normal(path: String): String {
        val collapsed = path.replace(Regex("/+"), "/")
        return if (collapsed.length > 1) collapsed.trimEnd('/') else collapsed
    }
}
