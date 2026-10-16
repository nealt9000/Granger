package app.granger.core.ai

import android.os.Debug
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Runs [block] and reports the process's peak memory (PSS, MB) observed while it ran. */
interface MemorySampler {
    suspend fun <T> peakPssMb(block: suspend () -> T): Pair<T, Int>
}

/** Samples this process's total PSS every [intervalMs] while the block runs. */
class PssMemorySampler(private val intervalMs: Long = 200) : MemorySampler {
    override suspend fun <T> peakPssMb(block: suspend () -> T): Pair<T, Int> = coroutineScope {
        val peakKb = AtomicInteger(currentPssKb())
        val sampler = launch(Dispatchers.Default) {
            while (isActive) {
                peakKb.accumulateAndGet(currentPssKb()) { a, b -> maxOf(a, b) }
                delay(intervalMs)
            }
        }
        try {
            val result = block()
            peakKb.accumulateAndGet(currentPssKb()) { a, b -> maxOf(a, b) }
            result to peakKb.get() / 1024
        } finally {
            sampler.cancel()
        }
    }

    private fun currentPssKb(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
}
