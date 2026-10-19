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

    /** A keyword word that also names a work by another title word: spellings of one work, and "gita" for the Bhagavad Gita. */
    private val ALIASES = mapOf("quran" to "koran", "koran" to "quran", "gita" to "bhagavad")

    /** [word], lowercased, and the title word it is an alias of, if any. */
    fun alternatives(word: String): Set<String> = word.lowercase().let { w -> ALIASES[w]?.let { setOf(w, it) } ?: setOf(w) }

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
 * Each term matches at the start of a word, so stems like "vaccin" and "anemi" cover their variants; short or
 * ambiguous terms (flu, gout, mole, rash, liver, burn) match whole words only.
 */
object Medical {
    private val TERMS = listOf(
        "symptom", "dose", "dosage", "medication", "overdose", "bleeding", "fracture", "poison",
        "infection", "fever", "pregnan", "allerg", "chest pain", "diabet", "insulin", "seizure", "cpr", "wound",
        "stroke", "heart attack", "blood pressure", "heart rate", "body temperature", "influenza",
        "kidney stone", "disease", "cancer", "tumor", "tumour", "vaccin", "virus", "antibiotic", "ibuprofen",
        "aspirin", "paracetamol", "acetaminophen", "concussion", "choking", "appendicitis", "measles", "chickenpox",
        "shingles", "scurvy", "tuberculosis", "anemi", "anaemi", "iron deficien", "lyme disease", "migraine", "asthma",
        "headache", "vomit", "cough", "breathing", "lump", "swelling", "doctor", "biopsy",
        "tylenol", "advil", "motrin", "benadryl", "xanax", "sertraline", "amoxicillin", "blood test", "cholesterol",
    ).map { "\\b" + Regex.escape(it) }
    private val WHOLE_WORDS = listOf("flu", "gout", "burn(s|ed|t|ing)?", "liver(s)?", "mole(s)?", "rash(es)?", "scan(s|ned|ning)?")
        .map { "\\b$it\\b" }
    private val PATTERN = Regex((TERMS + WHOLE_WORDS).joinToString("|"), RegexOption.IGNORE_CASE)

    fun looksMedical(text: String): Boolean = PATTERN.containsMatchIn(text)
}

/**
 * True when a question asks for advice about the asker's, or one specific person's, own health, medicines, test
 * results or care. Such questions decline with the medical copy whatever the library holds. Case-insensitive and
 * deterministic.
 *
 * Patterns come in two kinds. [strong] ones are unambiguous ("my meds", "came back at", "my toddler has a fever")
 * and decide alone. Generic ones ("should I take", "my results", "do I have") also fit everyday questions
 * ("should I take the train?"), so they count only when the caller has a medical signal for the question
 * (the rewrite's flag, the hint or [Medical.looksMedical]).
 */
object PersonalMedical {
    private val NUMBER = "(\\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"
    private val RELATIVE = "newborn|infant|baby|toddler|child|kid|son|daughter|husband|wife|partner|mom|mum|mother|dad|father|grandma|grandpa|grandmother|grandfather"
    private val STRONG = listOf(
        "\\bis it (safe|ok|okay)\\b[^.?!;]*\\bwith (my|the) (meds|medication|medicine|pills)\\b",
        "\\bmy (\\w+ )?(meds|medication|medicine|pills|dose|dosage|prescription|inhaler|insulin)\\b",
        "\\bcame back (at|as|positive|negative|high|low)\\b",
        "\\b(should|do) i (go to|see|call) (the |a |my )?(er|a&e|emergency|hospital|doctor|gp|dentist|911)\\b",
        "\\b(can|should) i give my ($RELATIVE)\\b",
        "\\bwhat (dose|dosage) of \\w+ for my\\b",
        "\\bmy ($RELATIVE) (swallowed|drank)\\b",
        "\\bmy doctor (said|told|meant|mean|says)\\b",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }
    private val GENERIC = listOf(
        "\\b(should|can|could|may) i (take|stop|double|skip|increase|lower|mix|combine|have)\\b",
        "\\bhow much \\w+( \\w+)? should i (take|give)\\b",
        "\\bmy (\\w+ ){0,2}(test|results?|scan|levels?|numbers|readings?|blood ?work|labs?|x-?ray|mri|biopsy)\\b",
        "\\bis (this|my) (\\w+ ){0,5}(cancer|serious|normal|ok|okay|infected|broken|dangerous)\\b",
        "\\bwhat are my (chances|odds|risks?)\\b",
        "\\bdo i have (a |an )?\\w+",
        "\\bdo i need to (take|bring|go|see|call)\\b",
        "\\bdo i give\\b",
        "\\bmy \\w+ (fell|took|hit)\\b",
        "\\bi (took|swallowed|drank|ate) \\d+",
        "\\bis \\d+ \\w+ too much\\b",
        "\\bshould i be worried about\\b",
        "\\bis it safe for me\\b",
        "\\bi (have|got|found) (a |an )?(rash|lump|pain|fever|headache|bump|swelling)\\b",
        "\\bmy ($NUMBER[- ]?(year|month|week)s?[- ]?old|$RELATIVE)\\b[^.?!;]*\\b(fever|rash|pain|vomit|breath|cough|bleed|er|hospital|temperature)\\b",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private fun normal(q: String) = q.replace('’', '\'')

    /** Phrasings that are personal medical questions on their own (the unambiguous ones). */
    fun strong(question: String): Boolean = normal(question).let { q -> STRONG.any { it.containsMatchIn(q) } }

    /** [medicalSignal] is the pipeline's combined medical flag for the question; generic phrasings need it. */
    fun matches(question: String, medicalSignal: Boolean): Boolean {
        val q = normal(question)
        return STRONG.any { it.containsMatchIn(q) } || (medicalSignal && GENERIC.any { it.containsMatchIn(q) })
    }
}
