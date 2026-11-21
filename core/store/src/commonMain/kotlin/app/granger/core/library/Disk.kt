package app.granger.core.library

import kotlinx.io.Sink
import kotlinx.io.Source

/*
 * What the book shelf needs from each phone's files (Plan I5 Task 1). Android's are the java.io.File and java.nio calls
 * the shelf made before it was shared, one for one, so nothing changes on the phone; the iPhone's go through kotlinx-io
 * and Foundation. Paths are absolute strings with '/' between folders on both phones.
 */
internal expect object Disk {
    /** `File(path).exists()`. */
    fun exists(path: String): Boolean

    /** `File(path).isFile`. */
    fun isFile(path: String): Boolean

    /** `File(path).length()`: 0 when it isn't there. */
    fun length(path: String): Long

    /** `File(path).delete()`: false when it isn't there or can't be deleted. Not recursive. */
    fun delete(path: String): Boolean

    /** `File(path).mkdirs()`. */
    fun mkdirs(path: String)

    /** `File(dir).listFiles().orEmpty()`, as paths: empty when [dir] isn't a folder. */
    fun list(dir: String): List<String>

    /** The whole file. Throws `IOException`. */
    fun readBytes(path: String): ByteArray

    /** Up to [max] bytes from the start, in one read (as `InputStream.read(buf)` gives them). Throws `IOException`. */
    fun readHead(path: String, max: Int): ByteArray

    /** Writes [bytes] to [path], replacing it. Throws `IOException`. */
    fun writeBytes(path: String, bytes: ByteArray)

    /** The file at [path] to read from. Throws `IOException`. */
    fun source(path: String): Source

    /** A new file at [path] to write into (replacing any). Throws `IOException`. */
    fun sink(path: String): Sink

    /** `Files.move(from, to, REPLACE_EXISTING)`. Throws `IOException`. */
    fun replace(from: String, to: String)

    /** `File(from).renameTo(File(to))`. */
    fun renameTo(from: String, to: String): Boolean

    /** `File(from).copyTo(File(to), overwrite = true)`. Throws `IOException`. */
    fun copyOver(from: String, to: String)

    /** `File(path).absolutePath`. */
    fun absolute(path: String): String

    /** `File(path).parentFile?.path`. */
    fun parent(path: String): String?

    /** `File(dir, name).path`. */
    fun child(dir: String, name: String): String

    /** `File(a).absoluteFile == File(b).absoluteFile`. */
    fun same(a: String, b: String): Boolean

    /** `File(dir).usableSpace`: the bytes free for this app on [dir]'s volume. */
    fun freeBytes(dir: String): Long
}

/** The file's name, as `java.io.File.name` gives it. */
internal fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

/** The file's name without its last extension, as `java.io.File.nameWithoutExtension` gives it. */
internal fun stemOf(path: String): String = nameOf(path).substringBeforeLast(".")

/** The file's extension, as `java.io.File.extension` gives it. */
internal fun extensionOf(path: String): String = nameOf(path).substringAfterLast('.', "")

/** SHA-256 over bytes fed in pieces (Android: `MessageDigest`; iOS: CommonCrypto). */
internal expect class Sha256() {
    fun update(bytes: ByteArray, offset: Int, length: Int)

    /** The digest, in lower-case hex; the hasher can't be fed after. */
    fun hex(): String
}

/** Reads [input] to the end, copying it to [out] if given; returns its sha256 in hex. */
internal fun sha256(input: Source, out: Sink? = null): String {
    val digest = Sha256()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = input.readAtMostTo(buffer, 0, buffer.size)
        if (n < 0) break
        if (n == 0) continue
        digest.update(buffer, 0, n)
        out?.write(buffer, 0, n)
    }
    return digest.hex()
}

/** Now, in milliseconds since 1970 (Android: `System.currentTimeMillis()`). */
expect fun epochMillis(): Long

/** A random UUID in the usual lower-case form (Android: `UUID.randomUUID().toString()`). */
expect fun randomId(): String

/** Logs to the phone's log under "Granger" (Android: `android.util.Log`). */
internal expect fun logInfo(message: String)

internal expect fun logWarn(message: String, error: Throwable? = null)
