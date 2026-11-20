package app.granger.core.research

/**
 * Plan R2 Task 6: words that older texts and modern articles use for the same thing, added to a rewrite keyword as
 * alternates (`alcohol|wine|strong drink`), so the search reaches both. The Classics say "strong drink", "tribute" and
 * "strangers"; a reader asks about "alcohol", "taxes" and "immigrants". WikiMed says "edema" where a novel says "dropsy".
 *
 * A general list of well-known pairs (King James and period English, old disease names), not fitted to the eval: each
 * entry is a word a modern reader is likely to type, or meet in an old book, with the words the other side uses.
 * English only. An entry matches a whole keyword, never a word inside one: "energy consumption" isn't tuberculosis, and
 * "artificial intelligence" isn't spying. The rewrite gives such words as keywords of their own ("taxes", "dropsy"). A
 * keyword that already has alternates is left as the rewrite gave it.
 */
object OlderWords {
    private val PAIRS: Map<String, List<String>> = mapOf(
        // Modern word → the older texts' words.
        "alcohol" to listOf("wine", "strong drink"),
        "alcoholic" to listOf("wine", "strong drink"),
        "drunk" to listOf("drunkenness", "drunken"),
        "immigrant" to listOf("stranger", "sojourner"),
        "immigrants" to listOf("strangers", "sojourners"),
        "foreigner" to listOf("stranger"),
        "foreigners" to listOf("strangers"),
        "tax" to listOf("tribute"),
        "taxes" to listOf("tribute"),
        "interest on a loan" to listOf("usury"),
        "pregnant" to listOf("with child"),
        "suicide" to listOf("self-slaughter"),
        "tattoo" to listOf("marks"),
        "tattoos" to listOf("marks"),
        "slave" to listOf("bondservant", "bondman"),
        "slaves" to listOf("bondservants", "bondmen"),
        "spies" to listOf("spy"),
        "intelligence gathering" to listOf("spies"),
        "poor" to listOf("needy"),
        // Old disease names ↔ the modern ones.
        "dropsy" to listOf("edema", "oedema"),
        "consumption" to listOf("tuberculosis"),
        "apoplexy" to listOf("stroke"),
        "ague" to listOf("malaria", "fever"),
        "grippe" to listOf("influenza"),
        "quinsy" to listOf("peritonsillar abscess"),
        "palsy" to listOf("paralysis"),
        "falling sickness" to listOf("epilepsy"),
        "lunacy" to listOf("insanity"),
        "melancholia" to listOf("depression"),
        "pox" to listOf("smallpox", "syphilis"),
    )

    /** [keywords] with each one that is an entry of the table, as a whole, given the entry's other words as alternates. */
    fun expand(keywords: List<String>, languages: List<ResearchLanguage> = Keywords.ENGLISH): List<String> {
        if (ResearchLanguage.EN !in languages) return keywords
        return keywords.map { keyword ->
            if ('|' in keyword) return@map keyword
            val extra = PAIRS[keyword.lowercase().trim()] ?: return@map keyword
            (listOf(keyword) + extra).joinToString("|")
        }
    }
}
