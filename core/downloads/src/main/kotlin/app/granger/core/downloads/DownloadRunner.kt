package app.granger.core.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
    private val downloader: FileDownloader,
    private val onInstalled: suspend (DownloadRecord) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val progressEveryMillis: Long = 1_000,
) {
    private val locks = mutableMapOf<String, Mutex>()

    /** One run per record at a time: a replacing run waits until the old one has fully exited. */
    suspend fun run(itemId: String, hash: String, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): RunOutcome {
        val lock = synchronized(locks) { locks.getOrPut("$itemId@$hash") { Mutex() } }
        return lock.withLock { runLocked(itemId, hash, onProgress) }
    }

    /** Called after too many retries: a queued or running record becomes failed, keeping its last error. */
    suspend fun giveUp(itemId: String, hash: String) {
        val lock = synchronized(locks) { locks.getOrPut("$itemId@$hash") { Mutex() } }
        lock.withLock {
            val record = dao.get(itemId, hash) ?: return
            if (record.state != DownloadState.QUEUED && record.state != DownloadState.RUNNING) return
            val bytes = withContext(io) { record.partFile.length() }
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
                val part = record.partFile
                val started = dao.setActiveState(itemId, hash, DownloadState.RUNNING, null, part.length(), hashFailures, now())
                if (started == 0) return RunOutcome.GONE
                var lastWrite = 0L
                val request = DownloadRequest(record.urls, record.sizeBytes, hash, record.hashAlgorithm)
                val result = downloader.download(request, part) { done ->
                    onProgress(done, record.sizeBytes)
                    val t = now()
                    if (t - lastWrite >= progressEveryMillis) {
                        lastWrite = t
                        dao.setProgress(itemId, hash, done, t)
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
                        if (result.permanent) return fail(record, result.reason, hashFailures)
                        val queued = dao.setActiveState(itemId, hash, DownloadState.QUEUED, result.reason, part.length(), hashFailures, now())
                        return if (queued == 0) RunOutcome.GONE else RunOutcome.RETRY
                    }
                }
            }
        } catch (e: CancellationException) {
            // Stopped by WorkManager (Wi-Fi lost, cancelled, time limit): the part file stays for a resume.
            withContext(NonCancellable) { dao.requeueIfRunning(itemId, hash, now()) }
            throw e
        } catch (e: Exception) {
            // E.g. the database or disk failing mid-download: never leave the record RUNNING with no worker.
            return withContext(NonCancellable) {
                fail(record, "Download stopped: ${e.message ?: e.javaClass.simpleName}", hashFailures)
            }
        }
    }

    private suspend fun install(record: DownloadRecord): RunOutcome {
        if (!withContext(io) { record.partFile.renameTo(record.file) }) {
            return fail(record, "Couldn't save the finished file.", 0)
        }
        if (dao.markInstalled(record.itemId, record.hash, now()) == 0) {
            // Cancelled or removed while finishing: don't leave a file nothing points to.
            if (dao.forItem(record.itemId).none { it.filePath == record.filePath }) withContext(io) { record.file.delete() }
            return RunOutcome.GONE
        }
        for (old in dao.forItem(record.itemId)) {
            if (old.hash == record.hash || old.isActive) continue
            if (old.filePath != record.filePath) withContext(io) { old.file.delete() }
            dao.delete(old.itemId, old.hash)
        }
        onInstalled(dao.get(record.itemId, record.hash) ?: record)
        return RunOutcome.INSTALLED
    }

    private suspend fun fail(record: DownloadRecord, reason: String, hashFailures: Int): RunOutcome {
        val changed = dao.setActiveState(record.itemId, record.hash, DownloadState.FAILED, reason, record.partFile.length(), hashFailures, now())
        return if (changed == 0) RunOutcome.GONE else RunOutcome.FAILED
    }

    private companion object {
        /** "Checksum mismatch → retry once, then mark failed" (spec §4). */
        const val MAX_HASH_FAILURES = 2
    }
}
