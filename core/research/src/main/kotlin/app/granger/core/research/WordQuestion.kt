package app.granger.core.research

/**
 * The word a question asks the meaning or origin of ("¿qué significa madrugué?", "por qué les dicen guagua", "what does
 * betimes mean"), for a dictionary lookup ([PassageFinder.entry]). The full-text search ranks a dictionary's exact
 * entry no better than any other pack's best hit, so in l4-tune-es-1 the five Spanish word questions all lost theirs
 * to other packs and declined.
 *
 * Only plain phrasings count, and only one word: the one the phrasing puts in the asked place. Matched on the question
 * folded for its cues ([ResearchLanguage.fold]); the word comes back as typed, accents and all.
 */
object WordQuestion {
    private const val W = "([\\p{L}'-]+)"
    private const val END = "\\s*[?!.¿¡]*\\s*$"

    /** Spanish phrasings: "qué significa (que alguien te) X", "les dicen X", "de dónde viene la palabra X", "qué es la X". */
    private val SPANISH = listOf(
        "\\b(significa|significan|quiere decir|quieren decir)\\b.*?\\b$W$END",
        "\\bsignificado de (la palabra |el termino |la expresion )?$W\\b",
        "\\b(les|le|se) (dicen|dice|llama|llaman)( a \\w+)? $W$END",
        "\\b(de donde|como) (viene|surge|surgio|salio|nace|nacio) (la palabra |el termino |la expresion |el nombre )?$W$END",
        "\\b(origen|etimologia) de (la palabra |el termino |la expresion )?$W\\b",
        "\\bla palabra $W\\b",
        "^\\W*(que|cual) (es|son) (el|la|los|las|un|una|lo)? ?$W$END",
    ).map(::Regex)

    /** English phrasings: "what does X mean", "meaning of X", "where does the word X come from". */
    private val ENGLISH = listOf(
        "\\bwhat (does|do|did) (the word |the term )?$W mean\\b",
        "\\b(meaning|etymology|origin|definition) of (the word |the term )?$W\\b",
        "\\bthe word $W\\b",
        "\\b(word|term) $W$END",
        "\\bdefine $W$END",
    ).map(::Regex)

    /** The word [question] asks about, as typed, or null when it isn't a plain word question. */
    fun word(question: String, languages: List<ResearchLanguage>): String? {
        val folded = ResearchLanguage.fold(question)
        // fold keeps every character's place (it drops combining marks after decomposing, which composes back to one
        // character per letter for Spanish), so a match's range in the folded text is the word's range as typed.
        val typed = java.text.Normalizer.normalize(question, java.text.Normalizer.Form.NFC)
        val patterns = (if (ResearchLanguage.ES in languages) SPANISH else emptyList()) +
            (if (ResearchLanguage.EN in languages) ENGLISH else emptyList())
        for (pattern in patterns) {
            val match = pattern.find(folded) ?: continue
            val group = match.groups.filterNotNull().last()
            val word = if (folded.length == typed.length) typed.substring(group.range) else group.value
            if (word.length >= 3 && !Keywords.isStopword(word.lowercase(), languages)) return word.trim('\'', '-')
        }
        return null
    }
}
