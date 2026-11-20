package app.granger.core.zim

data class ZimMetadata(
    val title: String,
    val description: String,
    val language: String,
    val date: String,
    val name: String,
    /** The tool that made the ZIM, its `Scraper` metadata ("gutenberg2zim-3.0.1", "nautiluszim 1.1.1"); blank if unsaid. */
    val scraper: String = "",
    /**
     * Its `Counter` metadata, entries per MIME type ("text/html=12;application/pdf=40"); blank if unsaid. It tells a pure
     * container of books from a website (Plan KB2 Task 2.2).
     */
    val counter: String = "",
)

/** One entry's content after following redirects; [path] is the final entry's path. */
class ZimItem(val path: String, val mimeType: String, val bytes: ByteArray)

/** What an entry is, without its content: for a PDF that may be hundreds of MB (Plan P2). */
data class ZimEntry(val path: String, val title: String, val mimeType: String, val sizeBytes: Long)

/**
 * A title suggestion or full-text result. Only full-text results carry a [snippet] (HTML with `<b>`
 * highlights) and a [score].
 */
data class ZimHit(val path: String, val title: String, val snippet: String? = null, val score: Int = 0)

/**
 * An open ZIM file (or Wikipedia read online, Plan O1), safe to use from any thread. File-only reads (a stored entry's
 * range, a streamed copy to a file) are on Android's `FileZimArchive` (`:core:zim`). Methods return null or an empty list instead of
 * throwing, including after [close], so a damaged or removed pack never crashes its caller.
 */
interface ZimArchive : AutoCloseable {
    val uuid: String
    val metadata: ZimMetadata
    val mainPath: String?
    val hasFulltextIndex: Boolean

    /**
     * Read over the network rather than from a file (Plan O1: Wikipedia online). Asking what a path is must then cost
     * nothing ([entry] answers from the path alone), so the reader's link check never fetches on the main thread.
     */
    val remote: Boolean get() = false

    /** The path [path] ends up at after following redirects, or null when there is no such entry. */
    fun resolvePath(path: String): String?

    fun read(path: String): ZimItem?

    /** [path]'s entry after redirects, without reading its content; null when there is no such entry. */
    fun entry(path: String): ZimEntry? = read(path)?.let { ZimEntry(it.path, it.path.substringAfterLast('/'), it.mimeType, it.bytes.size.toLong()) }

    /**
     * Calls [each] with every entry whose MIME type is exactly one of [mimes] ("application/pdf",
     * "application/epub+zip"), in path order, redirects skipped; [each] returns false to stop early. Only the entries'
     * directory is read, never their content (Plan KB2 Task 0.2: a book pack's files when it has no manifest).
     *
     * A callback rather than a list or a [Sequence]: a pack like Gutenberg's `en_all` holds about 60k EPUBs, so nothing
     * is gathered here, and the native iterator lives only inside this call, disposed before it returns, under the same
     * lock as the other reads. A lazy [Sequence] would have to keep that iterator, and the lock, open between the
     * caller's steps, and a caller that stopped early would leave it to a finalizer, which is how an earlier native
     * object crashed (Plan R5 Task 1). [each] runs on the calling thread while the archive is held open: it may read
     * this archive, but [close] waits until the walk ends.
     *
     * Returns false when the archive can't be listed: this default (an archive with no directory to walk), a closed
     * archive, or a native error part way; true when the walk reached the end or [each] stopped it.
     */
    fun list(mimes: Set<String>, each: (ZimEntry) -> Boolean): Boolean = false

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
