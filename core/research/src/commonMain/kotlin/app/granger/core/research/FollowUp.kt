package app.granger.core.research

import java.util.Locale

/**
 * Whether a question in a chat leans on the one before it. The app sends every question after the first with its prior
 * one; a question that stands alone must be searched alone, or the prior topic pulls the search its way (Plan R5 Task 7:
 * ten of Neal's questions in one chat declined that way, nine answered asked on their own).
 */
object FollowUp {
    /** Words that point back at something named earlier. Not "that" or "there": "is there a book", "the one that". */
    private val BACK = setOf("he", "she", "it", "they", "him", "her", "his", "hers", "its", "their", "theirs", "them", "this", "these", "those")
    /** Openers that carry on from the last answer: "And at altitude?", "What about Mars?". */
    private val OPENERS = listOf(listOf("and"), listOf("or"), listOf("but"), listOf("what", "about"), listOf("how", "about"))
    private val WORD = Regex("[\\p{L}']+")

    /**
     * Spanish words that point back, matched with their accents: "él" is "he" but "el" is "the". "Esta" is "this" and
     * "está" is "is". "Lo", "le", "les", "la", "los" and "las" are in [refersBackEs], which checks how they're used.
     */
    private val BACK_ES = setOf(
        "él", "ella", "ellos", "ellas", "su", "sus", "este", "esta", "estos", "estas", "éste", "ésta", "éstos", "éstas",
        "eso", "esa", "ésa", "aquel", "aquella", "aquél", "aquélla", "ahí", "allí", "ahi", "alli",
    )
    /** Spanish openers, folded: "¿Y cómo murió?", "¿Qué tal en invierno?", "¿Y qué hay de Lima?". */
    private val OPENERS_ES = listOf(listOf("y"), listOf("o"), listOf("pero"), listOf("que", "tal"), listOf("que", "hay", "de"))
    /** "Lo" before these is the neuter article ("lo que", "lo más alto"), not "it". */
    private val LO_ARTICLE = setOf("que", "mas", "mejor", "peor", "mismo", "cual", "menos")
    /** "La", "los" and "las" are articles unless a question word or "no" comes right before ("¿quién la pintó?"). */
    private val BEFORE_CLITIC = setOf("que", "quien", "quienes", "cuando", "donde", "como", "no", "ya", "nunca")

    /**
     * A word of [BACK] as a whole word ("it's" and "they're" count), or one of the [OPENERS] first; for Spanish, as
     * [refersBackEs]. [languages] are the question's ([ResearchLanguage.of]).
     */
    fun refersBack(question: String, languages: List<ResearchLanguage> = ResearchLanguage.of(question)): Boolean {
        val words = WORD.findAll(question.lowercase(Locale.ROOT)).map { it.value }.toList()
        if (ResearchLanguage.EN in languages &&
            (words.any { it.substringBefore('\'') in BACK } || OPENERS.any { words.take(it.size) == it })
        ) return true
        return ResearchLanguage.ES in languages && refersBackEs(words, languages)
    }

    /**
     * Spanish: a word of [BACK_ES], or "lo" not before a word of [LO_ARTICLE], or "le"/"les" in a question with no
     * "a"/"al" (which doubles them: "a los bebés les dicen"), or "la"/"los"/"las" right after a word of
     * [BEFORE_CLITIC]; or one of [OPENERS_ES] first. Plain articles would fire on nearly every question
     * ("la batalla de Boyacá"), so they count only as object pronouns before the verb. A word that's English too
     * ("su", "la", "Este") counts only when Spanish leads ([SpanishWords]).
     */
    private fun refersBackEs(words: List<String>, languages: List<ResearchLanguage>): Boolean {
        val folded = words.map(ResearchLanguage::fold)
        if (OPENERS_ES.any { folded.take(it.size) == it && SpanishWords.phraseCounts(it.joinToString(" "), languages) }) return true
        val doubled = folded.any { it == "a" || it == "al" }
        return words.indices.any { i ->
            val w = words[i]
            val previous = folded.getOrNull(i - 1)
            if (!SpanishWords.counts(w, languages)) return@any false
            when (w) {
                in BACK_ES -> true
                "lo" -> folded.getOrNull(i + 1) !in LO_ARTICLE
                "le", "les" -> !doubled
                "la", "los", "las" -> previous in BEFORE_CLITIC
                else -> false
            }
        }
    }
}
