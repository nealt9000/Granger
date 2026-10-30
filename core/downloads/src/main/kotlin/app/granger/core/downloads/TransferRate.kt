package app.granger.core.downloads

/**
 * The download speed over a rolling window (Plan DL Decision 6), in memory only. [add] counts bytes as they arrive;
 * [bytesPerSecond] is null until [warmUpMillis] of data, so the first figure isn't a guess from one burst. A stall
 * shows as the rate falling towards 0 as the window empties.
 */
class TransferRate(private val windowMillis: Long = 20_000, private val warmUpMillis: Long = 10_000) {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var startedAt: Long? = null

    /** The first report only starts the clock: when its bytes began arriving isn't known. */
    @Synchronized
    fun add(bytes: Long, at: Long) {
        if (bytes <= 0) return
        if (startedAt == null) {
            startedAt = at
            return
        }
        samples.addLast(at to bytes)
        trim(at)
    }

    @Synchronized
    fun bytesPerSecond(at: Long): Long? {
        val start = startedAt ?: return null
        if (at - start < warmUpMillis) return null
        trim(at)
        val span = minOf(windowMillis, at - start)
        return samples.sumOf { it.second } * 1000 / span
    }

    /** Forgets everything: after a pause or once the queue is idle, the next speed is measured afresh. */
    @Synchronized
    fun reset() {
        samples.clear()
        startedAt = null
    }

    private fun trim(at: Long) {
        while (samples.isNotEmpty() && samples.first().first <= at - windowMillis) samples.removeFirst()
    }
}
