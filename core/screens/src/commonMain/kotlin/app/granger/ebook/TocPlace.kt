package app.granger.ebook

/** One Contents row: its title, the chapter file it points into (with its #fragment), and how deep it sits. */
data class TocRow(val title: String, val href: String, val depth: Int)

/** Which of the reader's side panels is open. */
enum class Panel { CONTENTS, SETTINGS, SEARCH }

/**
 * Which Contents row the reader is in. A Gutenberg EPUB puts many chapters in one file as anchors (`…-h-1.htm.xhtml#ch3`),
 * so matching by file alone would light the file's last chapter from its first page (Plan GB walkthrough). Shared since
 * Plan I5 Task 2, on hrefs: Android's reader asks with a Readium locator (`TocPlace.index(toc, locator, starts)`).
 */
object TocPlace {
    /**
     * The contents row of an EPUB place: the last one in its file (the rows [inFile] says are) that starts at or before
     * [progression] through that file.
     */
    fun index(toc: List<TocRow>, inFile: (rowHref: String) -> Boolean, progression: Double?, anchorStarts: Map<String, Map<String, Double>>): Int? {
        val rows = toc.indices.filter { i -> inFile(toc[i].href) }
        if (rows.size <= 1) return rows.firstOrNull()
        val p = progression ?: return rows.first()
        val starts = anchorStarts[fileOf(toc[rows.first()].href)].orEmpty()
        // A row for the whole file starts at its top; an anchored one where its element is, once that's known.
        fun start(i: Int): Double? = fragmentOf(toc[i].href).let { if (it == null) 0.0 else starts[it] }
        val reached = rows.mapNotNull { i -> start(i)?.takeIf { it <= p + EPSILON }?.let { i to it } }
        // Of rows starting at the same place, the later (deeper) one; with nothing known, the file's first row.
        return reached.maxWithOrNull(compareBy<Pair<Int, Double>> { it.second }.thenBy { it.first })?.first ?: rows.first()
    }

    /** [index] for a place in the file [href], the files compared as written, without their #fragments. */
    fun index(toc: List<TocRow>, href: String, progression: Double?, anchorStarts: Map<String, Map<String, Double>>): Int? =
        index(toc, { fileOf(it) == fileOf(href) }, progression, anchorStarts)

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
                if (id in fragments && id !in at) at[id] = text
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
    // [\s\S] rather than DOT_MATCHES_ALL, which common Kotlin hasn't.
    private val HIDDEN = Regex("<(style|script)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE)
    private val ID = Regex("\\s(id|name)\\s*=\\s*[\"']([^\"']*)[\"']")
}

/** The PDF page a contents row opens at (its `#page=n`); null if it names none. */
fun pdfPageOf(href: String): Int? = href.substringAfter("#page=", "").takeIf { it.isNotEmpty() }?.toIntOrNull()

/** The last contents row at or before [page] in a PDF's outline; null when the page comes before them all. */
fun pdfTocIndex(toc: List<TocRow>, page: Int): Int? {
    var best: Int? = null
    var bestPage = 0
    toc.forEachIndexed { i, row ->
        val p = pdfPageOf(row.href) ?: return@forEachIndexed
        if (p <= page && p >= bestPage) {
            best = i
            bestPage = p
        }
    }
    return best
}
