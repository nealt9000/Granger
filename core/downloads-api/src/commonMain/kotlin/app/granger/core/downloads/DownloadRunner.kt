package app.granger.core.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class RunOutcome { INSTALLED, RETRY, FAILED, GONE }

/**
 * Takes one queued record to installed or failed. Only a file whose checksum matched is ever moved to
 * its final name; [onInstalled] (the library refresh) runs after that.
 */
class DownloadRunner(
    private val dao: DownloadDao,
    private val downloader: FileTransfer,
    private val onInstalled: suspend (DownloadRecord) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = ::nowMillis,
    private val progressEveryMillis: Long = 1_000,
) : RecordRunner {
    private val locks = mutableMapOf<String, Mutex>()
    private val locksLock = QueueLock()

    /** One run per record at a time: a replacing run waits until the old one has fully exited. */
    override suspend fun run(itemId: String, hash: String, onProgress: (done: Long, total: Long) -> Unit): RunOutcome {
        val lock = locksLock.with { locks.getOrPut("$itemId@$hash") { Mutex() } }
        return lock.withLock { runLocked(itemId, hash, onProgress) }
    }

    /** Called after too many retries: a queued or running record becomes failed, keeping its last error. */
    override suspend fun giveUp(itemId: String, hash: String) {
        val lock = locksLock.with { locks.getOrPut("$itemId@$hash") { Mutex() } }
        lock.withLock {
            val record = dao.get(itemId, hash) ?: return
            if (record.state != DownloadState.QUEUED && record.state != DownloadState.RUNNING) return
            val bytes = withContext(io) { Files.size(record.partPath) }
            val reason = "Gave up after repeated failures: ${record.error ?: "unknown error"}"
            dao.setActiveState(itemId, hash, DownloadState.FAILED, reason, bytes, record.hashFailures, now())
        }
    }

    private suspend fun runLocked(itemId: String, hash: String, onProgress: (done: Long, total: Long) -> Unit): RunOutcome {
        val record = dao.get(itemId, hash) ?: return RunOutcome.GONE
        if (record.state == DownloadState.INSTALLED) return RunOutcome.INSTALLED
        var hashFailures = record.hashFailures
        try {
            while (true) {
                val part = record.partPath
                val started = dao.setActiveState(itemId, hash, DownloadState.RUNNING, null, Files.size(part), hashFailures, now())
                if (started == 0) return RunOutcome.GONE
                var lastWrite = 0L
                val request = DownloadRequest(record.urls, record.sizeBytes, hash, record.hashAlgorithm)
                val result = downloader.download(request, part) { done ->
                    onProgress(done, record.sizeBytes)
                    val t = now()
                    if (t - lastWrite >= progressEveryMillis) {
                        lastWrite = t
                        // The transfer reports from its own thread, which may block: Android did this write blocking too.
                        runBlocking { dao.setProgress(itemId, hash, done, t) }
                    }
                }
                when (result) {
                    DownloadResult.Done -> return install(record)
                    DownloadResult.HashMismatch -> {
                        hashFailures++
                        if (hashFailures >= MAX_HASH_FAILURES) {
                            return fail(record, "Checksum mismatch: the download didn't match its expected checksum.", hashFailures)
                        }
                    }
                    is DownloadResult.NoSpace -> {
                        val mb = (result.neededBytes + 999_999) / 1_000_000
                        return fail(record, "Not enough free space: about $mb MB more is needed.", hashFailures)
                    }
                    is DownloadResult.Failed -> {
                        if (result.gone) return fail(record, GoneFile.message(record.urls), hashFailures)
                        if (result.permanent) return fail(record, result.reason, hashFailures)
                        val queued = dao.setActiveState(itemId, hash, DownloadState.QUEUED, result.reason, Files.size(part), hashFailures, now())
                        return if (queued == 0) RunOutcome.GONE else RunOutcome.RETRY
                    }
                }
            }
        } catch (e: CancellationException) {
            // Stopped (Pause all, Cancel, Wi-Fi lost, a time limit): the part file stays for a resume.
            withContext(NonCancellable) { dao.requeueIfRunning(itemId, hash, now()) }
            throw e
        } catch (e: Exception) {
            // E.g. the database or disk failing mid-download: never leave the record RUNNING with no worker.
            return withContext(NonCancellable) {
                fail(record, "Download stopped: ${e.message ?: e::class.simpleName}", hashFailures)
            }
        }
    }

    private suspend fun install(record: DownloadRecord): RunOutcome {
        if (!withContext(io) { Files.rename(record.partPath, record.filePath) }) {
            return fail(record, "Couldn't save the finished file.", 0)
        }
        if (dao.markInstalled(record.itemId, record.hash, now()) == 0) {
            // Cancelled or removed while finishing: don't leave a file nothing points to.
            if (dao.forItem(record.itemId).none { it.filePath == record.filePath }) withContext(io) { Files.delete(record.filePath) }
            return RunOutcome.GONE
        }
        for (old in dao.forItem(record.itemId)) {
            if (old.hash == record.hash || old.isActive) continue
            if (old.filePath != record.filePath) withContext(io) { Files.delete(old.filePath) }
            dao.delete(old.itemId, old.hash)
        }
        onInstalled(dao.get(record.itemId, record.hash) ?: record)
        return RunOutcome.INSTALLED
    }

    private suspend fun fail(record: DownloadRecord, reason: String, hashFailures: Int): RunOutcome {
        val changed = dao.setActiveState(record.itemId, record.hash, DownloadState.FAILED, reason, Files.size(record.partPath), hashFailures, now())
        return if (changed == 0) RunOutcome.GONE else RunOutcome.FAILED
    }

    private companion object {
        /** "Checksum mismatch → retry once, then mark failed" (spec §4). */
        const val MAX_HASH_FAILURES = 2
    }
}
