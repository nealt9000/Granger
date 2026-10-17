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
            .map { it.removeSuffix("'s").replace("'", "") }
            .filter { it.length >= 3 && it !in STOPWORDS }
            .distinct()
            .take(max)

    /** The words of a keyword phrase: the rewrite's phrases ("portia mercy") are matched word by word, in any order. */
    fun words(keyword: String): List<String> = WORD.findAll(keyword).map { it.value }.toList()

    /** Lowercases a keyword or phrase and drops apostrophes the way [extract] does, so "Darwin’s finches" becomes "darwin finches". */
    fun normalize(keyword: String): String =
        keyword.lowercase().replace('\u2019', '\'')
            .replace(POSSESSIVE, "")
            .replace("'", "")
            .trim()
            .replace(SPACES, " ")
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
