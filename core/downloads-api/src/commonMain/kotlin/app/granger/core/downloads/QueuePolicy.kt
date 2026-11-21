package app.granger.core.downloads

sealed interface EnqueueResult {
    data object Queued : EnqueueResult
    data object AlreadyActive : EnqueueResult
    data object AlreadyInstalled : EnqueueResult

    /** The volume is [neededBytes] short, counting other queued downloads and the reserve. */
    data class NoSpace(val neededBytes: Long) : EnqueueResult
}

object QueuePolicy {
    /** Left free on every volume so the phone keeps working. */
    const val RESERVE_BYTES = 256L * 1024 * 1024

    /**
     * The check made when queueing (spec "Insufficient space"): this download, the rest still to come on the same
     * volume, and the reserve must fit in its free space. There is no storage cap (the fair deal, 2026-11-02).
     */
    fun check(itemBytes: Long, freeBytes: Long, queuedOnVolumeBytes: Long): EnqueueResult {
        val needed = itemBytes + queuedOnVolumeBytes + RESERVE_BYTES - freeBytes
        if (needed > 0) return EnqueueResult.NoSpace(needed)
        return EnqueueResult.Queued
    }
}
