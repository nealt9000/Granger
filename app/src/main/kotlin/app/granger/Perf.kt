package app.granger

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock

/**
 * Timings for a performance pass: in a debug build each timed block logs how long it took under the tag GrangerPerf
 * (`adb logcat -s GrangerPerf`); a release build runs the block and nothing else.
 */
object Perf {
    const val TAG = "GrangerPerf"

    inline fun <T> time(label: String, block: () -> T): T {
        if (!BuildConfig.DEBUG) return block()
        val start = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            Log.d(TAG, "$label ${SystemClock.elapsedRealtime() - start} ms")
        }
    }

    /** A point in a longer load: [label] and the milliseconds since [since] (from [now]). */
    fun mark(label: String, since: Long) {
        if (BuildConfig.DEBUG) Log.d(TAG, "$label +${SystemClock.elapsedRealtime() - since} ms")
    }

    fun now(): Long = SystemClock.elapsedRealtime()
}

/**
 * [block] for each of these, several at a time ([limit]), the results in the items' order. For reads that are mostly
 * parsing (an article's card): a phone has the cores, and one after another they made a screen wait.
 */
suspend fun <T, R> Iterable<T>.side(limit: Int = 4, block: suspend (T) -> R): List<R> = kotlinx.coroutines.coroutineScope {
    val gate = kotlinx.coroutines.sync.Semaphore(limit)
    map { item -> async { gate.acquire(); try { block(item) } finally { gate.release() } } }.map { it.await() }
}

/**
 * A value that costs something to check and rarely changes (which index files the phone holds), kept for [millis]: a
 * screen asks dozens of times as it loads, and each ask re-listed the files, a tenth of a second on a phone. A change
 * (a new download) shows within [millis].
 */
class Briefly<T : Any>(private val millis: Long = 3_000, private val now: () -> Long = android.os.SystemClock::elapsedRealtime) {
    private val lock = kotlinx.coroutines.sync.Mutex()
    private var kept: T? = null
    private var at = 0L

    suspend fun get(make: suspend () -> T): T {
        kept?.takeIf { now() - at < millis }?.let { return it }
        return lock.withLock {
            kept?.takeIf { now() - at < millis } ?: make().also { kept = it; at = now() }
        }
    }
}
