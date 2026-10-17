package app.granger.core.research

/**
 * How well a passage matches the rewrite keywords, independent of the search engine's own score. A keyword phrase
 * counts when all its words are there.
 */
object LexicalScore {
    private const val TITLE_BONUS = 0.1

    fun of(passage: Passage, keywords: List<String>): Double {
        if (keywords.isEmpty()) return 0.0
        val body = "${passage.heading} ${passage.text}"
        val found = keywords.count { containsWhole(body, it) }
        val title = if (keywords.any { containsWhole(passage.sourceTitle, it) }) TITLE_BONUS else 0.0
        return found.toDouble() / keywords.size + title
    }

    /** Every word of [keyword] appears as a whole word, in any order. */
    private fun containsWhole(haystack: String, keyword: String): Boolean {
        val words = Keywords.words(keyword)
        return words.isNotEmpty() && words.all {
            Regex("(?<![\\p{L}\\p{N}])${Regex.escape(it)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(haystack)
        }
    }
}
