package app.granger.core.research

/**
 * Plan R0 Task 3, on in the app since Plan R1 (`tools/eval/kinds.py`): on a question that isn't about literature, a
 * passage from a fiction book ([fiction]: its book ids, read once per question) scores [weight] times as much, lexically
 * and by cosine. A question is about literature if it has a cue word, or a keyword names a fiction work the search found.
 */
class FictionWeight(private val fiction: () -> Set<String>, private val weight: Double = DEFAULT) : SourceWeight {
    override fun of(question: String, keywords: List<String>, found: List<Passage>): (Passage) -> Double {
        val ids = fiction()
        if (ids.isEmpty() || literary(question, keywords, found, ids)) return Ranking.NO_WEIGHT
        return { if (it.sourceId in ids) weight else 1.0 }
    }

    private fun literary(question: String, keywords: List<String>, found: List<Passage>, ids: Set<String>): Boolean {
        if (Keywords.words(question).any { it.lowercase() in LITERARY }) return true
        val titles = found.filter { it.sourceId in ids }.map { p -> Keywords.words(p.sourceTitle).map { it.lowercase() }.toSet() }
        return keywords.any { k ->
            val words = Keywords.words(k).map { it.lowercase() }.filterNot(Keywords::isStopword)
            words.isNotEmpty() && titles.any { t -> words.all { it in t } }
        }
    }

    companion object {
        /** R0's choice: it removes novels from factual questions' passages and costs no recall at 0.7 or lower. */
        const val DEFAULT = 0.7

        val LITERARY = setOf(
            "novel", "novels", "story", "stories", "tale", "tales", "poem", "poems", "poetry", "poet", "play", "plays", "playwright",
            "character", "characters", "chapter", "scene", "act", "stanza", "line", "lines", "quote", "quotes", "say", "says",
            "said", "narrator", "hero", "heroine", "fiction", "author", "wrote", "written", "book", "books", "literature",
            "opera", "libretto", "verse", "verses",
        )
    }
}
