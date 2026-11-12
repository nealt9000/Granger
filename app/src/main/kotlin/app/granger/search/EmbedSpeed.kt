package app.granger.search

/**
 * How long search by meaning takes on this phone (Plan SQ Phase 6): the query's embedding plus the dense search over the
 * vectors, as Best matches report them. When the median of the last [WINDOW] searches is over [SLOW_MS], the phone is
 * [slow] and Best matches go keyword-only (the embedder is not asked), so a phone that can't keep up doesn't wait on
 * meaning for every query. On the Pixel 6 the step takes ~1.2 s (p95 2.1 s), the embedding alone ~0.12 s.
 *
 * The first search after a start is left out: it includes loading the embedder. A slow phone checks again after
 * [RETRY_AFTER] keyword-only searches (it may have been busy, a rebuild of book vectors after an update), with one search
 * by meaning; a fast result brings meaning back.
 */
class EmbedSpeed(private val slowMs: Long = SLOW_MS) {
    private val recent = ArrayDeque<Long>()
    private var seenFirst = false
    private var skipped = 0

    /** Whether the next search should embed its query. */
    @Synchronized
    fun useMeaning(): Boolean {
        if (!slow) return true
        skipped++
        if (skipped < RETRY_AFTER) return false
        skipped = 0
        return true
    }

    /** Records one search by meaning: [ms], its embedding and dense search together. */
    @Synchronized
    fun record(ms: Long) {
        if (!seenFirst) {
            seenFirst = true
            return
        }
        // A retry that comes back fast clears the record; a slow one keeps the phone slow.
        if (slow && ms <= slowMs) recent.clear()
        recent.addLast(ms)
        while (recent.size > WINDOW) recent.removeFirst()
    }

    val slow: Boolean
        @Synchronized get() = recent.size >= MIN_SEEN && recent.sorted()[recent.size / 2] > slowMs

    companion object {
        /** Twice the Pixel 6's p95 (2.1 s): the bar Best matches' first step was set to is 2.5 s at p95. */
        const val SLOW_MS = 4_000L
        const val WINDOW = 5
        const val MIN_SEEN = 3
        const val RETRY_AFTER = 20
    }
}
