package app.granger.core.player

/** How far an audiobook steps back on resume, by how long it was paused (spec §7; Builder's call in Plan M1). */
object AutoRewind {
    /** Paused under 10 s → 0; under 5 min → 3 s; under 1 h → 10 s; 1 h or more → 30 s. A boundary takes the longer step. */
    fun rewindMs(pausedForMs: Long): Long = when {
        pausedForMs < 10_000L -> 0L
        pausedForMs < 300_000L -> 3_000L
        pausedForMs < 3_600_000L -> 10_000L
        else -> 30_000L
    }
}
