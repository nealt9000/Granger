package app.granger.core.research

/**
 * How well a passage matches the rewrite keywords, independent of the search engine's own score. Each keyword counts
 * for the share of its words that are there, so "portia quote" half-matches a passage that names Portia. Stopwords
 * don't count.
 */
object LexicalScore {
    private const val TITLE_BONUS = 0.1
    private const val LEAD_BONUS = 0.3

    fun of(passage: Passage, keywords: List<String>): Double = scorer(keywords)(passage)

    /**
     * [of] for many passages: the keyword patterns are built once. Every keyword word weighs the same; word rarity is
     * not used, since in a search's results the question's own topic word is in nearly every passage and would count least.
     * An article's opening passage (see [isLead]) that matches at least one word gets [LEAD_BONUS].
     */
    fun scorer(keywords: List<String>): (Passage) -> Double {
        val groups = keywords
            .map { k -> Keywords.words(k).map { it.lowercase() }.filterNot(Keywords::isStopword).distinct() }
            .filter { it.isNotEmpty() }
        if (groups.isEmpty()) return { 0.0 }
        val patterns = groups.flatten().distinct().associateWith(::wholeWord)
        return { passage ->
            val text = body(passage)
            val present = patterns.filterValues { it.containsMatchIn(text) }.keys
            val inTitle = groups.any { group -> group.all { patterns.getValue(it).containsMatchIn(passage.sourceTitle) } }
            // A book's title is part of what it says ("bible, money" finds the King James text); an article's body already names it.
            val titleWords = if (passage.kind == SourceKind.BOOK) {
                patterns.filterValues { it.containsMatchIn(passage.sourceTitle) }.keys
            } else emptySet()
            val found = groups.sumOf { group ->
                if (group.all { it in titleWords }) 1.0 else group.count { it in present }.toDouble() / group.size
            }
            found / groups.size +
                (if (inTitle) TITLE_BONUS else 0.0) +
                (if (found > 0 && isLead(passage)) LEAD_BONUS else 0.0)
        }
    }

    /** The text under an article's own title: its heading is blank or the title. Books have no such passage. */
    private fun isLead(passage: Passage): Boolean =
        passage.kind == SourceKind.ARTICLE &&
            (passage.heading.isBlank() || passage.heading.trim().equals(passage.sourceTitle.trim(), ignoreCase = true))

    private fun body(passage: Passage) = "${passage.heading} ${passage.text}"

    private fun wholeWord(word: String) =
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(word)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
}
