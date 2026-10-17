package app.granger.core.books

/** A passage to find on the page: [highlight] as it appears in the text, with a little of what comes before and after. */
data class TextQuote(val before: String, val highlight: String, val after: String)

object Snippets {
    /**
     * Where [query] occurs in [text], for the reader to find on the page: the whole query as a phrase (any spacing,
     * any case) if it's there, else its first word that is. Null when no word occurs. [context] characters either
     * side go with it, so a word that occurs twice is told apart.
     */
    fun quote(text: String, query: String, context: Int = 32, maxLength: Int = 120): TextQuote? {
        val terms = query.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return null
        val phrase = Regex(terms.joinToString("\\s+") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        val any = Regex(terms.distinct().sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        val match = phrase.find(text) ?: any.find(text) ?: return null
        val end = minOf(match.range.last + 1, match.range.first + maxLength)
        return TextQuote(
            before = text.substring(maxOf(0, match.range.first - context), match.range.first),
            highlight = text.substring(match.range.first, end),
            after = text.substring(end, minOf(text.length, end + context)),
        )
    }

    /** HTML-escaped text around the first query term, each term wrapped in `<b>`, with `…` at cut ends. */
    fun around(text: String, query: String, radius: Int = 80): String {
        val terms = query.split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct()
        val regex = if (terms.isEmpty()) null else
            Regex(terms.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        val first = regex?.find(text)
            ?: return esc(text.take(2 * radius)) + if (text.length > 2 * radius) "…" else ""

        val start = maxOf(0, first.range.first - radius)
        val end = minOf(text.length, first.range.first + radius)
        val window = text.substring(start, end)
        val sb = StringBuilder()
        if (start > 0) sb.append('…')
        var last = 0
        for (m in regex.findAll(window)) {
            sb.append(esc(window.substring(last, m.range.first)))
            sb.append("<b>").append(esc(m.value)).append("</b>")
            last = m.range.last + 1
        }
        sb.append(esc(window.substring(last)))
        if (end < text.length) sb.append('…')
        return sb.toString()
    }
}
