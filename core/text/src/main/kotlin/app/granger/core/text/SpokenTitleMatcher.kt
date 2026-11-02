package app.granger.core.text

import java.text.Normalizer

/**
 * The library name a spoken phrase most likely meant (Plan A1). The phone's recognizer gets questions right but
 * misspells names (*the domapada*, *Hector berleos*); the A0 spike found biasing barely helps, while matching the
 * transcript against the library's own names afterwards finds them. Two scores, the higher wins:
 * - **spelling:** 1 − edit distance ÷ the longer length, on the whole normalised phrase;
 * - **words:** every word of the phrase (articles and joining words aside) is close to a word of the name; the
 *   score grows with how much of the name the phrase covers. So *the Bible* finds *The King James Bible* and
 *   *Wikipedia* finds *Best of Wikipedia*, where spelling alone picked *The Rivals* and *WikiMed* (spike, Task 5).
 *   And the other way: every word of the name is in a longer phrase (*measure for measure by Shakespeare*), scored
 *   by how much of the phrase the name fills.
 *
 * A leading verb is dropped first (*open*, *read*, *play*, *search for*, and the Spanish ones). Pure Kotlin, so the
 * iPhone path can share it.
 */
object SpokenTitleMatcher {
    data class Match(val name: String, val score: Double)

    /** Below this the phrase is taken as not naming anything in the library. Tuned on the spike's transcripts. */
    const val THRESHOLD = 0.55

    /** How close two words must be to count as the same word (*aurulius* / *aurelius* is 0.88). */
    private const val WORD_CLOSE = 0.8
    private const val WORD_BASE = 0.7

    private val VERB = Regex(
        "^(open|read|play|listen to|search for|search|find|abrir|abre|leer|lee|reproducir|reproduce|pon|escuchar|buscar|busca)\\s+",
    )
    private val MINOR = setOf("the", "a", "an", "of", "by", "and", "el", "la", "los", "las", "un", "una", "de", "del", "y", "por")

    /** The best of [names] for [heard], or null when nothing scores at least [THRESHOLD]. */
    fun best(heard: String, names: List<String>): Match? = bestAny(heard, names)?.takeIf { it.score >= THRESHOLD }

    /** The best of [names] for [heard] whatever its score; null when [heard] has no words or [names] is empty. */
    fun bestAny(heard: String, names: List<String>): Match? {
        val phrase = stripVerb(normalize(heard))
        if (phrase.isEmpty()) return null
        val words = significant(phrase)
        var best: Match? = null
        var bestSpelling = -1.0
        for (name in names) {
            val norm = normalize(name)
            if (norm.isEmpty()) continue
            val spelling = ratio(phrase, norm)
            val nameWords = significant(norm)
            val score = maxOf(spelling, wordScore(words, nameWords), containedScore(nameWords, words))
            val better = best == null || score > best.score + 1e-9 ||
                (score > best.score - 1e-9 && (spelling > bestSpelling + 1e-9 || (spelling > bestSpelling - 1e-9 && name.length < best.name.length)))
            if (better) { best = Match(name, score); bestSpelling = spelling }
        }
        return best
    }

    /** Lowercase letters and digits, accents folded, single spaces. */
    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun stripVerb(phrase: String): String = phrase.replaceFirst(VERB, "")

    private fun significant(s: String): List<String> = s.split(' ').filter { it.isNotEmpty() && it !in MINOR }

    /**
     * 0 unless every word of [phrase] is close to a different word of [name]; then [WORD_BASE] plus the rest in
     * proportion to how many of the name's words were matched.
     */
    private fun wordScore(phrase: List<String>, name: List<String>): Double {
        if (phrase.isEmpty() || name.isEmpty() || phrase.size > name.size) return 0.0
        val used = BooleanArray(name.size)
        for (w in phrase) {
            var at = -1
            var bestRatio = WORD_CLOSE
            for (i in name.indices) {
                if (used[i]) continue
                val r = ratio(w, name[i])
                if (r >= bestRatio) { bestRatio = r; at = i }
            }
            if (at < 0) return 0.0
            used[at] = true
        }
        return WORD_BASE + (1 - WORD_BASE) * phrase.size / name.size
    }

    /** Every word of [name] close to a different word of the longer [phrase]: half, plus half the share it fills. */
    private fun containedScore(name: List<String>, phrase: List<String>): Double {
        if (name.isEmpty() || name.size >= phrase.size) return 0.0
        return if (wordScore(name, phrase) > 0) 0.5 + 0.5 * name.size / phrase.size else 0.0
    }

    fun ratio(a: String, b: String): Double {
        val longer = maxOf(a.length, b.length)
        return if (longer == 0) 1.0 else 1.0 - distance(a, b).toDouble() / longer
    }

    fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}
