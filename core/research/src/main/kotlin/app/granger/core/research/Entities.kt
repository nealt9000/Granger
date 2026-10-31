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
    /** The Spanish cues, folded: "¿cuántos años tenía…?", "años después", "¿vivía aún…?", "¿pudo haber leído…?". */
    private val CUES_ES = listOf(
        "cuantos anos tenia", "que edad tenia", "anos despues", "anos antes", "anos entre", "anos pasaron",
        "anos de diferencia", "cuanto tiempo", "mayor que", "menor que", "vivo", "vivia", "antes o despues",
        "al mismo tiempo", "nacio antes", "nacio primero", "murio antes", "murio primero",
    ).associateWith { Regex("\\b${Regex.escape(it)}\\b") }
    private val COULD_HAVE_ES = Regex("\\bpudo\\b.*\\bhaber (leido|visto|conocido|oido)\\b|\\bllego\\b.*\\ba ver\\b")
    /** "Ya" ("already") is in nearly every other Spanish sentence; it's a cue beside "cuando" ("¿ya era presidente cuando…?"). */
    private val ALREADY_ES = Regex("\\bya\\b.*\\bcuando\\b|\\bcuando\\b.*\\bya\\b")

    /** Capitalised at the start of a question without naming anything. */
    private val OPENERS = setOf(
        "was", "were", "did", "does", "do", "who", "how", "is", "are", "what", "when", "could", "which", "if", "in",
        "the", "a", "an", "i",
    )
    /** Spanish question openers, folded: "¿Cuántos años…", "¿Cuándo…", "La batalla de…". */
    private val OPENERS_ES = setOf(
        "quien", "quienes", "que", "cuando", "donde", "como", "cual", "cuales", "cuanto", "cuanta", "cuantos", "cuantas",
        "por", "el", "la", "los", "las", "un", "una", "en", "es", "fue", "era",
        // Not in the list the spec gives, but as often first: "¿Hay vuelos…?", "¿Se puede…?", "¿De dónde…?".
        "hay", "habia", "se", "de", "del", "con", "para", "a", "al", "desde", "entre", "sobre", "su", "sus", "mi", "mis",
        "este", "esta", "estos", "estas", "ese", "esa", "existe", "existen", "puedo", "puede", "tengo", "tiene", "son",
        "eran", "ya", "y", "o", "pero", "si", "no", "dime", "explica", "cuentame", "vivia", "llego", "pudo", "murio", "nacio",
    )
    /** Lower-case words inside a person's name: "Miguel de Cervantes", "Ludwig van Beethoven". */
    private val PARTICLES = setOf("de", "da", "di", "del", "della", "van", "von", "der", "la", "le", "du", "dos", "das", "bin", "ibn")
    /** Spanish adds "y" ("Santiago Ramón y Cajal") and "las"/"los" ("Bartolomé de las Casas"), when Spanish leads. */
    private val PARTICLES_ES = PARTICLES + setOf("y", "las", "los")
    private val TOKEN = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’.-]*")
    private val POSSESSIVE = Regex("['’]s$")
    private val MARKS = Regex("\\p{M}+")
    private val SPACES = Regex("\\s+")

    /** Whether [question] compares or counts between dates, by the cues of its [languages] ([ResearchLanguage.of]). */
    fun asked(question: String, languages: List<ResearchLanguage> = ResearchLanguage.of(question)): Boolean {
        val q = fold(question)
        if (ResearchLanguage.EN in languages && (CUES.any { it.containsMatchIn(q) } || COULD_HAVE.containsMatchIn(q))) return true
        return ResearchLanguage.ES in languages && (CUES_ES.any { (phrase, cue) -> SpanishWords.phraseCounts(phrase, languages) && cue.containsMatchIn(q) } || COULD_HAVE_ES.containsMatchIn(q) || ALREADY_ES.containsMatchIn(q))
    }

    /** A Spanish opener that's English too ("La", "Con", "Este") is one only when Spanish leads ([SpanishWords]). */
    private fun openers(languages: List<ResearchLanguage>): Set<String> = when {
        ResearchLanguage.ES !in languages -> OPENERS
        ResearchLanguage.EN !in languages -> OPENERS_ES
        else -> OPENERS + OPENERS_ES.filter { SpanishWords.counts(it, languages) }
    }

    /**
     * The names in [question]: runs of capitalised words ("Abraham Lincoln", "On the Origin of Species", with "of" and
     * "the" inside a run), then the rewrite's [keywords] for a question typed in lower case. Folded, in order, once each.
     */
    fun named(question: String, keywords: List<String>, languages: List<ResearchLanguage> = ResearchLanguage.of(question)): List<String> {
        val openers = openers(languages)
        return (runs(question, languages) + keywords).map(::fold).filter { it.isNotBlank() && it !in openers }.distinct()
    }

    /**
     * The runs of capitalised words in [question], as written: see [named]. For English, "of" and "the" join a run;
     * for Spanish, "de", "del" and "de la/los/las" before a capital ("Isla de Pascua", "Don Quijote de la Mancha").
     * Spanish titles capitalise only their first word, so "Cien años de soledad" gives "Cien"; the rewrite's keywords
     * cover those.
     */
    fun runs(question: String, languages: List<ResearchLanguage> = ResearchLanguage.of(question)): List<String> {
        val openers = openers(languages)
        val english = ResearchLanguage.EN in languages
        // "de", "del", "la", "los" and "las" are English name parts too ("Los Angeles"): Spanish joins when Spanish leads.
        val spanish = SpanishWords.leads(languages)
        val runs = mutableListOf<String>()
        val run = mutableListOf<String>()
        val tokens = TOKEN.findAll(question).toList()
        fun close() {
            // A possessive inside a name stays ("Fermat's Last Theorem"); at its end it goes ("Mendeleev's table").
            if (run.isNotEmpty()) runs += POSSESSIVE.replace(run.joinToString(" "), "")
            run.clear()
        }
        fun adjacentAt(k: Int) = k + 1 < tokens.size && question.substring(tokens[k].range.last + 1, tokens[k + 1].range.first).isBlank()
        fun capitalAfter(k: Int) = adjacentAt(k) && tokens[k + 1].value.first().isUpperCase()
        fun joinsEs(k: Int): Boolean = when (fold(tokens[k].value)) {
            "de" -> capitalAfter(k) || (adjacentAt(k) && fold(tokens[k + 1].value) in setOf("la", "los", "las") && capitalAfter(k + 1))
            "del" -> capitalAfter(k)
            "la", "los", "las" -> k > 0 && fold(tokens[k - 1].value) == "de" && capitalAfter(k)
            else -> false
        }
        tokens.forEachIndexed { i, m ->
            val word = POSSESSIVE.replace(m.value, "").trimEnd('.')
            val next = tokens.getOrNull(i + 1)?.value
            val adjacent = next != null && question.substring(m.range.last + 1, tokens[i + 1].range.first).isBlank()
            when {
                word.first().isUpperCase() && !(i == 0 && fold(word) in openers) -> run += m.value.trimEnd('.')
                english && run.isNotEmpty() && fold(word) in setOf("of", "the") && adjacent && next!!.first().isUpperCase() -> run += word
                spanish && run.isNotEmpty() && joinsEs(i) -> run += word
                else -> close()
            }
            // A run ends at a possessive unless a name carries on ("Fermat's Last Theorem").
            if (word != m.value.trimEnd('.') && !(adjacent && next!!.first().isUpperCase())) close()
            if (!adjacent) close()
        }
        close()
        return runs.filter { fold(it) !in openers }
    }

    /**
     * How well a passage's article title names [name]: 3 the title itself, 2 a longer proper name ending in it
     * ("Dmitri Mendeleev" for "mendeleev", "United States Declaration of Independence"), 1 the title with a
     * parenthetical ("Frankenstein (1931 film)"), 0 not at all. A one-word name only matches a longer title made of
     * capitalised words, so "moon" doesn't take "Full moon".
     */
    fun match(title: String, name: String, languages: List<ResearchLanguage> = Keywords.ENGLISH): Int {
        val t = fold(title)
        if (t == name) return 3
        val words = title.trim().split(SPACES)
        val particles = if (SpanishWords.leads(languages)) PARTICLES_ES else PARTICLES
        val proper = words.all { it.first().isUpperCase() || fold(it) in particles || (name.contains(' ') && fold(it) in setOf("of", "the")) }
        if (t.endsWith(" $name") && proper) return 2
        if (t.substringBefore(" (").trim() == name && t.contains(" (")) return 1
        return 0
    }

    /**
     * [ranked] with up to [slots] of its first [keep] places given to the named things. For each name in turn the pick
     * is the article that names it best, and its first opening passage: [pool] lists an article's passages in reading
     * order, and a Wikipedia lead's dates are in its first one (r5-t4-ent: Magna Carta's second lead passage has 1216,
     * not 1215). The pick takes the place of another passage of its article in the first [keep], or else of the last
     * passage there that isn't a pick. The passages it displaces move down, in order, so they stay among the candidates.
     */
    fun reserve(
        ranked: List<Passage>,
        pool: List<Passage>,
        names: List<String>,
        keep: Int,
        slots: Int = 2,
        languages: List<ResearchLanguage> = Keywords.ENGLISH,
    ): List<Passage> {
        // Nothing ranked is a decline (the gates found nothing good enough); a name doesn't overturn that.
        if (ranked.isEmpty()) return ranked
        val candidates = (pool + ranked).distinctBy { Ranking.textKey(it) }
        val picks = mutableListOf<Passage>()
        for (name in names) {
            if (picks.size == slots) break
            // sortedWith is stable: among equals, the earlier passage in reading order wins.
            // Articles only: the dates are in an article's lead, not in a chapter of the work itself (r5-t4-ent2 took a
            // chunk of the novel Frankenstein and lost its 1818).
            val pick = candidates
                .filter { it.kind == SourceKind.ARTICLE }
                .map { it to match(it.sourceTitle, name, languages) }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<Passage, Int>> { it.second }.thenBy { if (opens(it.first)) 0 else 1 })
                .firstOrNull()?.first ?: continue
            if (picks.none { sameArticle(it, pick) }) picks += pick
        }
        val top = ranked.take(keep).toMutableList()
        for (pick in picks) {
            if (pick in top) continue
            val same = top.indexOfFirst { sameArticle(it, pick) && it !in picks }
            when {
                same >= 0 -> top[same] = pick
                top.size < keep -> top += pick
                else -> top[top.indices.lastOrNull { top[it] !in picks } ?: break] = pick
            }
        }
        return top + ranked.filter { it !in top }
    }

    private fun sameArticle(a: Passage, b: Passage): Boolean = a.sourceId == b.sourceId && a.location == b.location

    private fun opens(p: Passage): Boolean = p.heading.isBlank() || fold(p.heading) == fold(p.sourceTitle)

    private fun fold(s: String): String =
        MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "").lowercase(Locale.ROOT).replace('_', ' ')
            .replace(SPACES, " ").trim()
}
