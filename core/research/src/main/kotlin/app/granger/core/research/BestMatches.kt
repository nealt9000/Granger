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
 * Ask's gate (decline when no dense hit or keyword passage is confident enough) is off: a search lists its best guesses;
 * it doesn't refuse.
 */
class BestMatches(
    private val finder: PassageFinder,
    /** The dense search and the query embedder; null (or either failing) leaves the keyword side alone. */
    private val dense: DenseFinder? = null,
    private val embed: (suspend (String) -> FloatArray?)? = null,
    private val config: RankingConfig = RankingConfig(),
    private val sourceWeight: SourceWeight? = null,
    /**
     * Which article a passage is, for one row per article: the app's maps a pack to its family ("wikipedia_en"), so one
     * article in Wikipedia and Wikipedia Physics is one row (Plan SQ: "Capillary action" took two of the first five).
     * Asked once per search.
     */
    private val articleKey: suspend () -> (Passage) -> Any = { { p: Passage -> p.sourceId to p.location } },
    /**
     * Plan SQ Step 4: the passages holding the query as a quote (the app's: book chunks with its words as a phrase), put
     * first in both steps. Ranked on keywords and meaning, a remembered line lost to articles sharing one word ("Alas,
     * poor Yorick" gave "Working poor"): 20% of quotes had their book in the first five, against 95% in Books.
     */
    private val quoted: (suspend (String) -> List<Passage>)? = null,
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
        val inLanguage = { p: Passage -> p.language == null || p.language in languages }
        val quote = async { quotes(query).filter(inLanguage) }
        // The quote's sentence: every word of the query counts, small ones too ("To be, or not to be").
        val quoteMatch = { p: Passage -> Match(p, sentence(p, listOf(query), languages, stopwords = false)) }
        if (keywords.isEmpty()) {
            send(Result(quote.await().take(limit).map(quoteMatch), keywords, false, final = true))
            return@channelFlow
        }
        val article = articleKey()
        val keywordDone = CompletableDeferred<Unit>()
        val meaning = async {
            byMeaning(query)?.filter(inLanguage)?.also { hits ->
                // Shown while the keyword search runs; skipped when the keywords already finished.
                if (!keywordDone.isCompleted) {
                    val ranked = byCosine(hits, sourceWeight?.of(query, keywords, hits) ?: Ranking.NO_WEIGHT, limit, article)
                    val pinned = quote.await()
                    val rows = withQuotes(query, pinned.map(quoteMatch), ranked.map { Match(it, sentence(it, keywords, languages)) })
                    send(Result(rows.distinctBy { article(it.passage) }.take(limit), keywords, byMeaning = true, embedMs = lastEmbedMs, denseMs = lastDenseMs))
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
        // No gate: Ask's declines an unsure question, but a search lists its best guesses (Plan SQ: gated, "how can a huge
        // steel ship float" lost its dense hits to an empty keyword list).
        val wide = config.copy(preselect = POOL, small = POOL, large = POOL, keywordCosine = KEYWORD_COSINE, denseGate = 0.0, lexicalGate = 0.0)
        val found = Ranking.preselect(words, topical, wide, weight, languages)
        val merged = hits?.let { Ranking.hybrid(found, it, topical, wide, weight, languages) }.orEmpty()
        // With dense hits all below the floor and no keyword passage, the first step's ranking stands.
        val ranked = merged.ifEmpty { Ranking.byLexical(found, topical, wide, limit = POOL, weight = weight, languages = languages) }
            .ifEmpty { hits?.let { byCosine(it, weight, POOL, article) }.orEmpty() }
        val rows = withQuotes(query, quote.await().map(quoteMatch), ranked.map { Match(it, sentence(it, topical, languages)) })
        send(
            Result(
                rows.distinctBy { article(it.passage) }.take(limit), keywords, byMeaning = hits != null,
                embedMs = lastEmbedMs, denseMs = lastDenseMs, keywordMs = keywordMs, final = true,
            ),
        )
    }

    /** Dense hits by weighted cosine, one per article or chapter: the first step's ranking. */
    private fun byCosine(hits: List<Passage>, weight: (Passage) -> Double, limit: Int, article: (Passage) -> Any): List<Passage> =
        hits.filter { it.cosine != null }.sortedByDescending { it.cosine!! * weight(it) }.distinctBy(article).take(limit)

    /**
     * [quotes] first, or, for a query under [QUOTE_FIRST_WORDS] words, after the first [SHORT_QUOTE_AT] [ranked] rows: a
     * three-word phrase in a book is as often a topic as a quote ("speed of light", "life of napoleon"), and its article
     * should lead.
     */
    private fun withQuotes(query: String, quotes: List<Match>, ranked: List<Match>): List<Match> =
        if (Keywords.words(query).size >= QUOTE_FIRST_WORDS) quotes + ranked
        else ranked.take(SHORT_QUOTE_AT) + quotes + ranked.drop(SHORT_QUOTE_AT)

    /** [quoted]'s passages for [query]; empty without it or when it fails. */
    private suspend fun quotes(query: String): List<Passage> =
        try {
            quoted?.invoke(query).orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
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
        /** [RankingConfig.keywordCosine] for Best matches. */
        const val KEYWORD_COSINE = 0.66
        /** The fewest words for a quote to lead Best matches; a shorter one comes in at [SHORT_QUOTE_AT]. */
        const val QUOTE_FIRST_WORDS = 4
        const val SHORT_QUOTE_AT = 2

        /**
         * The sentence of [passage] sharing the most of [keywords]' words (folded, stopwords left out unless [stopwords] is
         * false), the earlier on a tie; null when none shares any.
         */
        fun sentence(passage: Passage, keywords: List<String>, languages: List<ResearchLanguage>, stopwords: Boolean = true): IntRange? {
            val wanted = keywords.flatMap(Keywords::variants).flatMap(Keywords::words)
                .map { ResearchLanguage.fold(it) }.filterNot { stopwords && Keywords.isStopword(it, languages) }.toSet()
            if (wanted.isEmpty()) return null
            val ranges = CitedQuote.sentences(passage.text, languages.first())
            return ranges.map { r -> r to Keywords.words(passage.text.substring(r)).count { ResearchLanguage.fold(it) in wanted } }
                .filter { it.second > 0 }
                .maxWithOrNull(compareBy<Pair<IntRange, Int>> { it.second }.thenByDescending { it.first.first })
                ?.first
        }
    }
}
