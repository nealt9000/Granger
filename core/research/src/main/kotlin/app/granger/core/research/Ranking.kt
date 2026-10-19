package app.granger.core.research

import kotlin.math.sqrt

data class RankingConfig(
    val preselect: Int = 12,
    val perDocument: Int = 3,
    val cosineThreshold: Float = 0.40f,
    // Set by sweep on the tune-2 corpus (Plan 9, 8d): recall is flat from 0.32 to 0.6, and 0.6 sends fewer unanswerable questions passages.
    val lexicalThreshold: Double = 0.6,
    val small: Int = 4,
    val large: Int = 8,
)

/** Picks which passages go into the prompt: a cheap lexical preselect, then a threshold on embeddings or keywords. */
object Ranking {
    /** Best-first by lexical score then search score, at most [RankingConfig.perDocument] per article or book chapter
     * (a source and location), not per pack. */
    fun preselect(passages: List<Passage>, keywords: List<String>, config: RankingConfig): List<Passage> {
        val perDocument = HashMap<Pair<String, String>, Int>()
        val score = LexicalScore.scorer(keywords)
        // Scored once each: a sort comparator would score every passage many times over.
        return passages
            .map { it to score(it) }
            .sortedWith(compareByDescending<Pair<Passage, Double>> { it.second }.thenByDescending { it.first.searchScore })
            .map { it.first }
            .filter { p ->
                val seen = perDocument.merge(p.sourceId to p.location, 1, Int::plus)!!
                seen <= config.perDocument
            }
            .take(config.preselect)
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vector sizes differ: ${a.size} and ${b.size}" }
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0f) 0f else dot / denom
    }

    /** [vectors] lines up with [candidates]. */
    fun byEmbedding(
        candidates: List<Passage>,
        query: FloatArray,
        vectors: List<FloatArray>,
        config: RankingConfig,
    ): List<Passage> =
        candidates.indices
            .map { it to cosine(query, vectors[it]) }
            .filter { it.second >= config.cosineThreshold }
            .sortedByDescending { it.second }
            .take(config.large)
            .map { candidates[it.first] }

    fun byLexical(
        candidates: List<Passage>,
        keywords: List<String>,
        config: RankingConfig,
    ): List<Passage> {
        val score = LexicalScore.scorer(keywords)
        return candidates
            .map { it to score(it) }
            .filter { it.second >= config.lexicalThreshold }
            .sortedByDescending { it.second }
            .take(config.large)
            .map { it.first }
    }
}
