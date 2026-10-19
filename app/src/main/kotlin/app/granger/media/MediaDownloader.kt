package app.granger.media

import app.granger.core.archive.ArchiveQuery
import app.granger.core.archive.LibriVoxClient
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.EnqueueResult
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.Volume
import app.granger.core.downloads.VolumeSource
import app.granger.core.library.Albums
import app.granger.core.library.MediaLibrary
import app.granger.core.media.DownloadedIds
import app.granger.core.media.DownloadedMediaScanner
import app.granger.core.media.MediaManifest
import app.granger.core.media.MediaManifests
import app.granger.formatBytes
import app.granger.sources.DownloadPrefs
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Gets, cancels and removes downloaded books and albums, and files them as their files arrive. Every downloaded-media
 * scan in the app goes through [rescan], one at a time, so a file that finished while a scan was running is always
 * picked up by the next one.
 *
 * One item lives on one volume: a Get for an item already on a mounted volume (its folder or its ledger rows) completes
 * it there, whatever volume is chosen now; one whose files are on storage that isn't mounted is refused. The scanner
 * would otherwise see two copies and file only the first.
 */
class MediaDownloader(
    private val downloads: Downloads,
    private val scanner: DownloadedMediaScanner,
    private val media: MediaLibrary,
    private val volumes: VolumeSource,
    private val prefs: DownloadPrefs,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Get, cancel and remove, one at a time. */
    private val ops = Mutex()

    private val scanLock = Mutex()
    private val requested = AtomicLong(0)
    @Volatile private var scannedUpTo = 0L

    /** Writes the manifest (and `.nomedia` in the media root), then queues the group. Returns the notice to show, or null. An item already on another volume is completed there. */
    suspend fun get(m: MediaManifest): String? = ops.withLock {
        val title = MediaManifests.displayTitle(m)
        val group = MediaDownloads.groupId(m)
        require(sourceAndRef(group) != null) { "Bad media ref ${m.source}:${m.ref}" }
        val relativeDir = MediaManifests.relativeDir(m)
        val all = withContext(io) { volumes.list() }
        val records = downloads.records.first().filter { it.groupId == group }
        val volume = when (val placed = placedOn(all, relativeDir, records)) {
            Placed.Nowhere -> all.firstOrNull { it.root.path == prefs.volumePath } ?: all.firstOrNull()
            Placed.Unmounted -> return@withLock "$title is on storage that isn't available right now. Put it back to finish it, or remove it first."
            is Placed.On -> placed.volume
        } ?: return@withLock "No storage is available right now."

        val dir = File(volume.root, relativeDir)
        val existed = withContext(io) {
            val existed = dir.isDirectory
            write(dir, volume.root, m)
            existed
        }
        val result = downloads.enqueueGroup(group, MediaDownloads.specs(m), volume.root, prefs.wifiOnly)
        when (result) {
            EnqueueResult.Queued, EnqueueResult.AlreadyActive -> null
            // Every file is in already: the new manifest may name it differently, so file it again.
            EnqueueResult.AlreadyInstalled -> null.also { scope.launch { rescanQuietly() } }
            is EnqueueResult.NoSpace -> {
                // A fresh item leaves nothing behind; a partly downloaded one keeps its files.
                if (!existed) withContext(io) { dir.deleteRecursively() }
                "Not enough space on ${volume.label} for $title: free up ${formatBytes(result.neededBytes)}."
            }
            // Groups are never capped (no storage cap); kept for the exhaustive when.
            is EnqueueResult.OverCap -> null
        }
    }

    private sealed interface Placed {
        data object Nowhere : Placed
        data object Unmounted : Placed
        data class On(val volume: Volume) : Placed
    }

    private suspend fun placedOn(all: List<Volume>, relativeDir: String, records: List<DownloadRecord>): Placed = withContext(io) {
        all.firstOrNull { File(it.root, "$relativeDir/${MediaManifests.FILE_NAME}").isFile }?.let { return@withContext Placed.On(it) }
        if (records.isEmpty()) return@withContext Placed.Nowhere
        all.firstOrNull { v -> records.any { it.filePath.startsWith(File(v.root, relativeDir).path + File.separator) } }
            ?.let { Placed.On(it) } ?: Placed.Unmounted
    }

    /** The manifest, keeping tags already cached for files that haven't changed, and `.nomedia` in its media root. */
    private fun write(dir: File, root: File, m: MediaManifest) {
        val old = MediaManifests.read(dir)?.takeIf { it.source == m.source && it.ref == m.ref }
        val cached = old?.files.orEmpty().filter { it.tags != null }.associateBy { it.name to it.hash }
        val merged = m.copy(files = m.files.map { f -> f.copy(tags = f.tags ?: cached[f.name to f.hash]?.tags) })
        MediaManifests.write(dir, merged)
        val mediaRoot = File(root, MediaManifests.relativeDir(m).substringBefore('/'))
        File(mediaRoot, ".nomedia").let { if (!it.exists()) it.createNewFile() }
    }

    /** Stops the group's unfinished files; the finished ones stay filed (decided with Neal). */
    suspend fun cancel(groupId: String) {
        ops.withLock { downloads.cancelGroup(groupId) }
        rescanQuietly()
    }

    suspend fun retry(groupId: String) = downloads.retryGroup(groupId)

    /**
     * Forgets the item's rows (state kept), removes its group, deletes its folder, then rescans. The folder goes first
     * and the rows after a scan that started once it was gone: a scan that read the folder before it went can't bring
     * the rows back as ghosts.
     */
    suspend fun remove(groupId: String) {
        ops.withLock {
            val (source, ref) = sourceAndRef(groupId) ?: return@withLock
            val relativeDir = listOf("music", "audiobooks").map { "$it/$source/$ref" }
            val records = downloads.records.first().filter { it.groupId == groupId }
            val dirs = withContext(io) {
                volumes.list().flatMap { v -> relativeDir.map { File(v.root, it) } }.filter { it.isDirectory } +
                    records.mapNotNull { r -> relativeDir.firstOrNull { "/$it/" in r.filePath }?.let { File(r.filePath).parentFile } }
            }.distinct()
            val manifests = withContext(io) { dirs.mapNotNull { MediaManifests.read(it) } }

            downloads.removeGroup(groupId)
            withContext(io) { dirs.forEach { it.deleteRecursively() } }
            rescanQuietly()

            val audiobookId = "audiobook:$source:$ref"
            val trackIds = buildSet {
                manifests.forEach { m -> m.files.forEach { add(DownloadedIds.trackId(m, it)) } }
                media.chapters(audiobookId).first().forEach { add(it.trackId) }
                val key = Albums.itemKey(source, ref)
                media.tracks.first().filter { it.albumKey == key }.forEach { add(it.id) }
            }
            media.forget(trackIds, listOf(audiobookId))
        }
    }

    /** The runner's onInstalled for AUDIO records: one rescan at a time; never throws. */
    suspend fun onFileInstalled(record: DownloadRecord) {
        if (record.kind != ItemKind.AUDIO || record.groupId?.startsWith(MediaDownloads.PREFIX) != true) return
        rescanQuietly()
    }

    /**
     * Scans downloaded media. Scans run one at a time; a call returns once a scan that started after it has finished
     * (one already running when it was asked may have missed its file), and calls made during one scan share the next.
     */
    suspend fun rescan() {
        val ticket = requested.incrementAndGet()
        scanLock.withLock {
            if (scannedUpTo >= ticket) return
            val upTo = requested.get()
            scanner.scan()
            scannedUpTo = upTo
        }
    }

    /** [rescan], logging rather than throwing: for app start, finished files and removals. */
    suspend fun rescanQuietly() {
        try {
            rescan()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            runCatching { android.util.Log.e("Granger", "Downloaded media scan failed", e) }
        }
    }

    /** Validated before any path is built from it: a LibriVox id is digits, an identifier passes archive.org's rule. */
    private fun sourceAndRef(groupId: String): Pair<String, String>? {
        MediaDownloads.refOf(groupId, MediaManifest.LIBRIVOX)?.takeIf(LibriVoxClient::isValidId)?.let { return MediaManifest.LIBRIVOX to it }
        MediaDownloads.refOf(groupId, MediaManifest.ARCHIVE_ORG)?.takeIf(ArchiveQuery::isValidIdentifier)?.let { return MediaManifest.ARCHIVE_ORG to it }
        return null
    }
}
