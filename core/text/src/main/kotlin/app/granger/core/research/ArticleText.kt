package app.granger.core.research

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** One block of an article's readable text, in document order. [id] is the element's own id, else `g<n>` where `n` is its index among [ArticleText.BLOCKS] (the page script counts the same way). */
data class ArticleBlock(val id: String, val tag: String, val text: String) {
    val isHeading: Boolean get() = tag in ArticleText.HEADINGS
    val isCode: Boolean get() = tag == "pre"
    val isListItem: Boolean get() = tag == "li" || tag == "dd"
}

/**
 * What an article's text is, for Research's passages and for the voice alike: the block elements in document order,
 * minus navigation, references, tables and the bibliography-style sections. The selectors are public so the
 * reader's page script applies the same rules in the browser's DOM and both agree on each block's id.
 */
object ArticleText {
    const val NOISE = "script, style, sup.reference, .mw-editsection, .navbox, table, nav, footer"
    const val BLOCKS = "h1, h2, h3, h4, p, li, dd, blockquote, pre"
    const val NESTING = "p, li, dd, blockquote, pre"
    val HEADINGS = setOf("h1", "h2", "h3", "h4")
    val SKIPPED_SECTIONS = setOf(
        "references", "notes", "footnotes", "citations", "sources", "bibliography", "further reading",
        "external links", "see also", "notes and references", "references and notes", "works cited", "literature cited",
    )
    private val WHITESPACE = Regex("[\\s\\u00A0\\u2007\\u202F]+")

    /** The readable blocks of [html], in order, with their ids. Empty and punctuation-only blocks are left out. */
    fun blocks(html: String): List<ArticleBlock> {
        val doc = Jsoup.parse(html)
        // Indices are taken over every block element before any removal, so the browser's querySelectorAll agrees.
        val all = doc.select(BLOCKS)
        val noise = doc.select(NOISE)
        for (n in noise) n.remove()
        val out = ArrayList<ArticleBlock>()
        var skipLevel = 0
        all.forEachIndexed { i, el ->
            if (!el.hasParent() || el.ownerDocument() !== doc || isDetached(el)) return@forEachIndexed
            val tag = el.tagName()
            if (tag in HEADINGS) {
                val level = tag[1] - '0'
                val title = fold(el.text())
                if (skipLevel != 0 && level <= skipLevel) skipLevel = 0
                if (skipLevel == 0 && title.lowercase() in SKIPPED_SECTIONS) skipLevel = level
                if (skipLevel == 0 && title.any { it.isLetterOrDigit() }) out += ArticleBlock(idOf(el, i), tag, title)
                return@forEachIndexed
            }
            if (skipLevel != 0) return@forEachIndexed
            // A <p> inside an <li> or <blockquote> is already part of that block's text.
            if (el.parents().any { it.`is`(NESTING) }) return@forEachIndexed
            val text = if (tag == "pre") el.wholeText().trim() else fold(el.text())
            if (text.none { it.isLetterOrDigit() }) return@forEachIndexed
            out += ArticleBlock(idOf(el, i), tag, text)
        }
        return out
    }

    /** Whitespace runs (NBSP included) become one space, as the page script folds them. */
    fun fold(text: String): String = text.replace(WHITESPACE, " ").trim()

    private fun idOf(el: Element, index: Int): String = el.id().ifEmpty { "g$index" }

    /** True when [el] was inside a removed noise subtree (its chain no longer reaches the document). */
    private fun isDetached(el: Element): Boolean {
        var e: Element? = el
        while (e != null) {
            if (e is org.jsoup.nodes.Document) return false
            e = e.parent()
        }
        return true
    }
}
