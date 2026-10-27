package app.granger.core.research

import java.text.Normalizer
import java.util.Locale

/**
 * Plan R5, Task 4: a question that compares dates ("How old was Mary Shelley when Frankenstein came out?") needs a fact
 * from each thing it names, and ranking often keeps two passages about one and none about the other (r1-dense-1: 7 of
 * the 15 year questions had one fact or none). [reserve] gives each named thing its own article's best passage.
 */
object Entities {
    /** Kept loose: an unneeded reservation only swaps the fourth passage. */
    private val CUES = listOf(
        "how old", "years after", "years before", "years between", "years passed", "how long", "older", "younger",
        "alive", "before or after", "by how much", "lived longer", "live to see", "same time", "already",
    ).map { Regex("\\b${Regex.escape(it)}\\b") }
    private val COULD_HAVE = Regex("\\bcould .+ have (read|seen|met|heard)\\b")

    /** Capitalised at the start of a question without naming anything. */
    private val OPENERS = setOf(
        "was", "were", "did", "does", "do", "who", "how", "is", "are", "what", "when", "could", "which", "if", "in",
        "the", "a", "an", "i",
    )
    private val TOKEN = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’.-]*")
    private val POSSESSIVE = Regex("['’]s$")
    private val MARKS = Regex("\\p{M}+")
    private val SPACES = Regex("\\s+")

    /** Whether [question] compares or counts between dates. */
    fun asked(question: String): Boolean {
        val q = fold(question)
        return CUES.any { it.containsMatchIn(q) } || COULD_HAVE.containsMatchIn(q)
    }

    /**
     * The names in [question]: runs of capitalised words ("Abraham Lincoln", "On the Origin of Species", with "of" and
     * "the" inside a run), then the rewrite's [keywords] for a question typed in lower case. Folded, in order, once each.
     */
    fun named(question: String, keywords: List<String>): List<String> {
        val runs = mutableListOf<String>()
        val run = mutableListOf<String>()
        val tokens = TOKEN.findAll(question).toList()
        fun close() {
            // A possessive inside a name stays ("Fermat's Last Theorem"); at its end it goes ("Mendeleev's table").
            if (run.isNotEmpty()) runs += POSSESSIVE.replace(run.joinToString(" "), "")
            run.clear()
        }
        tokens.forEachIndexed { i, m ->
            val word = POSSESSIVE.replace(m.value, "").trimEnd('.')
            val next = tokens.getOrNull(i + 1)?.value
            val adjacent = next != null && question.substring(m.range.last + 1, tokens[i + 1].range.first).isBlank()
            when {
                word.first().isUpperCase() && !(i == 0 && fold(word) in OPENERS) -> run += m.value.trimEnd('.')
                run.isNotEmpty() && fold(word) in setOf("of", "the") && adjacent && next!!.first().isUpperCase() -> run += word
                else -> close()
            }
            // A run ends at a possessive unless a name carries on ("Fermat's Last Theorem").
            if (word != m.value.trimEnd('.') && !(adjacent && next!!.first().isUpperCase())) close()
            if (!adjacent) close()
        }
        close()
        return (runs + keywords).map(::fold).filter { it.isNotBlank() && it !in OPENERS }.distinct()
    }

    /**
     * How well a passage's article title names [name]: 3 the title itself, 2 a longer proper name ending in it
     * ("Dmitri Mendeleev" for "mendeleev", "United States Declaration of Independence"), 1 the title with a
     * parenthetical ("Frankenstein (1931 film)"), 0 not at all. A one-word name only matches a longer title made of
     * capitalised words, so "moon" doesn't take "Full moon".
     */
    fun match(title: String, name: String): Int {
        val t = fold(title)
        if (t == name) return 3
        val words = title.trim().split(SPACES)
        val proper = words.all { it.first().isUpperCase() || (name.contains(' ') && fold(it) in setOf("of", "the")) }
        if (t.endsWith(" $name") && proper) return 2
        if (t.substringBefore(" (").trim() == name && t.contains(" (")) return 1
        return 0
    }

    /**
     * [ranked] with up to [slots] of its first [keep] places given to the named things: for each name in turn, the best
     * passage of the article that names it best, from [ranked] and then [pool] (an article's opening passage first,
     * as that's where the dates are). A passage ranking already keeps in the first [keep] counts as that name's. The
     * passages it displaces move down, in order, so nothing is lost for Think deeper.
     */
    fun reserve(ranked: List<Passage>, pool: List<Passage>, names: List<String>, keep: Int, slots: Int = 2): List<Passage> {
        val candidates = (ranked + pool).distinctBy { Ranking.textKey(it) }
        val picks = mutableListOf<Passage>()
        for (name in names) {
            if (picks.size == slots) break
            val best = candidates
                .map { it to match(it.sourceTitle, name) }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<Passage, Int>> { it.second }.thenBy { if (opens(it.first)) 0 else 1 })
                .firstOrNull()?.first ?: continue
            val inTop = ranked.take(keep).firstOrNull { it.sourceId == best.sourceId && it.location == best.location }
            val pick = inTop ?: best
            if (picks.none { it.sourceId == pick.sourceId && it.location == pick.location }) picks += pick
        }
        // Nothing ranked is a decline (the gates found nothing good enough); a name doesn't overturn that.
        if (picks.isEmpty() || ranked.isEmpty()) return ranked
        val top = ranked.take(keep).toMutableList()
        for (pick in picks.filter { it !in top }) {
            if (top.size < keep) {
                top += pick
                continue
            }
            val out = top.indices.lastOrNull { top[it] !in picks } ?: break
            top[out] = pick
        }
        val order = ranked.take(keep).filter { it in top } + top.filter { it !in ranked.take(keep) }
        return order + ranked.filter { it !in order }
    }

    private fun opens(p: Passage): Boolean = p.heading.isBlank() || fold(p.heading) == fold(p.sourceTitle)

    private fun fold(s: String): String =
        MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "").lowercase(Locale.ROOT).replace('_', ' ')
            .replace(SPACES, " ").trim()
}
