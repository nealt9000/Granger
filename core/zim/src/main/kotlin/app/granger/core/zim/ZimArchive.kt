package app.granger.core.zim

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * A stored (uncompressed) entry's content as [length] bytes at [offset] in [file], the `.zim` itself: how ZIMs keep
 * media, so it can be streamed instead of read whole into memory.
 */
data class ZimRange(val path: String, val mimeType: String, val file: File, val offset: Long, val length: Long) {
    /** [count] bytes from [from] (relative to the entry). */
    fun open(from: Long = 0L, count: Long = length - from): InputStream = FileRangeInputStream(file, offset + from, count)
}

/** At most [left] bytes of [file] from [start]; the file is opened on creation and closed with the stream. */
internal class FileRangeInputStream(file: File, start: Long, private var left: Long) : InputStream() {
    private val raf = RandomAccessFile(file, "r").apply { seek(start) }

    override fun read(): Int {
        if (left <= 0) return -1
        val b = raf.read()
        if (b >= 0) left--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (left <= 0) return -1
        val n = raf.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) left -= n
        return n
    }

    override fun available(): Int = minOf(left, Int.MAX_VALUE.toLong()).toInt()

    override fun close() = raf.close()
}

/**
 * Android's file-backed archive: what a `.zim` on disk can do beyond [ZimArchive] (Plan I1 split the common part out).
 */
interface FileZimArchive : ZimArchive {
    /**
     * [path]'s content (after redirects) as a range of the `.zim` file when the entry is stored uncompressed; null
     * when it is compressed or missing, and [read] is then the way to get it.
     */
    fun storedRange(path: String): ZimRange? = null

    /**
     * Writes [path]'s content to [out], streamed where the archive allows it, never whole in memory for a large
     * stored entry. False when there is no such entry or the copy failed ([out] is then removed).
     */
    fun copyTo(path: String, out: File): Boolean = readInto(path, out)
}

/** A stored entry's range for a file-backed archive; null for any other (an online one). */
fun ZimArchive.storedRange(path: String): ZimRange? = (this as? FileZimArchive)?.storedRange(path)

/** [path]'s content written to [out]: streamed by a file-backed archive, else read whole and written. */
fun ZimArchive.copyTo(path: String, out: File): Boolean = if (this is FileZimArchive) copyTo(path, out) else readInto(path, out)

private fun ZimArchive.readInto(path: String, out: File): Boolean = try {
    read(path)?.let { out.writeBytes(it.bytes); true } ?: false
} catch (e: java.io.IOException) {
    out.delete()
    false
}

fun interface ZimOpener {
    fun open(file: File): ZimOpenResult
}
