package app.granger.core.library


/**
 * The flags as the volunteered screens apply them: only while Settings' switch is [on] (on by default), and against the
 * QID indexes now installed ([indexes], set whenever they're reopened). Search, an opened article and its own header
 * never ask.
 */
class ContentFilter(private val on: () -> Boolean) {
    @kotlin.concurrent.Volatile var indexes: QidIndexes? = null

    /** Settings' switch: flags that come with their item (topic records, Plan O6) are applied only while it's on. */
    val isOn: Boolean get() = on()

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
