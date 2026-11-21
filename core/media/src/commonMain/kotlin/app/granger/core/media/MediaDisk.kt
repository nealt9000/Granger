package app.granger.core.media

/*
 * What the shared media rules need from each phone's files (Plan I6 Task 1). Android's are the java.io calls the
 * downloaded-media scanner and the manifests made before they were shared, one for one, so nothing changes on the phone;
 * the iPhone's go through kotlinx-io and Foundation. Paths are absolute strings with '/' between folders. Public for the
 * media downloader in core:screens (its item folders, Plan I6 Task 3).
 */
expect object MediaDisk {
    /** `File(path).exists()`. */
    fun exists(path: String): Boolean

    /** `File(path).isFile`. */
    fun isFile(path: String): Boolean

    /** `File(path).isDirectory`. */
    fun isDirectory(path: String): Boolean

    /** `File(path).length()`: 0 when it isn't there. */
    fun length(path: String): Long

    /** `File(path).lastModified()`: 0 when it isn't there. */
    fun lastModified(path: String): Long

    /** `File(dir).listFiles()`, as paths: null when [dir] can't be listed. */
    fun list(dir: String): List<String>?

    /** `File(path).canonicalPath`. Throws `IOException`. */
    fun canonical(path: String): String

    /** `File(dir, name).path`. */
    fun child(dir: String, name: String): String

    /** `File(path).readText()` (UTF-8). Throws `IOException`. */
    fun readText(path: String): String

    /** `File(path).writeBytes(bytes)`. Throws `IOException`. */
    fun writeBytes(path: String, bytes: ByteArray)

    /**
     * Makes [dir], writes [bytes] to a temp file in it, syncs it, then renames it over [dir]/[name]: a reader sees the old
     * file or the new one. Throws `IOException` (the temp file is gone either way).
     */
    fun writeReplacing(dir: String, name: String, bytes: ByteArray)

    /** `File(from).renameTo(File(to))`. */
    fun renameTo(from: String, to: String): Boolean

    /** `File(path).delete()`. */
    fun delete(path: String): Boolean

    /** The file's uri as the library stores it (Android: `Uri.fromFile(File(path)).toString()`). */
    fun fileUri(path: String): String

    /** A line in the phone's log under "Granger". */
    fun logWarn(message: String)

    /** `File(path).parent`. */
    fun parent(path: String): String?

    /** `Files.isSymbolicLink`. */
    fun isLink(path: String): Boolean

    /** `File(path).createNewFile()`: an empty file, when none is there. Throws `IOException`. */
    fun createNewFile(path: String): Boolean

    /** `File(path).deleteRecursively()`. */
    fun deleteRecursively(path: String): Boolean

    /**
     * Deletes [path] and what's in it, best effort (what can't be deleted stays); a symlink, [path] included, is deleted
     * as a link, never followed.
     */
    fun deleteTree(path: String)
}

/** The file's name, as `java.io.File.name` gives it. */
internal fun MediaDisk.nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')
