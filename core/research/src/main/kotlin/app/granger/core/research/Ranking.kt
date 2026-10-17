package app.granger.core.research

import kotlin.math.sqrt

data class RankingConfig(
    val preselect: Int = 12,
    val perSource: Int = 3,
    val cosineThreshold: Float = 0.40f,
    val lexicalThreshold: Double = 0.50,
    val small: Int = 4,
    val large: Int = 8,
)

/** Picks which passages go into the prompt: a cheap lexical preselect, then a threshold on embeddings or keywords. */
object Ranking {
    /** Best-first by lexical score then search score, at most [RankingConfig.perSource] per source. */
    fun preselect(passages: List<Passage>, keywords: List<String>, config: RankingConfig): List<Passage> {
        val perSource = HashMap<String, Int>()
        return passages
            .sortedWith(
                compareByDescending<Passage> { LexicalScore.of(it, keywords) }.thenByDescending { it.searchScore },
            )
            .filter { p ->
                val seen = perSource.merge(p.sourceId, 1, Int::plus)!!
                seen <= config.perSource
            }
            .take(config.preselect)
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
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

    fun byLexical(candidates: List<Passage>, keywords: List<String>, config: RankingConfig): List<Passage> =
        candidates
            .map { it to LexicalScore.of(it, keywords) }
            .filter { it.second >= config.lexicalThreshold }
            .sortedByDescending { it.second }
            .take(config.large)
            .map { it.first }
}
