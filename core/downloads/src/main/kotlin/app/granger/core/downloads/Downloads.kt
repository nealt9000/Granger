package app.granger.core.downloads

import app.granger.core.catalog.BookEntry
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.ModelEntry
import app.granger.core.catalog.PackEntry
import app.granger.core.catalog.VoiceEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One file to download, whether or not it is in the catalog. [relativePath] is under the volume root,
 * e.g. `books/archive-org/x.epub`; [capped] says whether it counts toward the storage cap. [groupId] names the
 * download group (one book or album) the file belongs to; see [Downloads.enqueueGroup].
 */
data class DownloadSpec(
    val itemId: String,
    val kind: ItemKind,
    val title: String,
    val urls: List<String>,
    val sizeBytes: Long,
    val hash: String,
    val hashAlgorithm: HashAlgorithm,
    val relativePath: String,
    val snapshotDate: String,
    val capped: Boolean,
    val groupId: String? = null,
)

/** What the app's screens use to download, cancel and check items. */
interface Downloads {
    /** Every version of every item, in queue order (Plan DL Decision 2). */
    val records: Flow<List<DownloadRecord>>

    /** Pause all is on: nothing downloads until [resume]. */
    val paused: StateFlow<Boolean>

    /** Checks the cap and free space on [volumeRoot], then queues [item]. */
    suspend fun enqueue(item: CatalogItem, volumeRoot: File, wifiOnly: Boolean): EnqueueResult

    /** Checks the cap and free space on [volumeRoot], then queues [spec], which needn't be in the catalog. */
    suspend fun enqueue(spec: DownloadSpec, volumeRoot: File, wifiOnly: Boolean): EnqueueResult

    /** Queues a failed download again, with a fresh checksum retry. */
    suspend fun retry(itemId: String)

    /** Stops an unfinished download and deletes its partial file. An installed version stays. */
    suspend fun cancel(itemId: String)

    /** Stops any download and deletes every file and record of [itemId]. */
    suspend fun remove(itemId: String)

    /** Deletes one installed version of [itemId] (its record and file), when it isn't downloading. */
    suspend fun removeVersion(itemId: String, hash: String)

    /** Downloads an installed item again, e.g. after Readiness found it damaged. */
    suspend fun repair(itemId: String)

    /** Stores the result of a full checksum check from Readiness. */
    suspend fun recordCheck(itemId: String, hash: String, verified: Boolean)

    /** Applies the Wi-Fi-only setting to every unfinished download, keeping the queue's order. */
    suspend fun setWifiOnly(wifiOnly: Boolean)

    /**
     * Called once when the app starts (Plan DL Decision 1): a record left RUNNING by a run that died goes back to
     * QUEUED (its part file stays), and the queue is started. A group whose chain was lost before Plan DL resumes too.
     */
    suspend fun recover()

    /** Pause all (Plan DL Decision 3): the running file goes back to waiting, its part file kept; survives a restart. */
    suspend fun pause()

    /** Ends Pause all: the queue carries on, the paused file from where it was. */
    suspend fun resume()

    /** Moves a waiting item ahead of everything else waiting. It starts when the current file finishes. */
    suspend fun downloadNext(itemId: String)

    /**
     * Queues [specs] (every one with groupId == [groupId]) as one, in list order: at the back of the queue (setup), or
     * [next], right behind the file now downloading (an album you asked for). Specs whose exact hash is already
     * installed are skipped; one already active is left as it is. One space check covers the bytes still to fetch:
     * everything is queued or nothing. There's no cap: only free space on [volumeRoot] is checked.
     */
    suspend fun enqueueGroup(
        groupId: String, specs: List<DownloadSpec>, volumeRoot: File, wifiOnly: Boolean, next: Boolean = false,
    ): EnqueueResult

    /** Stops the group's unfinished files and deletes their parts; installed files and their records stay. */
    suspend fun cancelGroup(groupId: String)

    /** Stops the group and deletes every file and record in it. */
    suspend fun removeGroup(groupId: String)

    /** Queues the group's failed files again, in order. */
    suspend fun retryGroup(groupId: String)
}

/** Runs the one download queue in the background; the WorkManager implementation is in DownloadWorker.kt. */
interface DownloadScheduler {
    /** There may be something new to run: makes sure the queue runs, on an unmetered network if [unmeteredOnly]. */
    fun ensure(unmeteredOnly: Boolean)

    /** Stops [itemId]'s download if it is the one running, and returns once it has; the queue moves on. */
    suspend fun stopRun(itemId: String)

    /** Stops the queue (Pause all) and returns once the running file has gone back to waiting. */
    suspend fun stopQueue()

    /** Whether [itemId] is downloading now. */
    fun isRunning(itemId: String): Boolean

    /** Drops work queued before Plan DL (one job per item, a chain per group); the queue runs those records now. */
    fun forgetLegacyWork() {}
}

class DownloadManager(
    private val dao: DownloadDao,
    private val scheduler: DownloadScheduler,
    private val prefs: QueuePrefs = MemoryQueuePrefs(),
    private val installedPackBytes: suspend () -> Long,
    private val capBytes: () -> Long = { Long.MAX_VALUE },
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : Downloads {
    private val mutex = Mutex()

    override val records: Flow<List<DownloadRecord>> = dao.observeAll()

    private val _paused = MutableStateFlow(prefs.paused)
    override val paused: StateFlow<Boolean> = _paused.asStateFlow()

    /** Starts the queue if anything waits; Pause all keeps it stopped. */
    private suspend fun wake() {
        if (prefs.paused) return
        val waiting = dao.waiting()
        if (waiting.isNotEmpty()) withContext(io) { scheduler.ensure(unmeteredOnly = waiting.all { it.wifiOnly }) }
    }

    /**
     * The first of [count] places right behind the file now downloading, ahead of everything else waiting (Plan DL
     * Decision 2). The running file moves forward if it must, so it stays at the front.
     */
    private suspend fun nextOrder(count: Int = 1, except: String? = null): Long {
        val waiting = dao.waiting().filter { it.itemId != except }
        val running = waiting.firstOrNull { it.state == DownloadState.RUNNING || scheduler.isRunning(it.itemId) }
        val others = waiting.filter { it !== running }
        val first = (others.minOfOrNull { it.queueOrder } ?: ((running?.queueOrder ?: dao.maxQueueOrder() ?: 0) + count + 1)) - count
        if (running != null && running.queueOrder >= first) dao.setQueueOrder(running.itemId, running.hash, first - 1)
        return first
    }

    /** The first place after everything already queued. */
    private suspend fun backOrder(): Long = (dao.maxQueueOrder() ?: -1) + 1

    override suspend fun enqueue(item: CatalogItem, volumeRoot: File, wifiOnly: Boolean): EnqueueResult =
        enqueue(specOf(item), volumeRoot, wifiOnly)

    override suspend fun enqueue(spec: DownloadSpec, volumeRoot: File, wifiOnly: Boolean): EnqueueResult = mutex.withLock {
        val versions = dao.forItem(spec.itemId)
        if (versions.any { it.isActive }) return@withLock EnqueueResult.AlreadyActive
        if (versions.any { it.hash == spec.hash }) return@withLock EnqueueResult.AlreadyInstalled
        val root = volumeRoot.absoluteFile
        val active = dao.getAll().filter { it.isActive }
        val verdict = QueuePolicy.check(
            itemBytes = spec.sizeBytes,
            isPack = spec.capped,
            installedPackBytes = installedPackBytes(),
            queuedPackBytes = active.filter { it.kind == ItemKind.PACK || it.kind == ItemKind.BOOK }.sumOf { it.sizeBytes },
            replacedPackBytes = if (spec.capped) versions.sumOf { it.sizeBytes } else 0L,
            capBytes = capBytes(),
            freeBytes = withContext(io) { freeBytes(root) },
            queuedOnVolumeBytes = active
                .filter { it.filePath.startsWith(root.path + File.separator) }
                .sumOf { it.sizeBytes - it.bytesDone },
        )
        if (verdict != EnqueueResult.Queued) return@withLock verdict
        val t = now()
        val record = DownloadRecord(
            itemId = spec.itemId,
            hash = spec.hash,
            hashAlgorithm = spec.hashAlgorithm,
            kind = spec.kind,
            title = spec.title,
            urls = spec.urls,
            sizeBytes = spec.sizeBytes,
            filePath = File(root, spec.relativePath).path,
            snapshotDate = spec.snapshotDate,
            groupId = spec.groupId,
            wifiOnly = wifiOnly,
            state = DownloadState.QUEUED,
            createdAt = t,
            updatedAt = t,
            // Something you start yourself goes next; a pack's vectors, queued as it installs, follow right after it.
            queueOrder = nextOrder(),
        )
        dao.upsert(record)
        wake()
        EnqueueResult.Queued
    }

    override suspend fun enqueueGroup(
        groupId: String, specs: List<DownloadSpec>, volumeRoot: File, wifiOnly: Boolean, next: Boolean,
    ): EnqueueResult = mutex.withLock {
        require(specs.all { it.groupId == groupId }) { "Every spec in group $groupId must carry its groupId" }
        val root = volumeRoot.absoluteFile
        var anyActive = false
        // One read for the whole ledger, not one per spec: an onboarding group can be hundreds of items (Plan U3a).
        val all = dao.getAll()
        val byItem = all.groupBy { it.itemId }
        val toFetch = specs.filter { spec ->
            val versions = byItem[spec.itemId].orEmpty()
            when {
                versions.any { it.isActive } -> { anyActive = true; false }
                versions.any { it.hash == spec.hash } -> false
                else -> true
            }
        }
        if (toFetch.isEmpty()) {
            return@withLock if (anyActive) EnqueueResult.AlreadyActive else EnqueueResult.AlreadyInstalled
        }
        // Only free space is checked (no cap for media): the group's bytes, other queued bytes on the volume, the reserve.
        val active = all.filter { it.isActive }
        val verdict = QueuePolicy.check(
            itemBytes = toFetch.sumOf { it.sizeBytes },
            isPack = false,
            installedPackBytes = 0,
            queuedPackBytes = 0,
            replacedPackBytes = 0,
            capBytes = Long.MAX_VALUE,
            freeBytes = withContext(io) { freeBytes(root) },
            queuedOnVolumeBytes = active
                .filter { it.filePath.startsWith(root.path + File.separator) }
                .sumOf { it.sizeBytes - it.bytesDone },
        )
        if (verdict != EnqueueResult.Queued) return@withLock verdict
        val t = now()
        val base = if (next) nextOrder(toFetch.size) else backOrder()
        val records = toFetch.mapIndexed { i, spec ->
            DownloadRecord(
                itemId = spec.itemId,
                hash = spec.hash,
                hashAlgorithm = spec.hashAlgorithm,
                kind = spec.kind,
                title = spec.title,
                urls = spec.urls,
                sizeBytes = spec.sizeBytes,
                filePath = File(root, spec.relativePath).path,
                snapshotDate = spec.snapshotDate,
                groupId = groupId,
                wifiOnly = wifiOnly,
                state = DownloadState.QUEUED,
                createdAt = t,
                updatedAt = t,
                queueOrder = base + i,
            )
        }
        dao.upsertAll(records)
        wake()
        EnqueueResult.Queued
    }

    override suspend fun cancelGroup(groupId: String) = mutex.withLock {
        for (itemId in dao.forGroup(groupId).filter { it.isActive }.map { it.itemId }.distinct()) cancelLocked(itemId)
    }

    override suspend fun removeGroup(groupId: String) = mutex.withLock {
        for (itemId in dao.forGroup(groupId).map { it.itemId }.distinct()) removeLocked(itemId)
    }

    /** The failed files go back in their stored places, so the group keeps its order. */
    override suspend fun retryGroup(groupId: String) = mutex.withLock {
        val t = now()
        val queued = dao.forGroup(groupId).filter { it.state == DownloadState.FAILED }.map { it.requeued(t) }
        if (queued.isEmpty()) return@withLock
        dao.upsertAll(queued)
        wake()
    }

    /** Back to waiting with a fresh set of tries and checksum retries. */
    private fun DownloadRecord.requeued(t: Long) =
        copy(state = DownloadState.QUEUED, error = null, hashFailures = 0, attempts = 0, retryAt = null, updatedAt = t)

    private fun specOf(item: CatalogItem): DownloadSpec = downloadSpecOf(item)

    override suspend fun retry(itemId: String) = mutex.withLock {
        val failed = dao.forItem(itemId).firstOrNull { it.state == DownloadState.FAILED } ?: return@withLock
        dao.upsert(failed.requeued(now()))
        wake()
    }

    override suspend fun cancel(itemId: String) = mutex.withLock { cancelLocked(itemId) }

    /**
     * The records change first and the run is stopped after, so the queue, moving on, can't start the file again; the
     * part file goes once the run has let go of it.
     */
    private suspend fun cancelLocked(itemId: String) {
        val versions = dao.forItem(itemId)
        val hasInstalledVersion = versions.any { !it.isActive }
        val active = versions.filter { it.isActive }
        for (record in active) {
            val fileExists = withContext(io) { record.file.exists() }
            if (fileExists && !hasInstalledVersion) {
                // A cancelled repair: the verified file is still there, so keep its ledger row.
                // (With another installed version, a file at this path belongs to that version.)
                dao.upsert(
                    record.copy(
                        state = DownloadState.INSTALLED, bytesDone = record.sizeBytes, error = null,
                        hashFailures = 0, verifiedAt = null, updatedAt = now(), attempts = 0, retryAt = null,
                    ),
                )
            } else {
                dao.delete(record.itemId, record.hash)
            }
        }
        scheduler.stopRun(itemId)
        withContext(io) { active.forEach { it.partFile.delete() } }
    }

    override suspend fun remove(itemId: String) = mutex.withLock { removeLocked(itemId) }

    override suspend fun removeVersion(itemId: String, hash: String) = mutex.withLock {
        val record = dao.get(itemId, hash)?.takeUnless { it.isActive } ?: return@withLock
        // Another version saved at the same path owns the file now.
        val shared = dao.forItem(itemId).any { it.hash != hash && it.filePath == record.filePath }
        dao.delete(itemId, hash)
        if (!shared) withContext(io) { record.file.delete() }
    }

    private suspend fun removeLocked(itemId: String) {
        val versions = dao.forItem(itemId)
        for (record in versions) dao.delete(record.itemId, record.hash)
        scheduler.stopRun(itemId)
        withContext(io) {
            for (record in versions) {
                record.partFile.delete()
                record.file.delete()
            }
        }
    }

    override suspend fun repair(itemId: String) = mutex.withLock {
        val versions = dao.forItem(itemId)
        if (versions.any { it.isActive }) return@withLock
        val installed = versions.firstOrNull { it.state == DownloadState.INSTALLED } ?: return@withLock
        val queued = installed.requeued(now()).copy(bytesDone = 0, verifiedAt = null, queueOrder = nextOrder())
        dao.upsert(queued)
        wake()
    }

    override suspend fun recordCheck(itemId: String, hash: String, verified: Boolean) {
        dao.setVerifiedAt(itemId, hash, if (verified) now() else null)
    }

    /** The order is stored, so nothing moves; the queue looks again under the new rule. */
    override suspend fun setWifiOnly(wifiOnly: Boolean) = mutex.withLock {
        dao.setWifiOnlyForActive(wifiOnly)
        wake()
    }

    override suspend fun recover() = mutex.withLock {
        withContext(io) { scheduler.forgetLegacyWork() }
        val t = now()
        for (stale in dao.waiting().filter { it.state == DownloadState.RUNNING && !scheduler.isRunning(it.itemId) }) {
            dao.requeueIfRunning(stale.itemId, stale.hash, t)
        }
        wake()
    }

    override suspend fun pause() = mutex.withLock {
        prefs.paused = true
        _paused.value = true
        scheduler.stopQueue()
    }

    override suspend fun resume() = mutex.withLock {
        prefs.paused = false
        _paused.value = false
        wake()
    }

    override suspend fun downloadNext(itemId: String) = mutex.withLock {
        val record = dao.forItem(itemId).firstOrNull { it.state == DownloadState.QUEUED } ?: return@withLock
        if (scheduler.isRunning(itemId)) return@withLock
        dao.setQueueOrder(record.itemId, record.hash, nextOrder(except = itemId))
        wake()
    }
}

/**
 * Whether this record came from the catalog, so the catalog dropping its item means something (Plan DL Decision 10).
 * Books from archive.org, albums and vectors were never catalog items by their own id.
 */
val DownloadRecord.isFromCatalog: Boolean
    get() = when (kind) {
        ItemKind.PACK, ItemKind.MODEL, ItemKind.VOICE -> true
        ItemKind.BOOK -> filePath.contains("${File.separator}books${File.separator}granger${File.separator}")
        ItemKind.AUDIO, ItemKind.VECTORS -> false
    }

/**
 * The spec a catalog item downloads as. Packs and books count against the storage cap; models and voices do not. Each
 * pack snapshot and book release gets its own file, so an update never overwrites the pack being read. [groupId] puts
 * the item in a group (one file at a time, one progress), as onboarding's setup does (Plan H3).
 */
fun downloadSpecOf(item: CatalogItem, groupId: String? = null): DownloadSpec = when (item) {
    is PackEntry -> DownloadSpec(
        item.id, ItemKind.PACK, item.title, item.urls, item.sizeBytes, item.sha256, HashAlgorithm.SHA256,
        "packs/${item.id}_${item.snapshotDate}.zim", item.snapshotDate, capped = true, groupId = groupId,
    )
    is BookEntry -> DownloadSpec(
        item.id, ItemKind.BOOK, item.title, item.urls, item.sizeBytes, item.sha256, HashAlgorithm.SHA256,
        "books/granger/${item.id}_${item.releaseDate}.epub", item.releaseDate, capped = true, groupId = groupId,
    )
    is ModelEntry -> DownloadSpec(
        item.id, ItemKind.MODEL, item.title, item.urls, item.sizeBytes, item.sha256, HashAlgorithm.SHA256,
        "models/${item.fileName}", "", capped = false, groupId = groupId,
    )
    is VoiceEntry -> DownloadSpec(
        item.id, ItemKind.VOICE, item.title, item.urls, item.sizeBytes, item.sha256, HashAlgorithm.SHA256,
        "voices/${item.id}.zip", "", capped = false, groupId = groupId,
    )
}
