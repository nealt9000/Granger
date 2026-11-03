package app.granger.core.research

/**
 * Plan R2 Task 6: a question that asks what a word means, or where it comes from, names that word, and the pipeline
 * reads its dictionary entry by headword ([PassageFinder.headword]). The full-text search reads only the best few
 * articles across every pack, so a Wiktionary entry rarely makes the cut on its own ("what does betimes mean").
 *
 * Narrow cues, like [Entities.asked]'s, and no other parsing:
 * - English: "what does/did/do X mean", "the meaning/definition/etymology/origin of X", "define X", "the word X".
 * - Spanish: "qué significa X", "qué quiere decir X", "el significado de X", "la palabra X".
 * X is one or two words. Not "comes from" alone ("where does rain come from" is about rain, not the word), and not a
 * person or a thing someone said ("what did my doctor mean", "qué significa para mí").
 */
object Headword {
    private const val W = "[\\p{L}][\\p{L}'\\-]*"
    private const val X = "($W(?: $W)?)"

    private val ENGLISH = listOf(
        Regex("\\bwhat (?:does|did|do) (?:the (?:word|term|phrase) )?\"?$X\"? (?:really )?mean\\b(?! (?:when|that|by|if)\\b)", RegexOption.IGNORE_CASE),
        Regex("\\b(?:meaning|definition|etymology|origin) of (?:the (?:word|term|phrase) )?\"?$X\"?(?=\\s*(?:[?.!,]|$))", RegexOption.IGNORE_CASE),
        Regex("^\\s*define \"?$X\"?\\s*[?.!]?\\s*$", RegexOption.IGNORE_CASE),
        Regex("\\bthe (?:word|term) \"?($W)\"?", RegexOption.IGNORE_CASE),
    )

    private val SPANISH = listOf(
        Regex("\\bqu[eé] (?:significa|quiere decir) (?:la palabra |el t[eé]rmino )?(?:que (?:alguien |me |te |le )*)?\"?$X\"?\\s*[?.!]?\\s*$", RegexOption.IGNORE_CASE),
        Regex("\\bsignificado de (?:la palabra )?\"?$X\"?(?=\\s*(?:[?.!,]|$))", RegexOption.IGNORE_CASE),
        Regex("\\bla palabra \"?($W)\"?", RegexOption.IGNORE_CASE),
    )

    /** Words that stand for a person or for something said, never a headword asked about. */
    private val NOT_A_WORD = setOf(
        "my", "your", "his", "her", "their", "our", "he", "she", "they", "you", "i", "it", "this", "that", "these",
        "those", "someone", "somebody", "people", "mi", "mí", "tu", "su", "esto", "eso", "esa", "ese", "para", "alguien",
        "the", "a", "an", "el", "la", "lo", "los", "las", "un", "una",
    )

    /** The word [question] asks the meaning or origin of, as written, or null when it asks no such thing. */
    fun word(question: String, languages: List<ResearchLanguage> = ResearchLanguage.of(question)): String? {
        val q = question.replace('’', '\'').replace('“', '"').replace('”', '"').trim()
        val patterns = languages.flatMap { if (it == ResearchLanguage.ES) SPANISH else ENGLISH }
        for (p in patterns) {
            val found = p.find(q)?.groupValues?.get(1)?.trim() ?: continue
            if (found.split(' ').any { it.lowercase() in NOT_A_WORD }) continue
            return found
        }
        return null
    }

    /** The paths a dictionary may file [word] under: as written, lower case, and with a hyphen or underscore for a space. */
    fun paths(word: String): List<String> {
        val forms = listOf(word, word.lowercase())
        return (forms + forms.map { it.replace(' ', '-') } + forms.map { it.replace(' ', '_') }).distinct()
    }
}
