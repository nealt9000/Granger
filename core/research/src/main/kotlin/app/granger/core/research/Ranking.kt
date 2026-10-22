package app.granger.core.research

import app.granger.core.text.Chunkers
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
    // Dense retrieval (Plan R1 Task 7, the tuning split's sweep in `tools/eval/merge.py`): see [Ranking.hybrid].
    val denseAlpha: Double = 0.1,
    val cosineFloor: Double = 0.74,
    val denseGate: Double = 0.78,
    val lexicalGate: Double = 1.0,
)

/** Picks which passages go into the prompt: a cheap lexical preselect, then a threshold on embeddings or keywords. */
object Ranking {
    val NO_WEIGHT: (Passage) -> Double = { 1.0 }

    /** Best-first by lexical score then search score, at most [RankingConfig.perDocument] per article or book chapter
     * (a source and location), not per pack. Media passages never take one of the [RankingConfig.preselect] slots:
     * the other passages are preselected exactly as if there were none, and the media ones (the finder sends at most
     * two) are carried alongside, in their best-first place, for ranking and the media cap to judge.
     * [weight] multiplies each passage's lexical score (Plan R0's debug source weighting; none in the app). */
    fun preselect(passages: List<Passage>, keywords: List<String>, config: RankingConfig, weight: (Passage) -> Double = NO_WEIGHT): List<Passage> {
        val lexical = LexicalScore.scorer(keywords)
        val score = { p: Passage -> lexical(p) * weight(p) }
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

    /**
     * Keyword passages ([found], preselected) and dense hits ([dense], each with its [Passage.cosine]) in one list, best
     * first; a port of `gated(hybrid(0.1, 0.74), 0.78, 1.0)` in `tools/eval/merge.py`, which chose it.
     *
     * Nothing comes back unless the best dense hit reaches [RankingConfig.denseGate] or a keyword passage matches every
     * keyword ([RankingConfig.lexicalGate]): an unanswerable question rarely clears either, and an empty list declines it.
     * Otherwise each keyword passage above [RankingConfig.lexicalThreshold] scores `α·lexical + (1−α)·cosine`, the cosine
     * of the dense hit with the same text or the floor when it has none, and each dense hit above
     * [RankingConfig.cosineFloor] scores the same way. A passage both found is listed once. [weight] multiplies the
     * lexical score and the cosine alike, and at most [RankingConfig.perDocument] are kept per article or chapter.
     */
    fun hybrid(
        found: List<Passage>,
        dense: List<Passage>,
        keywords: List<String>,
        config: RankingConfig,
        weight: (Passage) -> Double = NO_WEIGHT,
    ): List<Passage> {
        val lexical = LexicalScore.scorer(keywords)
        val kept = found.map { it to lexical(it) * weight(it) }
            .sortedByDescending { it.second }
            .filter { it.second >= config.lexicalThreshold }
        val weighted = dense.map { it to it.cosine!! * weight(it) }
        val bestCosine = weighted.maxOfOrNull { it.second } ?: 0.0
        val bestLexical = kept.maxOfOrNull { it.second } ?: 0.0
        if (bestCosine < config.denseGate && bestLexical < config.lexicalGate) return emptyList()

        val a = config.denseAlpha
        // The last hit with a text wins, as in merge.py; the same text twice is the same chunk in two packs.
        val cosineOf = dense.associate { textKey(it) to it.cosine!! }
        val scored = kept.map { (p, lex) ->
            val c = cosineOf[textKey(p)]
            p to (if (c != null) a * lex + (1 - a) * c * weight(p) else a * lex + (1 - a) * config.cosineFloor)
        } + weighted.filter { it.second >= config.cosineFloor }.map { (p, cos) ->
            p to a * lexical(p) * weight(p) + (1 - a) * cos
        }
        val seen = HashSet<String>()
        val perDocument = HashMap<Pair<String, String>, Int>()
        return scored.sortedByDescending { it.second }
            .map { it.first }
            .filter { seen.add(textKey(it)) }
            .filter { perDocument.merge(it.sourceId to it.location, 1, Int::plus)!! <= config.perDocument }
    }

    private val SPACES = Regex("[${Chunkers.SPACE}]+")

    /** A passage's text with its whitespace runs folded, as `merge.text_key` does: how a dense hit meets its keyword twin. */
    fun textKey(p: Passage): String = p.text.replace(SPACES, " ").trim()

    /** Above [RankingConfig.lexicalThreshold], best first; at most [limit]. The [weight]ed score is the one thresholded. */
    fun byLexical(
        candidates: List<Passage>,
        keywords: List<String>,
        config: RankingConfig,
        limit: Int = config.large,
        weight: (Passage) -> Double = NO_WEIGHT,
    ): List<Passage> {
        val lexical = LexicalScore.scorer(keywords)
        val score = { p: Passage -> lexical(p) * weight(p) }
        return candidates
            .map { it to score(it) }
            .filter { it.second >= config.lexicalThreshold }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }
}
