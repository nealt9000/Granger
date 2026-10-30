package app.granger.core.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the queue runs one record with: [DownloadRunner] in the app, a fake in tests. */
interface RecordRunner {
    suspend fun run(itemId: String, hash: String, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): RunOutcome

    /** After [MAX_ATTEMPTS]: a queued record becomes FAILED, keeping its last error. */
    suspend fun giveUp(itemId: String, hash: String)
}

/** The queue's own settings, kept across restarts (the app's DownloadPrefs). */
interface QueuePrefs {
    /** Pause all (Plan DL Decision 3): nothing downloads until Resume. */
    var paused: Boolean
}

/** Pause all kept in memory only: for tests and previews. */
class MemoryQueuePrefs(override var paused: Boolean = false) : QueuePrefs

/** Why the queue stopped working through records, and when it should look again. */
sealed interface QueueRest {
    /** Nothing waits. */
    data object Idle : QueueRest

    data object Paused : QueueRest

    /** Everything left waits for a retry time or for Wi-Fi: look again after [delayMillis], on an unmetered network if [unmeteredOnly]. */
    data class Later(val delayMillis: Long, val unmeteredOnly: Boolean) : QueueRest
}

/**
 * The one download queue (Plan DL Decision 1): one file at a time, lowest [DownloadRecord.queueOrder] first, run with
 * the existing [DownloadRunner]. The `download-queue` worker calls [drain]; the rest of the app reaches it through
 * [DownloadScheduler].
 *
 * A record is runnable while it is QUEUED (or left RUNNING by a run that died), its [DownloadRecord.retryAt] has come,
 * and it isn't Wi-Fi-only on a metered network. A try that fails for now doesn't hold the queue up: the record waits
 * with [DownloadRecord.attempts] + 1 until its [DownloadRecord.retryAt], and the queue moves on (Decision 4).
 */
class DownloadQueue(
    private val dao: DownloadDao,
    private val runner: RecordRunner,
    private val prefs: QueuePrefs,
    /** Whether the network now is unmetered (Wi-Fi). */
    private val unmetered: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    /** How often a run checks it may still use this network. */
    private val networkCheckMillis: Long = 5_000,
) {
    private val lock = Any()
    private var draining = false
    private var dirty = false
    private var run: Job? = null

    private val _current = MutableStateFlow<DownloadRecord?>(null)

    /** The record downloading now, in this process; null between files and when the queue is idle. */
    val current: StateFlow<DownloadRecord?> = _current.asStateFlow()

    /**
     * Called whenever there may be something new to run. True when the caller must start the worker; false when a
     * [drain] is under way in this process, which will look again before it stops.
     */
    fun wake(): Boolean = synchronized(lock) {
        if (draining) dirty = true
        !draining
    }

    fun isRunning(itemId: String): Boolean = _current.value?.itemId == itemId

    /**
     * Stops the run of [itemId] (any run when null) and returns once it has stopped; its record goes back to QUEUED
     * with its part file. A [drain] carries on with the next record.
     */
    suspend fun stopRun(itemId: String? = null) {
        val job = synchronized(lock) { run?.takeIf { itemId == null || isRunning(itemId) } } ?: return
        job.cancel()
        job.join()
    }

    /** Works through the runnable records until none is left, then says why it stopped. */
    suspend fun drain(onProgress: (DownloadRecord, done: Long, total: Long) -> Unit = { _, _, _ -> }): QueueRest {
        synchronized(lock) {
            draining = true
            dirty = false
        }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (prefs.paused) return QueueRest.Paused
                val waiting = dao.waiting()
                val t = now()
                val metered = !unmetered()
                val next = waiting.firstOrNull { it.isRunnable(t, metered) }
                if (next != null) {
                    runOne(next, waiting, onProgress)
                    continue
                }
                // Something queued while we looked is seen on the next pass rather than lost.
                val stop = synchronized(lock) {
                    if (dirty) {
                        dirty = false
                        false
                    } else {
                        draining = false
                        true
                    }
                }
                if (stop) return restOf(waiting, t, metered)
            }
        } finally {
            synchronized(lock) { draining = false }
            _current.value = null
        }
    }

    private fun DownloadRecord.isRunnable(t: Long, metered: Boolean) =
        (retryAt ?: 0) <= t && !(wifiOnly && metered)

    private fun restOf(waiting: List<DownloadRecord>, t: Long, metered: Boolean): QueueRest {
        if (waiting.isEmpty()) return QueueRest.Idle
        val onThisNetwork = waiting.filter { !(it.wifiOnly && metered) }
        val soonest = (onThisNetwork.ifEmpty { waiting }).minOf { it.retryAt ?: t }
        return QueueRest.Later((soonest - t).coerceAtLeast(0), unmeteredOnly = onThisNetwork.isEmpty())
    }

    private suspend fun runOne(
        record: DownloadRecord, waiting: List<DownloadRecord>, onProgress: (DownloadRecord, Long, Long) -> Unit,
    ) {
        // The file now downloading is the front of the queue, so Download next lands right behind it.
        val front = waiting.filter { it !== record }.minOfOrNull { it.queueOrder }
        val running = if (front != null && record.queueOrder >= front - 1) {
            record.copy(queueOrder = front - 2).also { dao.setQueueOrder(it.itemId, it.hash, it.queueOrder) }
        } else {
            record
        }
        _current.value = running
        val outcome = try {
            coroutineScope {
                val job = async { runner.run(record.itemId, record.hash) { done, total -> onProgress(running, done, total) } }
                synchronized(lock) { run = job }
                // Wi-Fi lost, or Wi-Fi only turned on, while on mobile data: the file goes back to waiting.
                val watch = launch {
                    while (job.isActive) {
                        delay(networkCheckMillis)
                        if (!unmetered() && dao.get(record.itemId, record.hash)?.wifiOnly == true) job.cancel()
                    }
                }
                try {
                    job.await()
                } catch (e: CancellationException) {
                    // The run was stopped (Cancel, Wi-Fi lost, Download next isn't one): it went back to QUEUED.
                    if (!isActive) throw e
                    null
                } finally {
                    watch.cancel()
                }
            }
        } finally {
            synchronized(lock) { run = null }
            _current.value = null
        }
        if (outcome == RunOutcome.RETRY) {
            val attempts = record.attempts + 1
            if (attempts >= MAX_ATTEMPTS) {
                runner.giveUp(record.itemId, record.hash)
            } else {
                val t = now()
                dao.setRetry(record.itemId, record.hash, attempts, t + backoffMillis(attempts), t)
            }
        }
    }

    companion object {
        /** WorkManager's old rule, kept: 30 s after the first failure, doubling, at most 5 hours. */
        fun backoffMillis(attempts: Int): Long =
            (30_000L shl (attempts - 1).coerceIn(0, 20)).coerceAtMost(5 * 60 * 60 * 1000L)
    }
}
