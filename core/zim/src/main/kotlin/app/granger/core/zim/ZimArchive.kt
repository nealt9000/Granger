package app.granger.core.zim

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

data class ZimMetadata(
    val title: String,
    val description: String,
    val language: String,
    val date: String,
    val name: String,
    /** The tool that made the ZIM, its `Scraper` metadata ("gutenberg2zim-3.0.1", "nautiluszim 1.1.1"); blank if unsaid. */
    val scraper: String = "",
)

/** One entry's content after following redirects; [path] is the final entry's path. */
class ZimItem(val path: String, val mimeType: String, val bytes: ByteArray)

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

/** What an entry is, without its content: for a PDF that may be hundreds of MB (Plan P2). */
data class ZimEntry(val path: String, val title: String, val mimeType: String, val sizeBytes: Long)

/**
 * A title suggestion or full-text result. Only full-text results carry a [snippet] (HTML with `<b>`
 * highlights) and a [score].
 */
data class ZimHit(val path: String, val title: String, val snippet: String? = null, val score: Int = 0)

/**
 * An open ZIM file, safe to use from any thread. Methods return null or an empty list instead of
 * throwing, including after [close], so a damaged or removed pack never crashes its caller.
 */
interface ZimArchive : Closeable {
    val uuid: String
    val metadata: ZimMetadata
    val mainPath: String?
    val hasFulltextIndex: Boolean

    /** The path [path] ends up at after following redirects, or null when there is no such entry. */
    fun resolvePath(path: String): String?

    fun read(path: String): ZimItem?

    /**
     * [path]'s content (after redirects) as a range of the `.zim` file when the entry is stored uncompressed; null
     * when it is compressed or missing, and [read] is then the way to get it.
     */
    fun storedRange(path: String): ZimRange? = null

    /** [path]'s entry after redirects, without reading its content; null when there is no such entry. */
    fun entry(path: String): ZimEntry? = read(path)?.let { ZimEntry(it.path, it.path.substringAfterLast('/'), it.mimeType, it.bytes.size.toLong()) }

    /**
     * Writes [path]'s content to [out], streamed where the archive allows it, never whole in memory for a large
     * stored entry. False when there is no such entry or the copy failed ([out] is then removed).
     */
    fun copyTo(path: String, out: File): Boolean = try {
        read(path)?.let { out.writeBytes(it.bytes); true } ?: false
    } catch (e: java.io.IOException) {
        out.delete()
        false
    }

    /** The path of a random article, or null when the pack has none (or is closed). */
    fun randomPath(): String?

    fun suggest(query: String, limit: Int): List<ZimHit>

    /** Full-text search; empty when the pack has no full-text index. */
    fun search(query: String, limit: Int): List<ZimHit>

    /** [search] without snippets, which may each mean reading the article: for callers that only want the hits. */
    fun searchWithoutSnippets(query: String, limit: Int): List<ZimHit> = search(query, limit)

    override fun close()
}

sealed interface ZimOpenResult {
    class Opened(val archive: ZimArchive) : ZimOpenResult
    data class Failed(val reason: String) : ZimOpenResult
}

fun interface ZimOpener {
    fun open(file: File): ZimOpenResult
}
