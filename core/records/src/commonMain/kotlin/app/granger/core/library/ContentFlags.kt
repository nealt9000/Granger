package app.granger.core.library

/**
 * Adult-content flags (roadmap, "Carried forward from EX", Neal 2026-11-18), from the QID indexes' `flags` tables
 * (tools/adultflags). Granger doesn't volunteer it, and never hides what someone searches for or opens: a [TOPIC] item is
 * left out of every list a screen fills by rank or at random, and a [PICTURE] item keeps its plate without its picture.
 */
class ContentFlags(private val topics: Set<Long>, private val pictures: Set<Long>) {
    fun hides(qid: Long): Boolean = qid in topics
    fun noPicture(qid: Long): Boolean = qid in topics || qid in pictures
    val isEmpty: Boolean get() = topics.isEmpty() && pictures.isEmpty()

    companion object {
        const val TOPIC = 1
        const val PICTURE = 2
        val NONE = ContentFlags(emptySet(), emptySet())
    }
}
