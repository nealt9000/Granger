package app.granger.core.speech

/**
 * Splits text at its double quotes, for voices that read quoted speech in a dialogue slot of their own (V4b: the
 * phonemizer drops quote marks, so a voice can't hear them; the app picks the speaker instead). Straight quotes
 * toggle; curly ones say which way they go. Single quotes are left alone: they are mostly apostrophes.
 */
object QuoteSpans {
    data class Span(val text: String, val quoted: Boolean)

    /** [text]'s runs in and out of quotes, starting inside one when [open]; quote marks and blank runs dropped. */
    fun split(text: String, open: Boolean = false): List<Span> {
        val out = ArrayList<Span>()
        val run = StringBuilder()
        var inside = open
        fun flush() {
            val t = run.toString().trim()
            if (t.any { it.isLetterOrDigit() }) out += Span(t, inside)
            run.clear()
        }
        for (c in text) {
            val next = when (c) {
                '"' -> !inside
                '“' -> true
                '”' -> false
                else -> null
            }
            if (next == null) run.append(c) else if (next != inside) { flush(); inside = next }
        }
        flush()
        return out
    }

    /** Whether a quote is still open at the end of [text], starting inside one when [open]. */
    fun openAfter(text: String, open: Boolean = false): Boolean {
        var inside = open
        for (c in text) when (c) {
            '"' -> inside = !inside
            '“' -> inside = true
            '”' -> inside = false
        }
        return inside
    }

    /**
     * Whether each of a paragraph's [sentences] starts inside a quote. A paragraph whose quotes don't close (a
     * stray mark, a speech that runs on into the next paragraph) is read as narration throughout.
     */
    fun openAtStart(sentences: List<String>): List<Boolean> {
        val starts = ArrayList<Boolean>(sentences.size)
        var open = false
        for (s in sentences) {
            starts += open
            open = openAfter(s, open)
        }
        return if (open) sentences.map { false } else starts
    }
}
