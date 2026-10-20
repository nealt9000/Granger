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
    /** At most this many media passages reach the answer; [mediaCapAsked] when the question asks about media ([MediaCues]). */
    val mediaCap: Int = 1,
    val mediaCapAsked: Int = 2,
)

/** Picks which passages go into the prompt: a cheap lexical preselect, then a threshold on embeddings or keywords. */
object Ranking {
    /** Best-first by lexical score then search score, at most [RankingConfig.perDocument] per article or book chapter
     * (a source and location), not per pack. Media passages never take one of the [RankingConfig.preselect] slots:
     * the other passages are preselected exactly as if there were none, and the media ones (the finder sends at most
     * two) are carried alongside, in their best-first place, for ranking and the media cap to judge. */
    fun preselect(passages: List<Passage>, keywords: List<String>, config: RankingConfig): List<Passage> {
        val score = LexicalScore.scorer(keywords)
        // Scored once each: a sort comparator would score every passage many times over.
        val sorted = passages
            .map { it to score(it) }
            .sortedWith(compareByDescending<Pair<Passage, Double>> { it.second }.thenByDescending { it.first.searchScore })
            .map { it.first }
        // Each kind fills its own slots; the result keeps the best-first order across both, so ties stay as they were.
        val perDocument = HashMap<Pair<String, String>, Int>()
        var others = 0
        var media = 0
        return sorted.filter { p ->
            val isMedia = p.kind == SourceKind.MEDIA
            if ((if (isMedia) media else others) >= config.preselect) return@filter false
            val seen = perDocument.merge(p.sourceId to p.location, 1, Int::plus)!!
            (seen <= config.perDocument).also { kept -> if (kept) { if (isMedia) media++ else others++ } }
        }
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

    /**
     * Keeps the best [RankingConfig.mediaCap] media passages ([RankingConfig.mediaCapAsked] when [asked]) and drops the
     * rest, so each dropped one gives its place to the next passage. Every other passage keeps its order. Applied to the
     * ranked list before it is cut to [RankingConfig.large].
     */
    fun capMedia(passages: List<Passage>, asked: Boolean, config: RankingConfig): List<Passage> {
        val cap = if (asked) config.mediaCapAsked else config.mediaCap
        var media = 0
        return passages.filter { it.kind != SourceKind.MEDIA || ++media <= cap }
    }

    /**
     * When the question asks about media ([asked]), the best media passage of [found] (preselect's order: the finder's
     * lexical best) goes first in [ranked], whether or not ranking kept it. Otherwise a card for the very album or book
     * the question names loses to the encyclopedia article about it (m3-media-1: media cited on 3 of 10).
     * Otherwise [ranked] is returned as it is.
     */
    fun liftMedia(ranked: List<Passage>, found: List<Passage>, asked: Boolean): List<Passage> {
        if (!asked) return ranked
        val best = found.firstOrNull { it.kind == SourceKind.MEDIA } ?: return ranked
        return listOf(best) + ranked.filter { it != best }
    }

    /** [vectors] lines up with [candidates]. At most [limit] are kept. */
    fun byEmbedding(
        candidates: List<Passage>,
        query: FloatArray,
        vectors: List<FloatArray>,
        config: RankingConfig,
        limit: Int = config.large,
    ): List<Passage> =
        candidates.indices
            .map { it to cosine(query, vectors[it]) }
            .filter { it.second >= config.cosineThreshold }
            .sortedByDescending { it.second }
            .take(limit)
            .map { candidates[it.first] }

    /** Above [RankingConfig.lexicalThreshold], best first; at most [limit]. */
    fun byLexical(
        candidates: List<Passage>,
        keywords: List<String>,
        config: RankingConfig,
        limit: Int = config.large,
    ): List<Passage> {
        val score = LexicalScore.scorer(keywords)
        return candidates
            .map { it to score(it) }
            .filter { it.second >= config.lexicalThreshold }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }
}
