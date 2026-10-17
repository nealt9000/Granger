package app.granger.core.research

/** How well a passage matches the rewrite keywords, independent of the search engine's own score. */
object LexicalScore {
    private const val TITLE_BONUS = 0.1

    fun of(passage: Passage, keywords: List<String>): Double {
        if (keywords.isEmpty()) return 0.0
        val body = "${passage.heading} ${passage.text}"
        val found = keywords.count { containsWhole(body, it) }
        val title = if (keywords.any { containsWhole(passage.sourceTitle, it) }) TITLE_BONUS else 0.0
        return found.toDouble() / keywords.size + title
    }

    private fun containsWhole(haystack: String, keyword: String): Boolean {
        val k = keyword.trim()
        if (k.isEmpty()) return false
        return Regex("(?<![\\p{L}\\p{N}])${Regex.escape(k)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(haystack)
    }
}
