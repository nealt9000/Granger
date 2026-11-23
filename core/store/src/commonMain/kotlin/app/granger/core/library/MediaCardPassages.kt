package app.granger.core.library

import app.granger.core.research.Keywords
import app.granger.core.research.LexicalScore
import app.granger.core.research.Passage
import app.granger.core.research.ResearchLanguage
import kotlinx.coroutines.CancellationException

/** Works asked of the media index for each keyword, before the best [mediaCardPassages] are picked. */
internal const val MEDIA_PER_KEYWORD = 6

/**
 * Albums and audiobooks that hold every word of some keyword as whole words (the cards' rule), best by the
 * keywords' lexical score, at most [mediaCards]. A keyword of several words that matches nothing is tried word by
 * word, stopwords left out: the rewrite's "money from" still finds the song *Money*.
 *
 * Shared by both phones' [PackPassageFinder] `media` (moved from Android's `LibraryPassageFinder` in Plan I7 Task 7b).
 */
suspend fun mediaCardPassages(index: MediaIndex, mediaCards: Int, words: List<String>, languages: List<ResearchLanguage>): List<Passage> {
    if (mediaCards <= 0) return emptyList()
    return try {
        val works = words.flatMap { keyword ->
            index.works(listOf(keyword), MEDIA_PER_KEYWORD).ifEmpty {
                val parts = Keywords.words(keyword)
                if (parts.size < 2) emptyList()
                else parts.filterNot { Keywords.isStopword(it, languages) }.flatMap { index.works(listOf(it), MEDIA_PER_KEYWORD) }
            }
        }.distinctBy { it.id }
        val score = LexicalScore.scorer(words, languages)
        // Stable: equal scores keep the index's order. Scored as whole works, so focusing a card on a song or
        // chapter that matched doesn't change which works are kept.
        works.map { it to score(MediaCards.passage(it)) }.sortedByDescending { it.second }.take(mediaCards)
            .map { (work, _) -> MediaCards.passage(work, MediaCards.bestPart(work, words, languages)) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }
}
