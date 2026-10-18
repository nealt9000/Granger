package app.granger.core.research

/** Keyword fallback for when the model's rewrite fails. */
object Keywords {
    private val STOPWORDS = setOf(
        "the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "had", "her", "was", "one", "our",
        "out", "has", "have", "his", "how", "its", "who", "what", "when", "where", "which", "why", "whom", "whose",
        "this", "that", "these", "those", "with", "from", "into", "than", "then", "them", "they", "their", "there",
        "about", "does", "did", "doing", "done", "been", "being", "were", "will", "would", "could", "should",
        "may", "might", "must", "shall", "also", "just", "very", "some", "such", "own", "same", "too", "she", "him",
        "your", "yours", "mine", "ours", "theirs", "tell", "explain", "give", "show", "say", "said", "know",
        "get", "got", "make", "made", "much", "many", "more", "most", "other", "over", "under", "again", "once",
        "here", "each", "few", "both", "between", "through", "during", "before", "after", "above", "below", "off",
        "cant", "dont", "isnt", "doesnt", "didnt", "whats", "hows",
    )
    private val SPLIT = Regex("[^\\p{L}\\p{N}']+")
    private val POSSESSIVE = Regex("'s(?![\\p{L}\\p{N}])")
    private val SPACES = Regex("\\s+")
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    fun extract(question: String, max: Int = 6): List<String> =
        question.lowercase().replace('\u2019', '\'').split(SPLIT)
            .map { it.trim('\'').removeSuffix("'s") }
            // Inner apostrophes stay (O'Brien), as in passage text; the stopword check ignores them (don't = dont).
            .filter { it.length >= 3 && it.replace("'", "") !in STOPWORDS }
            .distinct()
            .take(max)

    fun isStopword(word: String): Boolean = word.lowercase() in STOPWORDS

    /** The words of a keyword phrase: the rewrite's phrases ("portia mercy") are matched word by word, in any order. */
    fun words(keyword: String): List<String> = WORD.findAll(keyword).map { it.value }.toList()

    /** Lowercases a keyword or phrase and drops possessives and loose quote marks the way [extract] does, so "Darwin’s finches" becomes "darwin finches"; "O’Brien" keeps its apostrophe. */
    fun normalize(keyword: String): String =
        keyword.lowercase().replace('\u2019', '\'')
            .replace(POSSESSIVE, "")
            .trim()
            .split(SPACES)
            .map { it.trim('\'') }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}

/** Cheap check used only when the rewrite fails and the model can't say whether a question is medical. */
object Medical {
    private val LEXICON = listOf(
        "symptom", "dose", "dosage", "medication", "overdose", "bleeding", "fracture", "burn", "poison",
        "infection", "fever", "pregnan", "allerg", "chest pain", "diabet", "insulin", "seizure", "cpr", "wound",
    )

    fun looksMedical(text: String): Boolean {
        val lower = text.lowercase()
        return LEXICON.any { lower.contains(it) }
    }
}
