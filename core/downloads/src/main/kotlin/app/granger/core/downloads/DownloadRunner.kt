package app.granger.core.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class RunOutcome { INSTALLED, RETRY, FAILED, GONE }

/**
 * Takes one queued record to installed or failed. Only a file whose sha256 matched is ever moved to
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
    suspend fun run(itemId: String, sha256: String, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): RunOutcome {
        val record = dao.get(itemId, sha256) ?: return RunOutcome.GONE
        if (record.state == DownloadState.INSTALLED) return RunOutcome.INSTALLED
        var hashFailures = record.hashFailures
        try {
            while (true) {
                val part = record.partFile
                val started = dao.setState(itemId, sha256, DownloadState.RUNNING, null, part.length(), hashFailures, now())
                if (started == 0) return RunOutcome.GONE
                var lastWrite = 0L
                val request = DownloadRequest(record.urls, record.sizeBytes, sha256)
                val result = downloader.download(request, part) { done ->
                    onProgress(done, record.sizeBytes)
                    val t = now()
                    if (t - lastWrite >= progressEveryMillis) {
                        lastWrite = t
                        dao.setProgress(itemId, sha256, done, t)
                    }
                }
                when (result) {
                    DownloadResult.Done -> return install(record)
                    DownloadResult.HashMismatch -> {
                        hashFailures++
                        if (hashFailures >= MAX_HASH_FAILURES) {
                            return fail(record, "Checksum mismatch: the download didn't match the catalog.", hashFailures)
                        }
                    }
                    is DownloadResult.NoSpace -> {
                        val mb = (result.neededBytes + 999_999) / 1_000_000
                        return fail(record, "Not enough free space: about $mb MB more is needed.", hashFailures)
                    }
                    is DownloadResult.Failed -> {
                        dao.setState(itemId, sha256, DownloadState.QUEUED, result.reason, part.length(), hashFailures, now())
                        return RunOutcome.RETRY
                    }
                }
            }
        } catch (e: CancellationException) {
            // Stopped by WorkManager (Wi-Fi lost, cancelled, time limit): the part file stays for a resume.
            withContext(NonCancellable) { dao.requeueIfRunning(itemId, sha256, now()) }
            throw e
        }
    }

    private suspend fun install(record: DownloadRecord): RunOutcome {
        if (!withContext(io) { record.partFile.renameTo(record.file) }) {
            return fail(record, "Couldn't save the finished file.", 0)
        }
        if (dao.markInstalled(record.itemId, record.sha256, now()) == 0) {
            // Cancelled or removed while finishing: don't leave a file nothing points to.
            if (dao.forItem(record.itemId).none { it.filePath == record.filePath }) withContext(io) { record.file.delete() }
            return RunOutcome.GONE
        }
        for (old in dao.forItem(record.itemId)) {
            if (old.sha256 == record.sha256 || old.isActive) continue
            if (old.filePath != record.filePath) withContext(io) { old.file.delete() }
            dao.delete(old.itemId, old.sha256)
        }
        onInstalled(dao.get(record.itemId, record.sha256) ?: record)
        return RunOutcome.INSTALLED
    }

    private suspend fun fail(record: DownloadRecord, reason: String, hashFailures: Int): RunOutcome {
        dao.setState(record.itemId, record.sha256, DownloadState.FAILED, reason, record.partFile.length(), hashFailures, now())
        return RunOutcome.FAILED
    }

    private companion object {
        /** "Checksum mismatch → retry once, then mark failed" (spec §4). */
        const val MAX_HASH_FAILURES = 2
    }
}
