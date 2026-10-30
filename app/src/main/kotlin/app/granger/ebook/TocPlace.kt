package app.granger.ebook

import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url

/**
 * Which Contents row the reader is in. A Gutenberg EPUB puts many chapters in one file as anchors (`…-h-1.htm.xhtml#ch3`),
 * so matching by file alone would light the file's last chapter from its first page (Plan GB walkthrough).
 */
internal object TocPlace {
    /** The contents row of an EPUB [here]: the last one in its file that starts at or before it. */
    fun index(toc: List<TocRow>, here: Locator, anchorStarts: Map<String, Map<String, Double>>): Int? {
        val rows = toc.indices.filter { i -> Url(toc[i].href)?.let { sameFile(it, here.href) } == true }
        if (rows.size <= 1) return rows.firstOrNull()
        val progression = here.locations.progression ?: return rows.first()
        val starts = anchorStarts[fileOf(toc[rows.first()].href)].orEmpty()
        // A row for the whole file starts at its top; an anchored one where its element is, once that's known.
        fun start(i: Int): Double? = fragmentOf(toc[i].href).let { if (it == null) 0.0 else starts[it] }
        val reached = rows.mapNotNull { i -> start(i)?.takeIf { it <= progression + EPSILON }?.let { i to it } }
        // Of rows starting at the same place, the later (deeper) one; with nothing known, the file's first row.
        return reached.maxWithOrNull(compareBy<Pair<Int, Double>> { it.second }.thenBy { it.first })?.first ?: rows.first()
    }

    /** The files with more than one row, each with the anchors its rows point at. */
    fun anchorsByFile(toc: List<TocRow>): Map<String, Set<String>> =
        toc.groupBy { fileOf(it.href) }.filterValues { it.size > 1 }
            .mapValues { (_, rows) -> rows.mapNotNull { fragmentOf(it.href) }.toSet() }
            .filterValues { it.isNotEmpty() }

    /**
     * Where each of [fragments] starts in [html], as a fraction of the body's text (tags, styles and scripts left out),
     * which tracks Readium's progression through the file closely enough to tell chapters apart.
     */
    fun anchorStarts(html: String, fragments: Set<String>): Map<String, Double> {
        if (fragments.isEmpty()) return emptyMap()
        val body = html.indexOf("<body").let { if (it < 0) html else html.substring(it) }.replace(HIDDEN, "")
        val at = HashMap<String, Int>()
        var text = 0
        var last = 0
        for (tag in TAG.findAll(body)) {
            text += body.substring(last, tag.range.first).count { !it.isWhitespace() }
            last = tag.range.last + 1
            for (m in ID.findAll(tag.value)) {
                val id = m.groupValues[2]
                if (id in fragments) at.putIfAbsent(id, text)
            }
        }
        text += body.substring(last).count { !it.isWhitespace() }
        if (text == 0) return at.mapValues { 0.0 }
        return at.mapValues { (_, n) -> n.toDouble() / text }
    }

    private fun fileOf(href: String) = href.substringBefore('#')

    private fun fragmentOf(href: String) = href.substringAfter('#', "").ifEmpty { null }

    private const val EPSILON = 0.002
    private val TAG = Regex("<[^>]*>")
    private val HIDDEN = Regex("<(style|script)\\b[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val ID = Regex("\\s(id|name)\\s*=\\s*[\"']([^\"']*)[\"']")
}
