package app.granger.core.research

import kotlin.math.ln

/**
 * How well a passage matches the rewrite keywords, independent of the search engine's own score. Each keyword counts
 * for the share of its words that are there, so "portia quote" half-matches a passage that names Portia. Stopwords
 * don't count.
 */
object LexicalScore {
    private const val TITLE_BONUS = 0.1

    fun of(passage: Passage, keywords: List<String>): Double = scorer(keywords)(passage)

    /**
     * [of] for many passages: the keyword patterns are built once. Given a [corpus] (the passages a search found),
     * words are weighted by how rare they are in it, with a smoothed idf, and each keyword by the weight of its words.
     * A keyword about the question's form ("literature analysis") then counts for little when its words are everywhere.
     */
    fun scorer(keywords: List<String>, corpus: List<Passage> = emptyList()): (Passage) -> Double {
        val groups = keywords
            .map { k -> Keywords.words(k).map { it.lowercase() }.filterNot(Keywords::isStopword).distinct() }
            .filter { it.isNotEmpty() }
        if (groups.isEmpty()) return { 0.0 }
        val patterns = groups.flatten().distinct().associateWith(::wholeWord)
        val weight: (String) -> Double = if (corpus.isEmpty()) {
            { 1.0 }
        } else {
            val bodies = corpus.map(::body)
            val docs = patterns.mapValues { (_, regex) -> bodies.count { regex.containsMatchIn(it) } }
            ({ word -> ln((corpus.size + 1.0) / (docs.getValue(word) + 1)) + 1 })
        }
        val keywordWeight: (List<String>) -> Double = if (corpus.isEmpty()) { _ -> 1.0 } else { group -> group.sumOf(weight) }
        val total = groups.sumOf(keywordWeight)
        return { passage ->
            val text = body(passage)
            val present = patterns.filterValues { it.containsMatchIn(text) }.keys
            val found = groups.sumOf { group ->
                keywordWeight(group) * group.filter { it in present }.sumOf(weight) / group.sumOf(weight)
            }
            val inTitle = groups.any { group -> group.all { patterns.getValue(it).containsMatchIn(passage.sourceTitle) } }
            found / total + if (inTitle) TITLE_BONUS else 0.0
        }
    }

    private fun body(passage: Passage) = "${passage.heading} ${passage.text}"

    private fun wholeWord(word: String) =
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(word)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
}
