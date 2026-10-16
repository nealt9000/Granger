package app.granger.core.ai

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Runs [block] and reports the process's peak resident memory (RSS, MB) observed while it ran. */
interface MemorySampler {
    suspend fun <T> peakRssMb(block: suspend () -> T): Pair<T, Int>
}

/**
 * Samples VmRSS from /proc/self/status every [intervalMs] while the block runs. Reading one status line is
 * cheap, unlike Debug.getMemoryInfo, which walks smaps and would slow decoding with multi-GB models mapped.
 */
class RssMemorySampler(private val intervalMs: Long = 250) : MemorySampler {
    override suspend fun <T> peakRssMb(block: suspend () -> T): Pair<T, Int> = coroutineScope {
        val peakKb = AtomicInteger(currentRssKb())
        val sampler = launch(Dispatchers.Default) {
            while (isActive) {
                peakKb.accumulateAndGet(currentRssKb()) { a, b -> maxOf(a, b) }
                delay(intervalMs)
            }
        }
        try {
            val result = block()
            peakKb.accumulateAndGet(currentRssKb()) { a, b -> maxOf(a, b) }
            result to peakKb.get() / 1024
        } finally {
            sampler.cancel()
        }
    }

    private fun currentRssKb(): Int = parseVmRssKb(File("/proc/self/status").readText()) ?: 0
}

/** Parses the VmRSS line (kB) from /proc/self/status. */
fun parseVmRssKb(status: String): Int? =
    status.lineSequence()
        .firstOrNull { it.startsWith("VmRSS:") }
        ?.removePrefix("VmRSS:")
        ?.trim()
        ?.substringBefore(' ')
        ?.toIntOrNull()
