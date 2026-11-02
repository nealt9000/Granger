package app.granger.core.research

import kotlin.math.sqrt

/** The kinds of question the owl reacts to; [DEFAULT] when nothing is confident. */
enum class ReactionTopic { DEFAULT, HISTORY, SCIENCE, LITERATURE, SCRIPTURE, WORDS, PLACES, MATHS, HOW_TO, MEDICAL, FOLLOW_UP }

/**
 * Topic centroids: the mean query vector of each topic's anchor questions (tools/owl/anchors.json), embedded by the
 * phone's EmbeddingGemma. A question takes the nearest topic when its cosine is at least [floor] and beats the
 * runner-up by [margin]; else [ReactionTopic.DEFAULT]. MEDICAL nearest is MEDICAL whatever the scores, so a health
 * question the lexicon misses still gets the plain lines.
 */
class TopicCentroids(private val topics: Map<ReactionTopic, FloatArray>, private val floor: Float, private val margin: Float) {
    init {
        require(topics.size >= 2) { "need two topics or more" }
        require(topics.values.map { it.size }.toSet().size == 1) { "centroids differ in size" }
    }

    fun classify(question: FloatArray): ReactionTopic {
        val n = norm(question)
        if (n == 0f) return ReactionTopic.DEFAULT
        val scored = topics.map { (topic, c) -> topic to cosine(question, c, n) }.sortedByDescending { it.second }
        val (topic, best) = scored[0]
        return when {
            topic == ReactionTopic.MEDICAL -> ReactionTopic.MEDICAL
            best >= floor && best - scored[1].second >= margin -> topic
            else -> ReactionTopic.DEFAULT
        }
    }

    private fun cosine(q: FloatArray, c: FloatArray, qn: Float): Float {
        val dims = minOf(q.size, c.size)
        var dot = 0f
        var cn = 0f
        for (i in 0 until dims) {
            dot += q[i] * c[i]
            cn += c[i] * c[i]
        }
        return if (cn == 0f) 0f else dot / (qn * sqrt(cn))
    }

    private fun norm(v: FloatArray): Float = sqrt(v.fold(0f) { s, x -> s + x * x })

    /** The asset tools/owl/topics.py writes, with the embedder it was built from ([Parsed.embedderSha]). */
    data class Parsed(val centroids: TopicCentroids, val embedderSha: String)

    companion object {
        /**
         * Lines of `key value...`: `embedder SHA`, `floor F`, `margin M`, then one `TOPIC x1 x2 ...` per topic; `#` lines
         * are comments. Throws [IllegalArgumentException] on anything else.
         */
        fun parse(text: String): Parsed {
            var sha: String? = null
            var floor: Float? = null
            var margin: Float? = null
            val topics = LinkedHashMap<ReactionTopic, FloatArray>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
                when (parts[0]) {
                    "embedder" -> sha = parts.getOrNull(1)
                    "floor" -> floor = parts.getOrNull(1)?.toFloatOrNull()
                    "margin" -> margin = parts.getOrNull(1)?.toFloatOrNull()
                    else -> {
                        val topic = ReactionTopic.entries.firstOrNull { it.name == parts[0] }
                            ?: throw IllegalArgumentException("unknown topic ${parts[0]}")
                        topics[topic] = FloatArray(parts.size - 1) { i ->
                            parts[i + 1].toFloatOrNull() ?: throw IllegalArgumentException("bad number in $topic")
                        }
                    }
                }
            }
            require(sha != null && floor != null && margin != null) { "embedder, floor and margin are required" }
            return Parsed(TopicCentroids(topics, floor!!, margin!!), sha!!)
        }
    }
}

/** The topics a question gets without the embedder: health (the lexicon) and follow-ups, decided at once. */
object OwlReaction {
    fun lexical(question: String, priorQuestion: String?): ReactionTopic? = when {
        Medical.looksMedical(question) || PersonalMedical.strong(question) -> ReactionTopic.MEDICAL
        priorQuestion != null && FollowUp.refersBack(question) -> ReactionTopic.FOLLOW_UP
        else -> null
    }
}
