package app.granger.eval

import app.granger.core.research.Keywords
import app.granger.core.research.Passage
import app.granger.core.research.SourceWeight

/**
 * Plan R0, Task 3 on the phone (`tools/eval/kinds.py`): on a question that isn't about literature, a passage from a
 * fiction book ([fiction]: book ids) scores [weight] times its lexical score. A question is about literature if it has a
 * cue word, or a keyword names a fiction work the search found.
 */
class FictionWeight(private val fiction: Set<String>, private val weight: Double) : SourceWeight {
    override fun of(question: String, keywords: List<String>, found: List<Passage>): (Passage) -> Double {
        if (literary(question, keywords, found)) return { 1.0 }
        return { if (it.sourceId in fiction) weight else 1.0 }
    }

    private fun literary(question: String, keywords: List<String>, found: List<Passage>): Boolean {
        if (Keywords.words(question).any { it.lowercase() in LITERARY }) return true
        val titles = found.filter { it.sourceId in fiction }.map { p -> Keywords.words(p.sourceTitle).map { it.lowercase() }.toSet() }
        return keywords.any { k ->
            val words = Keywords.words(k).map { it.lowercase() }.filterNot(Keywords::isStopword)
            words.isNotEmpty() && titles.any { t -> words.all { it in t } }
        }
    }

    companion object {
        val LITERARY = setOf(
            "novel", "novels", "story", "stories", "tale", "tales", "poem", "poems", "poetry", "poet", "play", "plays", "playwright",
            "character", "characters", "chapter", "scene", "act", "stanza", "line", "lines", "quote", "quotes", "say", "says",
            "said", "narrator", "hero", "heroine", "fiction", "author", "wrote", "written", "book", "books", "literature",
            "opera", "libretto", "verse", "verses",
        )
    }
}
