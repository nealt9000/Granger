package app.granger.core.research

/**
 * How well a passage matches the rewrite keywords, independent of the search engine's own score. A keyword phrase
 * counts when all its words are there.
 */
object LexicalScore {
    private const val TITLE_BONUS = 0.1

    fun of(passage: Passage, keywords: List<String>): Double = scorer(keywords)(passage)

    /** [of] for many passages: the keyword patterns are built once. */
    fun scorer(keywords: List<String>): (Passage) -> Double {
        if (keywords.isEmpty()) return { 0.0 }
        val patterns = keywords.map { k -> Keywords.words(k).map(::wholeWord) }
        return { passage ->
            val body = "${passage.heading} ${passage.text}"
            val found = patterns.count { containsAll(body, it) }
            val title = if (patterns.any { containsAll(passage.sourceTitle, it) }) TITLE_BONUS else 0.0
            found.toDouble() / keywords.size + title
        }
    }

    private fun wholeWord(word: String) =
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(word)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

    /** Every word of a keyword appears as a whole word, in any order. */
    private fun containsAll(haystack: String, words: List<Regex>): Boolean =
        words.isNotEmpty() && words.all { it.containsMatchIn(haystack) }
}
