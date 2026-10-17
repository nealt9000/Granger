package app.granger.core.research

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

data class HtmlPassage(val heading: String, val anchor: String?, val text: String)

/** Splits an article's HTML into quotable passages along its headings. */
object HtmlPassages {
    private const val NOISE = "script, style, sup.reference, .mw-editsection, .navbox, table, nav, footer"
    private const val BLOCKS = "h1, h2, h3, h4, p, li, dd, blockquote, pre"
    private const val NESTING = "p, li, dd, blockquote, pre"
    private val HEADINGS = setOf("h1", "h2", "h3", "h4")
    private val WHITESPACE = Regex("\\s+")

    fun split(html: String, targetWords: Int = 200, maxWords: Int = 300): List<HtmlPassage> {
        val doc = Jsoup.parse(html)
        doc.select(NOISE).remove()

        val out = mutableListOf<HtmlPassage>()
        var heading = ""
        var anchor: String? = null
        val buffer = mutableListOf<String>() // words of the passage being built
        val blocks = mutableListOf<String>()

        fun flush() {
            if (buffer.isNotEmpty()) out += HtmlPassage(heading, anchor, blocks.joinToString("\n"))
            buffer.clear()
            blocks.clear()
        }

        for (el in doc.select(BLOCKS)) {
            if (el.tagName() in HEADINGS) {
                flush()
                heading = el.text().trim()
                anchor = anchorOf(el)
                continue
            }
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
