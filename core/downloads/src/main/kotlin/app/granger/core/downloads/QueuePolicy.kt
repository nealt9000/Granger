package app.granger.core.downloads

sealed interface EnqueueResult {
    data object Queued : EnqueueResult
    data object AlreadyActive : EnqueueResult
    data object AlreadyInstalled : EnqueueResult

    /** The volume is [neededBytes] short, counting other queued downloads and the reserve. */
    data class NoSpace(val neededBytes: Long) : EnqueueResult

    /** Packs (installed + queued + this one) would total [wouldUseBytes], over [capBytes]. */
    data class OverCap(val capBytes: Long, val wouldUseBytes: Long) : EnqueueResult
}

object QueuePolicy {
    /** Left free on every volume so the phone keeps working. */
    const val RESERVE_BYTES = 256L * 1024 * 1024

    /**
     * The checks made when queueing (spec "Storage cap", "Insufficient space"). The cap covers packs
     * only; models are excluded. An update's old snapshot ([replacedPackBytes]) is not counted twice.
     */
    fun check(
        itemBytes: Long,
        isPack: Boolean,
        installedPackBytes: Long,
        queuedPackBytes: Long,
        replacedPackBytes: Long,
        capBytes: Long,
        freeBytes: Long,
        queuedOnVolumeBytes: Long,
    ): EnqueueResult {
        if (isPack) {
            val wouldUse = installedPackBytes - replacedPackBytes + queuedPackBytes + itemBytes
            if (wouldUse > capBytes) return EnqueueResult.OverCap(capBytes, wouldUse)
        }
        val needed = itemBytes + queuedOnVolumeBytes + RESERVE_BYTES - freeBytes
        if (needed > 0) return EnqueueResult.NoSpace(needed)
        return EnqueueResult.Queued
    }
}
