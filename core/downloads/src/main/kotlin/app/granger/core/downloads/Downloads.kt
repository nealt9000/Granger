package app.granger.core.downloads

import app.granger.core.catalog.BookEntry
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.ModelEntry
import app.granger.core.catalog.PackEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** What the app's screens use to download, cancel and check items. */
interface Downloads {
    /** Every version of every item, oldest first. */
    val records: Flow<List<DownloadRecord>>

    /** Checks the cap and free space on [volumeRoot], then queues [item]. */
    suspend fun enqueue(item: CatalogItem, volumeRoot: File, wifiOnly: Boolean): EnqueueResult

    /** Queues a failed download again, with a fresh checksum retry. */
    suspend fun retry(itemId: String)

    /** Stops an unfinished download and deletes its partial file. An installed version stays. */
    suspend fun cancel(itemId: String)

    /** Stops any download and deletes every file and record of [itemId]. */
    suspend fun remove(itemId: String)

    /** Downloads an installed item again, e.g. after Readiness found it damaged. */
    suspend fun repair(itemId: String)

    /** Stores the result of a full sha256 check from Readiness. */
    suspend fun recordCheck(itemId: String, sha256: String, verified: Boolean)

    /** Applies the Wi-Fi-only setting to every unfinished download. */
    suspend fun setWifiOnly(wifiOnly: Boolean)
}

/** Runs downloads in the background; the WorkManager implementation is in DownloadWorker.kt. */
interface DownloadScheduler {
    /** Starts [record]'s download; with [replace], restarts it (it resumes from its part file). */
    fun schedule(record: DownloadRecord, replace: Boolean = false)

    fun cancel(itemId: String)
}

class DownloadManager(
    private val dao: DownloadDao,
    private val scheduler: DownloadScheduler,
    private val installedPackBytes: suspend () -> Long,
    private val capBytes: () -> Long = { Long.MAX_VALUE },
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : Downloads {
    private val mutex = Mutex()

    override val records: Flow<List<DownloadRecord>> = dao.observeAll()

    override suspend fun enqueue(item: CatalogItem, volumeRoot: File, wifiOnly: Boolean): EnqueueResult = mutex.withLock {
        val versions = dao.forItem(item.id)
        if (versions.any { it.isActive }) return@withLock EnqueueResult.AlreadyActive
        if (versions.any { it.sha256 == item.sha256 }) return@withLock EnqueueResult.AlreadyInstalled
        val root = volumeRoot.absoluteFile
        val active = dao.getAll().filter { it.isActive }
        // Packs and books both count against the storage cap; models do not.
        val isCapped = item is PackEntry || item is BookEntry
        val verdict = QueuePolicy.check(
            itemBytes = item.sizeBytes,
            isPack = isCapped,
            installedPackBytes = installedPackBytes(),
            queuedPackBytes = active.filter { it.kind == ItemKind.PACK || it.kind == ItemKind.BOOK }.sumOf { it.sizeBytes },
            replacedPackBytes = if (isCapped) versions.sumOf { it.sizeBytes } else 0L,
            capBytes = capBytes(),
            freeBytes = withContext(io) { freeBytes(root) },
            queuedOnVolumeBytes = active
                .filter { it.filePath.startsWith(root.path + File.separator) }
                .sumOf { it.sizeBytes - it.bytesDone },
        )
        if (verdict != EnqueueResult.Queued) return@withLock verdict
        val t = now()
        val record = DownloadRecord(
            itemId = item.id,
            sha256 = item.sha256,
            kind = when (item) {
                is PackEntry -> ItemKind.PACK
                is BookEntry -> ItemKind.BOOK
                is ModelEntry -> ItemKind.MODEL
            },
            title = item.title,
            urls = item.urls,
            sizeBytes = item.sizeBytes,
            filePath = targetFile(item, root).path,
            snapshotDate = snapshotDate(item),
            wifiOnly = wifiOnly,
            state = DownloadState.QUEUED,
            createdAt = t,
            updatedAt = t,
        )
        dao.upsert(record)
        scheduler.schedule(record)
        EnqueueResult.Queued
    }

    private fun snapshotDate(item: CatalogItem): String = when (item) {
        is PackEntry -> item.snapshotDate
        is BookEntry -> item.releaseDate
        is ModelEntry -> ""
    }

    /** Each pack snapshot and book release gets its own file, so an update never overwrites the pack being read. */
    private fun targetFile(item: CatalogItem, root: File): File = when (item) {
        is PackEntry -> File(root, "packs/${item.id}_${item.snapshotDate}.zim")
        is BookEntry -> File(root, "books/granger/${item.id}_${item.releaseDate}.epub")
        is ModelEntry -> File(root, "models/${item.fileName}")
    }

    override suspend fun retry(itemId: String) = mutex.withLock {
        val failed = dao.forItem(itemId).firstOrNull { it.state == DownloadState.FAILED } ?: return@withLock
        val queued = failed.copy(state = DownloadState.QUEUED, error = null, hashFailures = 0, updatedAt = now())
        dao.upsert(queued)
        scheduler.schedule(queued, replace = true)
    }

    override suspend fun cancel(itemId: String) = mutex.withLock {
        scheduler.cancel(itemId)
        val hasInstalledVersion = dao.forItem(itemId).any { !it.isActive }
        for (record in dao.forItem(itemId).filter { it.isActive }) {
            val fileExists = withContext(io) { record.partFile.delete(); record.file.exists() }
            if (fileExists && !hasInstalledVersion) {
                // A cancelled repair: the verified file is still there, so keep its ledger row.
                // (With another installed version, a file at this path belongs to that version.)
                dao.upsert(
                    record.copy(
                        state = DownloadState.INSTALLED, bytesDone = record.sizeBytes, error = null,
                        hashFailures = 0, verifiedAt = null, updatedAt = now(),
                    ),
                )
            } else {
                dao.delete(record.itemId, record.sha256)
            }
        }
    }

    override suspend fun remove(itemId: String) = mutex.withLock {
        scheduler.cancel(itemId)
        for (record in dao.forItem(itemId)) {
            dao.delete(record.itemId, record.sha256)
            withContext(io) {
                record.partFile.delete()
                record.file.delete()
            }
        }
    }

    override suspend fun repair(itemId: String) = mutex.withLock {
        val versions = dao.forItem(itemId)
        if (versions.any { it.isActive }) return@withLock
        val installed = versions.firstOrNull { it.state == DownloadState.INSTALLED } ?: return@withLock
        val queued = installed.copy(state = DownloadState.QUEUED, bytesDone = 0, error = null, hashFailures = 0, verifiedAt = null, updatedAt = now())
        dao.upsert(queued)
        scheduler.schedule(queued, replace = true)
    }

    override suspend fun recordCheck(itemId: String, sha256: String, verified: Boolean) {
        dao.setVerifiedAt(itemId, sha256, if (verified) now() else null)
    }

    override suspend fun setWifiOnly(wifiOnly: Boolean) = mutex.withLock {
        dao.setWifiOnlyForActive(wifiOnly)
        for (record in dao.getAll().filter { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }) {
            scheduler.schedule(record, replace = true)
        }
    }
}
