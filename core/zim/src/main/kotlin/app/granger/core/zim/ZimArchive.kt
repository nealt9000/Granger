package app.granger.core.zim

import java.io.Closeable
import java.io.File

data class ZimMetadata(
    val title: String,
    val description: String,
    val language: String,
    val date: String,
    val name: String,
)

/** One entry's content after following redirects; [path] is the final entry's path. */
class ZimItem(val path: String, val mimeType: String, val bytes: ByteArray)

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

    /** The path of a random article, or null when the pack has none (or is closed). */
    fun randomPath(): String?

    fun suggest(query: String, limit: Int): List<ZimHit>

    /** Full-text search; empty when the pack has no full-text index. */
    fun search(query: String, limit: Int): List<ZimHit>

    override fun close()
}

sealed interface ZimOpenResult {
    class Opened(val archive: ZimArchive) : ZimOpenResult
    data class Failed(val reason: String) : ZimOpenResult
}

fun interface ZimOpener {
    fun open(file: File): ZimOpenResult
}
