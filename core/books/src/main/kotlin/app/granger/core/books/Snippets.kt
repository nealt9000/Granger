package app.granger.core.books

object Snippets {
    /** HTML-escaped text around the first query term, each term wrapped in `<b>`, with `…` at cut ends. */
    fun around(text: String, query: String, radius: Int = 80): String {
        val terms = query.split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct()
        val regex = if (terms.isEmpty()) null else
            Regex(terms.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        val first = regex?.find(text)
            ?: return escape(text.take(2 * radius)) + if (text.length > 2 * radius) "…" else ""

        val start = maxOf(0, first.range.first - radius)
        val end = minOf(text.length, first.range.first + radius)
        val window = text.substring(start, end)
        val sb = StringBuilder()
        if (start > 0) sb.append('…')
        var last = 0
        for (m in regex.findAll(window)) {
            sb.append(escape(window.substring(last, m.range.first)))
            sb.append("<b>").append(escape(m.value)).append("</b>")
            last = m.range.last + 1
        }
        sb.append(escape(window.substring(last)))
        if (end < text.length) sb.append('…')
        return sb.toString()
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
