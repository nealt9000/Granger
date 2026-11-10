package app.granger.core.research

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
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

    /** [embedMs], [denseMs] (the dense search after the embedding) and [keywordMs] as measured; 0 when not run. */
    data class Result(
        val matches: List<Match>,
        val keywords: List<String>,
        val byMeaning: Boolean,
        val embedMs: Long = 0,
        val denseMs: Long = 0,
        val keywordMs: Long = 0,
        /** The hybrid (or keyword-only) ranking; false for the dense-only first step of [stream]. */
        val final: Boolean = false,
    )

    /**
     * Loads the embedder ahead of the first query (Plan SQ, the Pixel 6: the first query's embedding took 2.6 s against
     * ~0.12 s after). Does nothing without one; never throws.
     */
    suspend fun warm() {
        try {
            embed?.invoke("warm")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** The final ranking ([stream]'s last result). */
    suspend fun find(query: String, limit: Int = LIMIT): Result = stream(query, limit).last()

    /**
     * Best matches in up to two steps (Plan SQ, the Pixel 6: the dense search answers in ~1.7 s, the keyword search in
     * ~3.5 s): first the dense hits alone, ranked by cosine (offline that is 66% article @5 against the hybrid's 68%),
     * then, once the keyword search lands, the hybrid ranking. Without dense hits there is one result, the keyword one.
     */
    fun stream(query: String, limit: Int = LIMIT): Flow<Result> = channelFlow {
        val languages = ResearchLanguage.of(query)
        val keywords = Keywords.extract(query, languages = languages)
        if (keywords.isEmpty()) {
            send(Result(emptyList(), keywords, false, final = true))
            return@channelFlow
        }
        val inLanguage = { p: Passage -> p.language == null || p.language in languages }
        val keywordDone = CompletableDeferred<Unit>()
        val meaning = async {
            byMeaning(query)?.filter(inLanguage)?.also { hits ->
                // Shown while the keyword search runs; skipped when the keywords already finished.
                if (!keywordDone.isCompleted) {
                    val weight = sourceWeight?.of(query, keywords, hits) ?: Ranking.NO_WEIGHT
                    val ranked = hits.filter { it.cosine != null }.sortedByDescending { it.cosine!! * weight(it) }
                        .distinctBy { it.sourceId to it.location }.take(limit)
                    send(Result(ranked.map { Match(it, sentence(it, keywords, languages)) }, keywords, byMeaning = true, embedMs = lastEmbedMs, denseMs = lastDenseMs))
                }
            }
        }
        val keywordStart = System.nanoTime()
        val words = finder.find(Keywords.searchTerms(keywords), languages).filter(inLanguage)
        val keywordMs = (System.nanoTime() - keywordStart) / 1_000_000
        keywordDone.complete(Unit)
        val hits = meaning.await()
        val weight = sourceWeight?.of(query, keywords, words + hits.orEmpty()) ?: Ranking.NO_WEIGHT
        val topical = keywords.filterNot(YearFacts::cueOnly).ifEmpty { keywords }
        val wide = config.copy(preselect = POOL, small = POOL, large = POOL)
        val found = Ranking.preselect(words, topical, wide, weight, languages)
        val merged = hits?.let { Ranking.hybrid(found, it, topical, wide, weight, languages) }.orEmpty()
        val ranked = merged.ifEmpty { Ranking.byLexical(found, topical, wide, limit = POOL, weight = weight, languages = languages) }
        val perArticle = ranked.distinctBy { it.sourceId to it.location }.take(limit)
        send(
            Result(
                perArticle.map { Match(it, sentence(it, topical, languages)) }, keywords, byMeaning = hits != null,
                embedMs = lastEmbedMs, denseMs = lastDenseMs, keywordMs = keywordMs, final = true,
            ),
        )
    }

    @Volatile private var lastEmbedMs = 0L
    @Volatile private var lastDenseMs = 0L

    private suspend fun byMeaning(query: String): List<Passage>? {
        lastEmbedMs = 0
        lastDenseMs = 0
        val finder = dense ?: return null
        val embedder = embed ?: return null
        return try {
            val start = System.nanoTime()
            val vector = embedder(query)
            val embedded = System.nanoTime()
            lastEmbedMs = (embedded - start) / 1_000_000
            vector?.let { finder.find(it) }.also { lastDenseMs = (System.nanoTime() - embedded) / 1_000_000 }?.takeIf { it.isNotEmpty() }
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
