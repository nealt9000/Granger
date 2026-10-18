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

/**
 * Cheap lexicon check for medical questions. The pipeline ORs it with the rewrite's own medical flag, which the small
 * model misses for some questions ("warning signs of a stroke"), and it decides alone when the rewrite fails.
 * Terms match as substrings of the lowercased text, so stems like "vaccin" and "anemi" cover their variants.
 */
object Medical {
    private val LEXICON = listOf(
        "symptom", "dose", "dosage", "medication", "overdose", "bleeding", "fracture", "burn", "poison",
        "infection", "fever", "pregnan", "allerg", "chest pain", "diabet", "insulin", "seizure", "cpr", "wound",
        "stroke", "heart attack", "blood pressure", "heart rate", "body temperature", "flu", "influenza",
        "kidney stone", "disease", "cancer", "tumor", "tumour", "vaccin", "virus", "antibiotic", "ibuprofen",
        "aspirin", "paracetamol", "acetaminophen", "concussion", "choking", "appendic", "measles", "chickenpox",
        "shingles", "gout", "scurvy", "tuberculosis", "anemi", "anaemi", "iron deficien", "lyme", "migraine", "asthma",
    )

    fun looksMedical(text: String): Boolean {
        val lower = text.lowercase()
        return LEXICON.any { lower.contains(it) }
    }
}

/**
 * True when a question asks for advice about the asker's, or one specific person's, own health, medicines, test
 * results or care. Such questions decline with the medical copy whatever the library holds. Case-insensitive and
 * deterministic: a small set of phrasings in five families (medicine decisions, one person's results,
 * self-diagnosis, care decisions, care for a named relative). General questions stay false even when they say
 * "my" or "you" in passing.
 */
object PersonalMedical {
    private val PATTERNS = listOf(
        // Medicine decisions.
        "\\b(should|can|could|may) i (take|stop|double|skip|increase|lower|mix|combine|have)\\b",
        "\\bhow much \\w+( \\w+)? should i (take|give)\\b",
        "\\bis it (safe|ok|okay) (for me|for my|to take|with (my|the) (meds|medication|medicine|pills))",
        "\\bmy (meds|medication|medicine|pills|dose|dosage|prescription|inhaler|insulin)\\b",
        // One person's results.
        "\\bmy (\\w+ ){0,2}(test|results?|scan|levels?|numbers|readings?|blood ?work|labs?|x-?ray|mri|biopsy)\\b",
        "\\bcame back (at|as|positive|negative|high|low)\\b",
        // Self-diagnosis.
        "\\bis (this|my) (\\w+ ){0,5}(cancer|serious|normal|ok|okay|infected|broken|dangerous)\\b",
        "\\bwhat are my (chances|odds|risks?)\\b",
        "\\bdo i have (a |an )?\\w+",
        // Care decisions for a specific person.
        "\\bdo i need to (take|bring|go|see|call)\\b",
        "\\b(should|do) i (go to|see|call) (the |a |my )?(er|a&e|emergency|hospital|doctor|gp|dentist|911)\\b",
        "\\bmy (\\d+[- ]?(year|month|week)[- ]?old|baby|toddler|child|kid|son|daughter|husband|wife|partner|mom|mum|mother|dad|father)\\b.*\\b(fever|rash|pain|vomit|breath|cough|bleed|er|hospital|doctor|temperature)",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    fun matches(question: String): Boolean {
        val q = question.replace('\u2019', '\'')
        return PATTERNS.any { it.containsMatchIn(q) }
    }
}
