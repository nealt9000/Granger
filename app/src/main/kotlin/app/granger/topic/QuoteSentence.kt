package app.granger.topic

/**
 * The sentence a book's mention sits in, for a topic page's quote (Plan LL2, Neal 2026-11-16: whole sentences, not the
 * few words the link file keeps). Pure; the app reads the chapter's text out of the EPUB.
 */
object QuoteSentence {
    /** Four lines on a phone: longer sentences are cut to a window with the mention in it. */
    private const val MAX = 210
    private const val SIDE = 90
    private val END = Regex("[.!?…»”\"]")

    /**
     * The sentence around [quote] in [text], the occurrence whose preceding words match [before] best; trimmed at words
     * with "…" when longer than [MAX]. Null when [quote] isn't in [text].
     */
    fun around(text: String, before: String, quote: String): String? {
        if (quote.isBlank()) return null
        val at = occurrence(text, before, quote) ?: return null
        val end = at + quote.length
        var start = at
        while (start > 0 && !boundaryBefore(text, start)) start--
        var stop = text.length
        for (j in end until text.length) {
            if (text[j] == '\n') { stop = j; break }
            if (text[j].toString().matches(END) && (j + 1 == text.length || text[j + 1].isWhitespace())) { stop = j + 1; break }
        }
        val sentence = text.substring(start, stop).trim()
        if (sentence.length <= MAX) return sentence
        // Too long (a run-on, or verse without stops): a window around the mention, cut at words.
        val from = (at - SIDE).coerceAtLeast(start).let { i -> if (i == start) i else text.indexOf(' ', i).let { s -> if (s in i until at) s + 1 else i } }
        val to = (end + SIDE).coerceAtMost(stop).let { i -> if (i == stop) i else text.lastIndexOf(' ', i).let { s -> if (s > end) s else i } }
        return (if (from > start) "…" else "") + text.substring(from, to).trim() + (if (to < stop) "…" else "")
    }

    /** A sentence starts at [i]: after a newline, or after an ending mark and a space. */
    private fun boundaryBefore(text: String, i: Int): Boolean {
        val prev = text[i - 1]
        if (prev == '\n') return true
        return prev.isWhitespace() && i >= 2 && text[i - 2].toString().matches(END)
    }

    private fun occurrence(text: String, before: String, quote: String): Int? {
        val tail = before.trimEnd().takeLast(16).trim().lowercase()
        var best: Int? = null
        var i = text.indexOf(quote)
        while (i >= 0) {
            if (best == null) best = i
            if (tail.isNotEmpty() && text.substring((i - 40).coerceAtLeast(0), i).lowercase().replace(Regex("\\s+"), " ").trimEnd().endsWith(tail.replace(Regex("\\s+"), " "))) return i
            i = text.indexOf(quote, i + 1)
        }
        return best
    }
}
