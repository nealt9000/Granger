package app.granger.media

import app.granger.core.archive.ArchiveQuery
import app.granger.core.archive.LibriVoxClient
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
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
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
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
    /** After every downloaded-media scan (a filing, a removal, "Rescan now"): tells other apps' views to refresh. */
    private val onChanged: () -> Unit = {},
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
        // A worker that outlived an earlier removal's sweep may have left a stray folder since.
        sweepStrayFolders()
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
            roots.firstOrNull { root -> all.any { File(it.root, "$root/$source/$ref/${MediaManifests.FILE_NAME}").isFile } }
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
                dirs.any { d -> MediaManifests.read(d)?.files.orEmpty().any { File(d, it.name).isFile } }
            }
            if (audioOnDisk) return@withLock
            downloads.removeGroup(groupId)
            withContext(io) { dirs.forEach { deleteTree(it.toPath()) } }
        }
        rescanQuietly()
    }

    /** The item's folders: under either media root on every mounted volume, and wherever its ledger rows point. */
    private suspend fun itemDirs(source: String, ref: String, records: List<DownloadRecord>): List<File> {
        val relativeDir = MEDIA_ROOTS.map { "$it/$source/$ref" }
        return withContext(io) {
            volumes.list().flatMap { v -> relativeDir.map { File(v.root, it) } }.filter { it.isDirectory } +
                records.mapNotNull { r -> relativeDir.firstOrNull { "/$it/" in r.filePath }?.let { File(r.filePath).parentFile } }
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

            downloads.removeGroup(groupId)
            withContext(io) { dirs.forEach { it.deleteRecursively() } }
            rescanQuietly()
            sweepStrayFolders()

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
                val sourceDir = File(volume.root, "$shelf/$source")
                if (isLink(sourceDir)) continue
                for (item in sourceDir.listFiles().orEmpty()) {
                    if (!item.isDirectory || isLink(item) || File(item, MediaManifests.FILE_NAME).exists()) continue
                    val prefix = item.path + File.separator
                    if (ledger.any { it.startsWith(prefix) }) continue
                    deleteTree(item.toPath())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { android.util.Log.w("Granger", "Stray media folder sweep failed", e) }
        }
    }

    private fun isLink(f: File) = Files.isSymbolicLink(f.toPath())

    /**
     * Deletes [root] and what's in it, best effort (what can't be deleted stays); a symlink, [root] included, is deleted
     * as a link, never followed.
     */
    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                runCatching { Files.deleteIfExists(file) }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                runCatching { Files.deleteIfExists(dir) }
                return FileVisitResult.CONTINUE
            }
        })
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
        runCatching { onChanged() }
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
