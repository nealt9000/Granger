package app.granger.voiceinput

/**
 * Debug-only (Plan A0 Task 5): the closest library title to what the recognizer heard. The replay showed the
 * recognizer misspells names (*the domapada*, *Hector berleos*) but gets close, so an edit-distance match against the
 * library's titles recovers them; biasing does not (results doc, Task 4). A leading "open", "read", "play" or
 * "search for" is dropped first. Scores are 1 − edit distance ÷ the longer length, on lowercased letters and digits.
 */
class TitleMatcher(titles: List<String>) {
    data class Match(val title: String, val score: Double, val heard: String)

    private val entries = titles.distinct().map { normalize(it) to it }.filter { it.first.isNotEmpty() }

    /** The best title for [transcript], or null when the library is empty. */
    fun best(transcript: String): Match? {
        val heard = stripVerb(normalize(transcript))
        if (heard.isEmpty()) return null
        return entries.maxByOrNull { ratio(heard, it.first) }?.let { (norm, title) -> Match(title, ratio(heard, norm), heard) }
    }

    companion object {
        /** Below this the transcript is taken as not naming a title (commands scored ≤ 0.54 in the replay). */
        const val THRESHOLD = 0.55

        private val VERB = Regex("^(open|read|play|search for|search)\\s+")

        fun normalize(s: String): String =
            s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

        fun stripVerb(s: String): String = s.replaceFirst(VERB, "")

        fun ratio(a: String, b: String): Double {
            val longer = maxOf(a.length, b.length)
            return if (longer == 0) 1.0 else 1.0 - distance(a, b).toDouble() / longer
        }

        fun distance(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                prev = cur
            }
            return prev[b.length]
        }
    }
}
