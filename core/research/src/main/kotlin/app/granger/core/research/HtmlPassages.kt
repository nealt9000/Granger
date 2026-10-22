package app.granger.core.research

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

data class HtmlPassage(val heading: String, val anchor: String?, val text: String)

/** Splits an article's HTML into quotable passages along its headings. The rules for what counts as text are [ArticleText]'s, shared with the voice. */
object HtmlPassages {
    private const val NOISE = ArticleText.NOISE
    private const val BLOCKS = ArticleText.BLOCKS
    private const val NESTING = ArticleText.NESTING
    private val HEADINGS = ArticleText.HEADINGS
    private val SKIPPED_SECTIONS = ArticleText.SKIPPED_SECTIONS
    private val WHITESPACE = Regex("[\\s\\u00A0\\u2007\\u202F]+")

    fun split(html: String, targetWords: Int = 200, maxWords: Int = 300): List<HtmlPassage> {
        val doc = Jsoup.parse(html)
        doc.select(NOISE).remove()

        val out = mutableListOf<HtmlPassage>()
        var heading = ""
        var anchor: String? = null
        val buffer = mutableListOf<String>() // words of the passage being built
        val blocks = mutableListOf<String>()
        var skipLevel = 0 // level of the bibliography-style heading being skipped; 0 when not skipping

        fun flush() {
            if (buffer.isNotEmpty()) out += HtmlPassage(heading, anchor, blocks.joinToString("\n"))
            buffer.clear()
            blocks.clear()
        }

        for (el in doc.select(BLOCKS)) {
            if (el.tagName() in HEADINGS) {
                flush()
                val level = el.tagName()[1] - '0'
                val title = el.text().trim()
                if (skipLevel != 0 && level <= skipLevel) skipLevel = 0
                if (skipLevel == 0 && title.lowercase() in SKIPPED_SECTIONS) skipLevel = level
                heading = title
                anchor = anchorOf(el)
                continue
            }
            if (skipLevel != 0) continue
            // A <p> inside an <li> or <blockquote> is already part of that block's text.
            if (el.parents().any { it.`is`(NESTING) }) continue
            val words = el.text().trim().split(WHITESPACE).filter { it.isNotEmpty() }
            if (words.isEmpty()) continue

            if (words.size > maxWords) {
                flush()
                val pieces = words.chunked(targetWords)
                for (piece in pieces.dropLast(1)) out += HtmlPassage(heading, anchor, piece.joinToString(" "))
                buffer += pieces.last()
                blocks += pieces.last().joinToString(" ")
            } else {
                if (buffer.size + words.size > maxWords) flush()
                buffer += words
                blocks += words.joinToString(" ")
            }
            if (buffer.size >= targetWords) flush()
        }
        flush()
        return out
    }

    private fun anchorOf(heading: Element): String? =
        heading.id().ifEmpty { null }
            ?: heading.selectFirst("[id]")?.id()?.ifEmpty { null }
            ?: heading.parents().firstOrNull { (it.tagName() == "section" || it.tagName() == "details") && it.id().isNotEmpty() }?.id()
}
