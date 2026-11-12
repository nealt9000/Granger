package app.granger.core.research

import app.granger.core.text.Spellings

/**
 * How well a passage matches the rewrite keywords, independent of the search engine's own score. Each keyword counts
 * for the share of its words that are there, so "portia quote" half-matches a passage that names Portia. Stopwords
 * don't count.
 */
object LexicalScore {
    private const val TITLE_BONUS = 0.1
    private const val LEAD_BONUS = 0.3

    /** [languages] are the question's ([ResearchLanguage.of]): whose stopwords don't count. English alone by default. */
    fun of(passage: Passage, keywords: List<String>, languages: List<ResearchLanguage> = Keywords.ENGLISH): Double =
        scorer(keywords, languages)(passage)

    /**
     * [of] for many passages: the keyword patterns are built once. Every keyword word weighs the same; word rarity is
     * not used, since in a search's results the question's own topic word is in nearly every passage and would count least.
     * An article's opening passage (see [isLead]) that matches at least one word gets [LEAD_BONUS].
     *
     * For a Spanish question ([ResearchLanguage.foldsVowels]) the keyword words, each passage's text (once per passage)
     * and its title words are matched with accented vowels folded ([ResearchLanguage.foldVowels]), so "fundo bogota"
     * finds "fundó" and "Bogotá" while "ano" still misses "año". An English question's matching is untouched.
     */
    fun scorer(keywords: List<String>, languages: List<ResearchLanguage> = Keywords.ENGLISH): (Passage) -> Double {
        val fold = ResearchLanguage.foldsVowels(languages)
        // Each keyword is a list of alternatives (one, unless the rewrite gave `wine|strong drink`), each a list of words.
        val groups = keywords
            .map { k ->
                // Each spelling of an alternative ("grey horse", "gray horse") is an alternative too.
                Keywords.variants(k).flatMap { Spellings.forms(it) }.distinct().map { v ->
                    val words = Keywords.words(v).map { it.lowercase() }.filterNot { Keywords.isStopword(it, languages) }
                    (if (fold) words.map(ResearchLanguage::foldVowels) else words).distinct()
                }
                    .filter { it.isNotEmpty() }
            }
            .filter { it.isNotEmpty() }
        if (groups.isEmpty()) return { 0.0 }
        val patterns = groups.flatten().flatten().distinct().associateWith(::wholeWord)
        return { passage ->
            val text = if (fold) ResearchLanguage.foldVowels(body(passage)) else body(passage)
            val present = patterns.filterValues { it.containsMatchIn(text) }.keys
            val titleWords = Keywords.words(if (fold) ResearchLanguage.foldVowels(passage.sourceTitle) else passage.sourceTitle).map { it.lowercase() }.toSet()
            // A title only adds a small bonus: crediting a whole keyword let "sister" pass every passage of Sister Carrie (tune-4).
            // Keywords.alternatives' aliases are plain ASCII, so a folded word's alternatives need no folding.
            val inTitle = groups.any { alts -> alts.any { words -> words.all { w -> Keywords.alternatives(w).any { it in titleWords } } } }
            // A keyword counts for its best alternative.
            val found = groups.sumOf { alts -> alts.maxOf { words -> words.count { it in present }.toDouble() / words.size } }
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
