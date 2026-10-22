package app.granger.reader

import app.granger.core.research.ArticleBlock
import java.text.Normalizer

/** Characters [start]..[end] of block [blockId]'s folded text (`ArticleText.fold`), as the page script's `cite` takes them. */
data class CiteRange(val blockId: String, val prefix: String, val start: Int, val end: Int)

/**
 * Finds a cited quote among an article's blocks, so the page can mark it (research spec §7). The anchor's section (its
 * heading block up to the next heading of the same or a higher level) is searched first, then the whole article.
 * Matching ignores case, accents and whitespace runs, one character for one, so offsets stay valid in the block's
 * folded text. A quote that isn't found whole is tried by its first half (cut at a word, at least [MIN_PART] characters):
 * the passage's text and the page's can differ around footnote marks and inline noise.
 */
object ArticleQuote {
    const val MIN_PART = 30
    private val WHITESPACE = Regex("[\\s\\u00A0\\u2007\\u202F]+")

    fun locate(blocks: List<ArticleBlock>, anchor: String?, quote: String): CiteRange? {
        val wanted = quote.replace(WHITESPACE, " ").trim()
        if (wanted.isEmpty()) return null
        val section = section(blocks, anchor)
        for (needle in listOfNotNull(wanted, half(wanted))) {
            for (scope in listOfNotNull(section, blocks)) {
                for (block in scope) {
                    if (block.isCode) continue      // kept unfolded, unlike the page script's text
                    val at = fold(block.text).indexOf(fold(needle))
                    if (at >= 0) return CiteRange(block.id, block.text.take(PREFIX), at, at + needle.length)
                }
            }
        }
        return null
    }

    private fun section(blocks: List<ArticleBlock>, anchor: String?): List<ArticleBlock>? {
        if (anchor.isNullOrEmpty()) return null
        val i = blocks.indexOfFirst { it.id == anchor }
        if (i < 0) return null
        val level = blocks[i].tag.takeIf { blocks[i].isHeading }?.drop(1)?.toIntOrNull() ?: return blocks.subList(i, i + 1)
        val end = (i + 1 until blocks.size).firstOrNull { j ->
            blocks[j].isHeading && (blocks[j].tag.drop(1).toIntOrNull() ?: 0) <= level
        } ?: blocks.size
        return blocks.subList(i, end)
    }

    private fun half(s: String): String? {
        if (s.length < MIN_PART * 2) return null
        val head = s.substring(0, s.length / 2)
        val space = head.lastIndexOf(' ')
        return (if (space >= MIN_PART) head.substring(0, space) else head).takeIf { it.length >= MIN_PART }
    }

    /** Lower case and accents off, one character for one. */
    internal fun fold(s: String): String = buildString(s.length) {
        for (c in s) {
            val base = if (c.code < 0x80) c else Normalizer.normalize(c.toString(), Normalizer.Form.NFD).first()
            append(base.lowercaseChar())
        }
    }

    /** The page script finds a block by its id, else by the start of its text (ids it couldn't match). */
    private const val PREFIX = 40
}
