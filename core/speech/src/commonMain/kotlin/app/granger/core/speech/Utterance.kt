package app.granger.core.speech

/**
 * Where a sentence is, in a form a reader can show and the player can save. Encoded as
 * `pack:<id>/<path>#<blockId>:<n>`, `book:<id>@<locator json>:<n>` or `text:<key>:<n>`, where `n` is the
 * sentence's index within its block.
 */
sealed interface ReadAddress {
    val sentence: Int
    fun encode(): String
    fun withSentence(n: Int): ReadAddress

    data class Article(val packId: String, val path: String, val blockId: String, override val sentence: Int) : ReadAddress {
        override fun encode() = "pack:$packId/$path#$blockId:$sentence"
        override fun withSentence(n: Int) = copy(sentence = n)
    }

    data class Book(val bookId: String, val locatorJson: String, override val sentence: Int) : ReadAddress {
        override fun encode() = "book:$bookId@$locatorJson:$sentence"
        override fun withSentence(n: Int) = copy(sentence = n)
    }

    /** Plain text with a key of its own (a Research answer, the spike's passages). [block] is the paragraph index. */
    data class Text(val key: String, val block: Int, override val sentence: Int) : ReadAddress {
        override fun encode() = "text:$key#$block:$sentence"
        override fun withSentence(n: Int) = copy(sentence = n)
    }

    companion object {
        fun decode(s: String): ReadAddress? {
            val colon = s.lastIndexOf(':')
            if (colon < 0) return null
            val n = s.substring(colon + 1).toIntOrNull() ?: return null
            val body = s.substring(0, colon)
            return when {
                body.startsWith("pack:") -> {
                    val hash = body.lastIndexOf('#'); if (hash < 0) return null
                    val slash = body.indexOf('/', 5); if (slash < 0 || slash > hash) return null
                    Article(body.substring(5, slash), body.substring(slash + 1, hash), body.substring(hash + 1), n).takeIf { it.packId.isNotEmpty() && it.blockId.isNotEmpty() }
                }
                body.startsWith("book:") -> {
                    val at = body.indexOf('@', 5); if (at < 0) return null
                    Book(body.substring(5, at), body.substring(at + 1), n).takeIf { it.bookId.isNotEmpty() && it.locatorJson.isNotEmpty() }
                }
                body.startsWith("text:") -> {
                    val hash = body.lastIndexOf('#'); if (hash < 0) return null
                    val block = body.substring(hash + 1).toIntOrNull() ?: return null
                    Text(body.substring(5, hash), block, n).takeIf { it.key.isNotEmpty() }
                }
                else -> null
            }
        }
    }
}

enum class UtteranceKind { HEADING, SENTENCE, CODE_OMITTED }

/** One thing the voice says: a sentence (or a heading), where it is, which paragraph it belongs to, and the pause after it. */
data class Utterance(
    val address: ReadAddress,
    val kind: UtteranceKind,
    val text: String,
    val paragraph: Int,
    val pauseAfterMs: Long,
    /** The sentence's character range within its block's folded text, for the reader's highlight. */
    val start: Int = 0,
    val end: Int = text.length,
    /** Whether the sentence starts inside a quote its paragraph opened earlier ([QuoteSpans]). */
    val quoteOpen: Boolean = false,
)

/** A block of a source as its reader hands it over: a heading, a paragraph, a list item, a code block. */
data class Block(val id: String, val kind: BlockKind, val text: String)

enum class BlockKind { HEADING, PARAGRAPH, LIST_ITEM, CODE }

object Utterances {
    const val SENTENCE_PAUSE_MS = 350L
    const val PARAGRAPH_PAUSE_MS = 700L
    const val HEADING_PAUSE_MS = 900L
    const val CODE_OMITTED = "Code omitted."

    /** Blocks → utterances, with the pauses the voice keeps: short between sentences, longer after a paragraph or heading. */
    fun fromBlocks(blocks: List<Block>, address: (blockId: String, sentence: Int) -> ReadAddress): List<Utterance> {
        val out = ArrayList<Utterance>()
        var paragraph = 0
        for (block in blocks) {
            val made = when (block.kind) {
                BlockKind.CODE -> {
                    if (block.text.isBlank()) emptyList()
                    else listOf(Utterance(address(block.id, 0), UtteranceKind.CODE_OMITTED, CODE_OMITTED, paragraph, PARAGRAPH_PAUSE_MS, 0, 0))
                }
                BlockKind.HEADING -> {
                    val text = Sentences.fold(block.text)
                    if (text.none { it.isLetterOrDigit() }) emptyList()
                    else listOf(Utterance(address(block.id, 0), UtteranceKind.HEADING, text, paragraph, HEADING_PAUSE_MS, 0, text.length))
                }
                BlockKind.PARAGRAPH, BlockKind.LIST_ITEM -> {
                    val sentences = Sentences.sentences(block.text)
                    val open = QuoteSpans.openAtStart(sentences.map { it.text })
                    sentences.mapIndexed { i, s ->
                        val last = i == sentences.lastIndex
                        Utterance(address(block.id, i), UtteranceKind.SENTENCE, s.text, paragraph, if (last) PARAGRAPH_PAUSE_MS else SENTENCE_PAUSE_MS, s.start, s.end, open[i])
                    }
                }
            }
            if (made.isNotEmpty()) { out += made; paragraph++ }
        }
        return out
    }
}
