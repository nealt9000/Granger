package app.granger.media

import app.granger.UiText
import app.granger.core.archive.ArchiveQuery
import app.granger.core.archive.LibriVoxClient
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.EnqueueResult
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.DownloadVolume
import app.granger.core.downloads.DownloadVolumes
import app.granger.core.library.Albums
import app.granger.core.library.MediaLibrary
import app.granger.core.media.DownloadedIds
import app.granger.core.media.DownloadedMediaScan
import app.granger.core.media.MediaDisk
import app.granger.core.media.MediaManifest
import app.granger.core.media.MediaManifests
import app.granger.screens.bytesText
import app.granger.screens.ioDispatcher
import app.granger.screens.logWarn
import app.granger.sources.DownloadPrefs
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
 *
 * Shared since Plan I6 Task 3: a book's or album's files are one group on the shared queue, `media:<source>:<ref>`,
 * filed by the shared [DownloadedMediaScan]. Item folders go through [MediaDisk]. Android's `MediaDownloader` keeps its
 * constructor (its `VolumeSource`) over this.
 */
@OptIn(ExperimentalAtomicApi::class)
open class MediaItemDownloader(
    private val downloads: Downloads,
    private val scanner: DownloadedMediaScan,
    private val media: MediaLibrary,
    private val volumes: DownloadVolumes,
    private val prefs: DownloadPrefs,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = ioDispatcher,
    /** After every downloaded-media scan (a filing, a removal, "Rescan now"): tells other apps' views to refresh. */
    private val onChanged: () -> Unit = {},
    /** Before a removal deletes anything: the player lets go of the book or tracks (`PlayerHub.unload`). */
    private val onRemoving: (audiobookId: String, trackIds: Set<String>) -> Unit = { _, _ -> },
) {
    /** Get, cancel and remove, one at a time. */
    private val ops = Mutex()

    private val scanLock = Mutex()
    private val requested = AtomicLong(0)
    @Volatile private var scannedUpTo = 0L

    /** Writes the manifest (and `.nomedia` in the media root), then queues the group. Returns the notice to show, or null. An item already on another volume is completed there. */
    suspend fun get(m: MediaManifest): UiText? = ops.withLock {
        val title = MediaManifests.displayTitle(m)
        val group = MediaDownloads.groupId(m)
        require(sourceAndRef(group) != null) { "Bad media ref ${m.source}:${m.ref}" }
        val relativeDir = MediaManifests.relativeDir(m)
        // A worker that outlived an earlier removal's sweep may have left a stray folder since.
        sweepStrayFolders()
        val all = withContext(io) { volumes.list() }
        val records = downloads.records.first().filter { it.groupId == group }
        val volume = when (val placed = placedOn(all, relativeDir, records)) {
            Placed.Nowhere -> all.firstOrNull { it.root == prefs.volumePath } ?: all.firstOrNull()
            Placed.Unmounted -> return@withLock UiText.Res("media_unmounted", title)
            is Placed.On -> placed.volume
        } ?: return@withLock UiText.Res("sources_no_storage")

        val dir = MediaDisk.child(volume.root, relativeDir)
        val existed = withContext(io) {
            val existed = MediaDisk.isDirectory(dir)
            write(dir, volume.root, m)
            existed
        }
        // An album you asked for goes next in the queue (Plan DL Decision 2).
        val result = downloads.enqueueGroup(group, MediaDownloads.specs(m), volume.root, prefs.wifiOnly, next = true)
        when (result) {
            EnqueueResult.Queued, EnqueueResult.AlreadyActive -> null
            // Every file is in already: the new manifest may name it differently, so file it again.
            EnqueueResult.AlreadyInstalled -> null.also { scope.launch { rescanQuietly() } }
            is EnqueueResult.NoSpace -> {
                // A fresh item leaves nothing behind; a partly downloaded one keeps its files.
                if (!existed) withContext(io) { MediaDisk.deleteRecursively(dir) }
                UiText.Res("sources_no_space", volume.label, title, bytesText(result.neededBytes))
            }
        }
    }

    private sealed interface Placed {
        data object Nowhere : Placed
        data object Unmounted : Placed
        data class On(val volume: DownloadVolume) : Placed
    }

    private suspend fun placedOn(all: List<DownloadVolume>, relativeDir: String, records: List<DownloadRecord>): Placed = withContext(io) {
        all.firstOrNull { MediaDisk.isFile(MediaDisk.child(it.root, "$relativeDir/${MediaManifests.FILE_NAME}")) }?.let { return@withContext Placed.On(it) }
        if (records.isEmpty()) return@withContext Placed.Nowhere
        all.firstOrNull { v -> records.any { it.filePath.startsWith(MediaDisk.child(v.root, relativeDir) + "/") } }
            ?.let { Placed.On(it) } ?: Placed.Unmounted
    }

    /** The manifest, keeping tags already cached for files that haven't changed, and `.nomedia` in its media root. */
    private fun write(dir: String, root: String, m: MediaManifest) {
        val old = MediaManifests.read(dir)?.takeIf { it.source == m.source && it.ref == m.ref }
        val cached = old?.files.orEmpty().filter { it.tags != null }.associateBy { it.name to it.hash }
        val merged = m.copy(files = m.files.map { f -> f.copy(tags = f.tags ?: cached[f.name to f.hash]?.tags) })
        MediaManifests.write(dir, merged)
        val mediaRoot = MediaDisk.child(root, MediaManifests.relativeDir(m).substringBefore('/'))
        MediaDisk.child(mediaRoot, ".nomedia").let { if (!MediaDisk.exists(it)) MediaDisk.createNewFile(it) }
    }

    /**
     * The media root, "music" or "audiobooks", that holds [groupId]'s item: by its ledger rows, else its manifest on a
     * mounted volume. Null when it's in neither. One archive.org item is one group whichever view got it, so the views
     * ask this before a Get: an item in the other root is opened there, never got again.
     */
    suspend fun mediaRootOf(groupId: String): String? {
        val (source, ref) = sourceAndRef(groupId) ?: return null
        val roots = listOf("music", "audiobooks")
        val records = downloads.records.first().filter { it.groupId == groupId }
        roots.firstOrNull { root -> records.any { "/$root/$source/$ref/" in it.filePath } }?.let { return it }
        return withContext(io) {
            val all = volumes.list()
            roots.firstOrNull { root -> all.any { MediaDisk.isFile(MediaDisk.child(it.root, "$root/$source/$ref/${MediaManifests.FILE_NAME}")) } }
        }
    }

    /**
     * Stops the group's unfinished files; the finished ones stay filed (decided with Neal). With no audio file in yet
     * there's nothing to keep: the group and the item folder (manifest, maybe the cover) go too, so no cover-only item
     * shows in other apps.
     */
    suspend fun cancel(groupId: String) {
        ops.withLock {
            downloads.cancelGroup(groupId)
            val (source, ref) = sourceAndRef(groupId) ?: return@withLock
            val records = downloads.records.first().filter { it.groupId == groupId }
            if (records.any { it.state == DownloadState.INSTALLED && !MediaDownloads.isCover(it) }) return@withLock
            val dirs = itemDirs(source, ref, records)
            val audioOnDisk = withContext(io) {
                dirs.any { d -> MediaManifests.read(d)?.files.orEmpty().any { MediaDisk.isFile(MediaDisk.child(d, it.name)) } }
            }
            if (audioOnDisk) return@withLock
            downloads.removeGroup(groupId)
            withContext(io) { dirs.forEach { MediaDisk.deleteTree(it) } }
        }
        rescanQuietly()
    }

    /** The item's folders: under either media root on every mounted volume, and wherever its ledger rows point. */
    private suspend fun itemDirs(source: String, ref: String, records: List<DownloadRecord>): List<String> {
        val relativeDir = MEDIA_ROOTS.map { "$it/$source/$ref" }
        return withContext(io) {
            volumes.list().flatMap { v -> relativeDir.map { MediaDisk.child(v.root, it) } }.filter { MediaDisk.isDirectory(it) } +
                records.mapNotNull { r -> relativeDir.firstOrNull { "/$it/" in r.filePath }?.let { MediaDisk.parent(r.filePath) } }
        }.distinct()
    }

    suspend fun retry(groupId: String) = downloads.retryGroup(groupId)

    /**
     * Forgets the item's rows (state kept), removes its group, deletes its folder, then rescans. The folder goes first
     * and the rows after a scan that started once it was gone: a scan that read the folder before it went can't bring
     * the rows back as ghosts. A worker still running then can recreate the folder for its `.part`, so after the rescan
     * [sweepStrayFolders] runs too.
     */
    suspend fun remove(groupId: String) {
        ops.withLock {
            val (source, ref) = sourceAndRef(groupId) ?: return@withLock
            val records = downloads.records.first().filter { it.groupId == groupId }
            val dirs = itemDirs(source, ref, records)
            val manifests = withContext(io) { dirs.mapNotNull { MediaManifests.read(it) } }
            val audiobookId = "audiobook:$source:$ref"
            val trackIds = buildSet {
                manifests.forEach { m -> m.files.forEach { add(DownloadedIds.trackId(m, it)) } }
                media.chapters(audiobookId).first().forEach { add(it.trackId) }
                val key = Albums.itemKey(source, ref)
                media.tracks.first().filter { it.albumKey == key }.forEach { add(it.id) }
            }
            onRemoving(audiobookId, trackIds)

            downloads.removeGroup(groupId)
            withContext(io) { dirs.forEach { MediaDisk.deleteRecursively(it) } }
            rescanQuietly()
            sweepStrayFolders()
            media.forget(trackIds, listOf(audiobookId))
        }
    }

    /**
     * Deletes item folders (`<volume>/{music,audiobooks}/<source>/<ref>`) that have no manifest and no ledger row
     * pointing into them: what a worker racing a removal leaves (its `.part`, in a folder its mkdirs recreated). Only
     * those folders, never anything else; a symlink is never followed. Run under [ops], so a Get writing its manifest
     * can't be caught between mkdirs and the write. Never throws.
     */
    private suspend fun sweepStrayFolders() = withContext(io) {
        try {
            val ledger = downloads.records.first().map { it.filePath }
            for (volume in volumes.list()) for (shelf in MEDIA_ROOTS) for (source in SOURCES) {
                val sourceDir = MediaDisk.child(volume.root, "$shelf/$source")
                if (MediaDisk.isLink(sourceDir)) continue
                for (item in MediaDisk.list(sourceDir).orEmpty()) {
                    if (!MediaDisk.isDirectory(item) || MediaDisk.isLink(item) || MediaDisk.exists(MediaDisk.child(item, MediaManifests.FILE_NAME))) continue
                    val prefix = "$item/"
                    if (ledger.any { it.startsWith(prefix) }) continue
                    MediaDisk.deleteTree(item)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { logWarn("Stray media folder sweep failed", e) }
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
        val ticket = requested.incrementAndFetch()
        scanLock.withLock {
            if (scannedUpTo >= ticket) return
            val upTo = requested.load()
            scanner.scan()
            scannedUpTo = upTo
        }
        runCatching { onChanged() }
    }

    /** [rescan], logging rather than throwing: for app start, finished files and removals. */
    suspend fun rescanQuietly() {
        try {
            rescan()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            runCatching { logWarn("Downloaded media scan failed", e) }
        }
    }

    private companion object {
        val MEDIA_ROOTS = listOf("music", "audiobooks")
        val SOURCES = listOf(MediaManifest.LIBRIVOX, MediaManifest.ARCHIVE_ORG)
    }

    /** Validated before any path is built from it: a LibriVox id is digits, an identifier passes archive.org's rule. */
    private fun sourceAndRef(groupId: String): Pair<String, String>? {
        MediaDownloads.refOf(groupId, MediaManifest.LIBRIVOX)?.takeIf(LibriVoxClient::isValidId)?.let { return MediaManifest.LIBRIVOX to it }
        MediaDownloads.refOf(groupId, MediaManifest.ARCHIVE_ORG)?.takeIf(ArchiveQuery::isValidIdentifier)?.let { return MediaManifest.ARCHIVE_ORG to it }
        return null
    }
}
