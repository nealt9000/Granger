package app.granger.core.zim

/** Entries are path → (MIME type, body); redirects are path → target path. */
class FakeZimArchive(
    private val entries: Map<String, Pair<String, String>>,
    private val redirects: Map<String, String> = emptyMap(),
    override val uuid: String = "pack-1",
    override val mainPath: String? = "index",
    override val hasFulltextIndex: Boolean = true,
    override val metadata: ZimMetadata = ZimMetadata("Fake", "", "eng", "2026-10-15", "fake"),
) : ZimArchive {
    override fun resolvePath(path: String): String? = (redirects[path] ?: path).takeIf { it in entries }

    override fun read(path: String): ZimItem? = resolvePath(path)?.let { p ->
        val (mimeType, body) = entries.getValue(p)
        ZimItem(p, mimeType, body.toByteArray())
    }

    override fun suggest(query: String, limit: Int): List<ZimHit> = emptyList()
    override fun search(query: String, limit: Int): List<ZimHit> = emptyList()
    override fun close() {}
}
