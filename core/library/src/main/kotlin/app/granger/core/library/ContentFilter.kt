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

/**
 * The flags as the volunteered screens apply them: only while Settings' switch is [on] (on by default), and against the
 * QID indexes now installed ([indexes], set whenever they're reopened). Search, an opened article and its own header
 * never ask.
 */
class ContentFilter(private val on: () -> Boolean) {
    @Volatile var indexes: QidIndexes? = null

    /** The item is kept off volunteered lists. */
    fun hides(qid: Long): Boolean = on() && indexes?.flags?.hides(qid) == true

    /** The item's plate shows no picture on a volunteered screen. */
    fun noPicture(qid: Long): Boolean = on() && indexes?.flags?.noPicture(qid) == true

    /** [hides] for an article, by its pack and path; false when its pack has no QID index. */
    fun hides(packId: String, path: String): Boolean = on() && qidOf(packId, path)?.let { indexes?.flags?.hides(it) } == true

    /** [noPicture] for an article, by its pack and path; false when its pack has no QID index. */
    fun noPicture(packId: String, path: String): Boolean = on() && qidOf(packId, path)?.let { indexes?.flags?.noPicture(it) } == true

    private fun qidOf(packId: String, path: String): Long? {
        val i = indexes ?: return null
        if (i.flags.isEmpty) return null
        return i.qidOf(packId, path) ?: i.qidOf(packId, path.removePrefix("A/"))
    }

    companion object {
        /** Filters nothing: tests, and builds before the switch is wired. */
        val OFF = ContentFilter { false }
    }
}
