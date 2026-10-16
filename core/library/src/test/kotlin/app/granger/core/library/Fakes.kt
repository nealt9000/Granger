package app.granger.core.library

import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimHit
import app.granger.core.zim.ZimItem
import app.granger.core.zim.ZimMetadata
import app.granger.core.zim.ZimOpenResult
import app.granger.core.zim.ZimOpener
import java.io.File

class FakeZimArchive(
    override val uuid: String,
    title: String = uuid,
    private val paths: Set<String> = setOf("index"),
    private val redirects: Map<String, String> = emptyMap(),
    override val mainPath: String? = "index",
    override val hasFulltextIndex: Boolean = true,
    var suggestions: List<ZimHit> = emptyList(),
    var results: List<ZimHit> = emptyList(),
    var failSearch: Boolean = false,
    name: String = uuid,
) : ZimArchive {
    override val metadata = ZimMetadata(title, "About $title", "eng", "2026-10-15", name)
    var closed = false

    override fun resolvePath(path: String) = (redirects[path] ?: path).takeIf { it in paths }
    override fun read(path: String) = resolvePath(path)?.let { ZimItem(it, "text/html", ByteArray(0)) }

    override fun suggest(query: String, limit: Int): List<ZimHit> {
        if (failSearch) error("native failure")
        return suggestions.take(limit)
    }

    override fun search(query: String, limit: Int): List<ZimHit> {
        if (failSearch) error("native failure")
        return results.take(limit)
    }

    override fun close() { closed = true }
}

/** Opens files by name: a name mapped to an archive factory opens; any other name fails. */
class FakeZimOpener(val archives: MutableMap<String, () -> FakeZimArchive> = mutableMapOf()) : ZimOpener {
    val opened = mutableListOf<FakeZimArchive>()

    override fun open(file: File): ZimOpenResult {
        val factory = archives[file.name] ?: return ZimOpenResult.Failed("not a ZIM")
        if (!file.exists()) return ZimOpenResult.Failed("missing")
        return ZimOpenResult.Opened(factory().also { opened += it })
    }
}
