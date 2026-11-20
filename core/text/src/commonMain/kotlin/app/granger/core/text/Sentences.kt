package app.granger.core.text

/**
 * Sentence boundaries in one paragraph, the same on every phone (Plan I1, builder's call 3). It replaces
 * `java.text.BreakIterator` (whose rules differ between the desktop JVM, Android's ICU and iOS) with one plain rule: a
 * sentence ends after a run of `.`, `!`, `?` or `…`, any closing quotes or brackets, and whitespace, when the next
 * character isn't a lower-case letter. Abbreviations followed by a capital ("Dr. Smith") end a sentence, as the JVM's
 * `BreakIterator` does; "U.S. in 1940" doesn't, since a lower-case word follows.
 */
object Sentences {
    private const val ENDS = ".!?…"
    private const val CLOSERS = "\"'’”»)]"

    /** Breaks in [paragraph]: 0 first, the length last, each sentence keeping its trailing whitespace (as `BreakIterator`). */
    fun breaks(paragraph: String): List<Int> {
        val out = mutableListOf(0)
        var i = 0
        val n = paragraph.length
        while (i < n) {
            if (paragraph[i] !in ENDS) { i++; continue }
            var j = i
            while (j < n && paragraph[j] in ENDS) j++
            while (j < n && paragraph[j] in CLOSERS) j++
            var k = j
            while (k < n && paragraph[k].isWhitespace()) k++
            if (k > j && k < n && !paragraph[k].isLowerCase()) out += k
            i = maxOf(k, i + 1)
        }
        if (out.last() != n) out += n
        return out
    }
}
