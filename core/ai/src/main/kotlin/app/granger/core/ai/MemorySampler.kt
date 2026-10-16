package app.granger.core.ai

/** Runs [block] and reports the process's peak memory (PSS, MB) observed while it ran. */
interface MemorySampler {
    suspend fun <T> peakPssMb(block: suspend () -> T): Pair<T, Int>
}
