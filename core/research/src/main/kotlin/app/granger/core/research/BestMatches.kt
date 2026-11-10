package app.granger.core.research

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.cancellation.CancellationException

/**
 * Search's Best matches (Plan SQ Phase 4): the articles and book chapters that best answer a query, each with the passage
 * that matched and the sentence in it to show. Ask's retrieval with no model at all: the query's plain keywords
 * ([Keywords.extract]) through the per-word keyword search, the query's vector through the dense search, both at once,
 * merged by [Ranking.hybrid] (fiction weighted by [sourceWeight]), one row per article or chapter. Offline, Ask's
 * recorded retrieval ranked this way put the answer's article in the top 5 for 68% of the dev questions
 * (`tools/eval/ask_articles.py`); keywords alone, 25%.
 *
 * Where Ask's gate would decline (no dense hit or keyword passage confident enough), Search still shows the keyword
 * ranking: a search lists its best guesses; it doesn't refuse.
 */
class BestMatches(
    private val finder: PassageFinder,
    /** The dense search and the query embedder; null (or either failing) leaves the keyword side alone. */
    private val dense: DenseFinder? = null,
    private val embed: (suspend (String) -> FloatArray?)? = null,
    private val config: RankingConfig = RankingConfig(),
    private val sourceWeight: SourceWeight? = null,
) {
    /** One row: an article or chapter, its best passage, and the [sentence] of it (character range) to highlight. */
    data class Match(val passage: Passage, val sentence: IntRange?)

    data class Result(val matches: List<Match>, val keywords: List<String>, val byMeaning: Boolean)

    suspend fun find(query: String, limit: Int = LIMIT): Result = coroutineScope {
        val languages = ResearchLanguage.of(query)
        val keywords = Keywords.extract(query, languages = languages)
        if (keywords.isEmpty()) return@coroutineScope Result(emptyList(), keywords, false)
        val inLanguage = { p: Passage -> p.language == null || p.language in languages }
        val meaning = async { byMeaning(query)?.filter(inLanguage) }
        val words = finder.find(Keywords.searchTerms(keywords), languages).filter(inLanguage)
        val hits = meaning.await()
        val weight = sourceWeight?.of(query, keywords, words + hits.orEmpty()) ?: Ranking.NO_WEIGHT
        val topical = keywords.filterNot(YearFacts::cueOnly).ifEmpty { keywords }
        val wide = config.copy(preselect = POOL, small = POOL, large = POOL)
        val found = Ranking.preselect(words, topical, wide, weight, languages)
        val merged = hits?.let { Ranking.hybrid(found, it, topical, wide, weight, languages) }.orEmpty()
        val ranked = merged.ifEmpty { Ranking.byLexical(found, topical, wide, limit = POOL, weight = weight, languages = languages) }
        val perArticle = ranked.distinctBy { it.sourceId to it.location }.take(limit)
        Result(perArticle.map { Match(it, sentence(it, topical, languages)) }, keywords, byMeaning = hits != null)
    }

    private suspend fun byMeaning(query: String): List<Passage>? {
        val finder = dense ?: return null
        val embedder = embed ?: return null
        return try {
            embedder(query)?.let { finder.find(it) }?.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val LIMIT = 10
        /** How many passages are ranked before one row per article is kept (merge.py's deep pool). */
        const val POOL = 40

        /**
         * The sentence of [passage] sharing the most of [keywords]' words (folded, stopwords left out), the earlier on a
         * tie; null when none shares any.
         */
        fun sentence(passage: Passage, keywords: List<String>, languages: List<ResearchLanguage>): IntRange? {
            val wanted = keywords.flatMap(Keywords::variants).flatMap(Keywords::words)
                .map { ResearchLanguage.fold(it) }.filterNot { Keywords.isStopword(it, languages) }.toSet()
            if (wanted.isEmpty()) return null
            val ranges = CitedQuote.sentences(passage.text, languages.first())
            return ranges.map { r -> r to Keywords.words(passage.text.substring(r)).count { ResearchLanguage.fold(it) in wanted } }
                .filter { it.second > 0 }
                .maxWithOrNull(compareBy<Pair<IntRange, Int>> { it.second }.thenByDescending { it.first.first })
                ?.first
        }
    }
}
