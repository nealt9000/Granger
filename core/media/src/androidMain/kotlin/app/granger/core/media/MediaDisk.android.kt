package app.granger.core.media

import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// The java.io calls the scanner and the manifests made before Plan I6, one for one.
internal actual object MediaDisk {
    actual fun exists(path: String): Boolean = File(path).exists()
    actual fun isFile(path: String): Boolean = File(path).isFile
    actual fun isDirectory(path: String): Boolean = File(path).isDirectory
    actual fun length(path: String): Long = File(path).length()
    actual fun lastModified(path: String): Long = File(path).lastModified()
    actual fun list(dir: String): List<String>? = File(dir).listFiles()?.map { it.path }
    actual fun canonical(path: String): String = File(path).canonicalPath
    actual fun child(dir: String, name: String): String = File(dir, name).path
    actual fun readText(path: String): String = File(path).readText()
    actual fun writeBytes(path: String, bytes: ByteArray) = File(path).writeBytes(bytes)

    actual fun writeReplacing(dir: String, name: String, bytes: ByteArray) {
        val folder = File(dir)
        folder.mkdirs()
        val tmp = File.createTempFile("$name.", ".tmp", folder)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!tmp.renameTo(File(folder, name))) throw IOException("Couldn't replace ${File(folder, name)}")
        } finally {
            tmp.delete()
        }
    }

    actual fun renameTo(from: String, to: String): Boolean = File(from).renameTo(File(to))
    actual fun delete(path: String): Boolean = File(path).delete()
    actual fun fileUri(path: String): String = Uri.fromFile(File(path)).toString()

    actual fun logWarn(message: String) {
        runCatching { android.util.Log.w("Granger", message) }
    }
}
