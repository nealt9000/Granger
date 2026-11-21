package app.granger.core.media

import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

// The java.io calls the scanner and the manifests made before Plan I6, one for one.
actual object MediaDisk {
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

    actual fun parent(path: String): String? = File(path).parent
    actual fun isLink(path: String): Boolean = Files.isSymbolicLink(File(path).toPath())
    actual fun createNewFile(path: String): Boolean = File(path).createNewFile()
    actual fun deleteRecursively(path: String): Boolean = File(path).deleteRecursively()

    actual fun deleteTree(path: String) {
        val root: Path = File(path).toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                runCatching { Files.deleteIfExists(file) }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                runCatching { Files.deleteIfExists(dir) }
                return FileVisitResult.CONTINUE
            }
        })
    }
}
