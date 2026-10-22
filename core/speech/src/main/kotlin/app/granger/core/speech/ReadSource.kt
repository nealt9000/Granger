package app.granger.core.speech

/**
 * A readable thing: an article, a book, an answer. Sources are lazy; the player asks for more as the voice nears
 * the end of what it has. [key] is what the saved place and the rail know the source by (`pack:<id>/<path>`,
 * `book:<id>`, `text:<key>`).
 */
interface ReadSource {
    val key: String
    val title: String

    /** The section the address falls in (a chapter, a heading), for the notification; null when there is none. */
    suspend fun sectionTitle(at: ReadAddress): String? = null

    /** Up to [limit] utterances from [from] (inclusive), or from the start when null. Empty means the end. */
    suspend fun utterances(from: ReadAddress?, limit: Int): List<Utterance>

    /** Re-finds a saved address after the source was re-opened (articles by block id, then text); null when it is gone. */
    suspend fun locate(address: ReadAddress): ReadAddress? = address
}

/** Plain text as a source: Research answers (V2) and the spike's passages read through the player. */
class TextReadSource(override val key: String, override val title: String, text: String) : ReadSource {
    private val all: List<Utterance> = Utterances.fromBlocks(
        text.split(Regex("\\n\\s*\\n")).mapIndexed { i, para -> Block(i.toString(), BlockKind.PARAGRAPH, para) }.filter { it.text.isNotBlank() },
    ) { blockId, n -> ReadAddress.Text(key, blockId.toInt(), n) }

    val size: Int get() = all.size

    override suspend fun utterances(from: ReadAddress?, limit: Int): List<Utterance> {
        val start = if (from == null) 0 else all.indexOfFirst { it.address == from }.takeIf { it >= 0 } ?: return emptyList()
        return all.subList(start, minOf(all.size, start + limit))
    }
}
