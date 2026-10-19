package app.granger.core.media

import android.net.Uri
import android.os.ParcelFileDescriptor
import app.granger.core.library.Albums
import app.granger.core.library.Audiobook
import app.granger.core.library.MediaLibrary
import app.granger.core.library.MediaOrigin
import app.granger.core.library.PackStatus
import app.granger.core.library.ScanResult
import app.granger.core.library.ScannedAudiobook
import app.granger.core.library.ScannedChapter
import app.granger.core.library.Track
import app.granger.core.taglib.RawTags
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Files Granger's own downloads (LibriVox and archive.org audio, under each volume's `music/` and `audiobooks/`) into
 * [MediaLibrary], from each item folder's [MediaManifest]. These are Granger's files, so no audio permission is needed.
 *
 * - Syncs only [MediaOrigin.LIBRIVOX] and [MediaOrigin.ARCHIVE_ORG], never PHONE: phone media is [PhoneScanner]'s.
 * - An album is one origin item: its tracks share `Albums.itemKey(source, ref)` and the item's title and creators.
 *   Embedded tags give a track its title (when the origin has none), duration, ReplayGain and art, never its album.
 * - A file is present when it exists at its full size; `*.part` files never count. Present files' tags are read once,
 *   read-only and under [tagGuard], and cached in the manifest, so later scans never open them.
 * - A manifest that can't be read (torn, not JSON, or naming another folder or unsafe file names) skips only its
 *   item, and is listed in [lastUnreadable]: its rows are left exactly as they are. An item on an unmounted volume
 *   is absent, so its rows go MISSING with their state kept.
 */
class DownloadedMediaScanner(
    private val roots: () -> List<File>,
    private val media: MediaLibrary,
    private val tagReader: TagReader = NativeTagReader,
    private val tagGuard: TagReadGuard = TagReadGuard.NONE,
) {
    private val lock = Mutex()
    private var running: CompletableDeferred<Int>? = null

    /** Item folders whose manifest couldn't be read at the last scan (skipped, rows untouched). */
    @Volatile var lastUnreadable: List<File> = emptyList()
        private set

    /**
     * Files every item from its manifest into LIBRIVOX and ARCHIVE_ORG. Mutex-guarded; a scan already running is joined.
     * Returns files whose tags were read.
     */
    suspend fun scan(): Int {
        var owner = false
        val job = lock.withLock {
            running ?: CompletableDeferred<Int>().also {
                running = it
                owner = true
            }
        }
        if (!owner) return job.await()
        val result = runCatching { withContext(Dispatchers.IO) { runScan() } }
        withContext(NonCancellable) { lock.withLock { running = null } }
        job.completeWith(result)
        return result.getOrThrow()
    }

    /** Every item folder with a readable manifest, on every mounted volume. */
    suspend fun items(): List<Pair<File, MediaManifest>> = withContext(Dispatchers.IO) { survey(roots()).items }

    private class Survey(val items: List<Pair<File, MediaManifest>>, val unreadable: List<File>, val unlisted: List<File>)

    private fun survey(volumes: List<File>): Survey {
        val items = mutableListOf<Pair<File, MediaManifest>>()
        val unreadable = mutableListOf<File>()
        val unlisted = mutableListOf<File>()
        val seen = HashSet<String>()
        for (root in volumes) for (parent in PARENTS) {
            val dir = File(root, parent)
            if (!dir.exists()) continue
            val children = dir.listFiles()
            if (children == null) {
                unlisted += dir
                continue
            }
            for (child in children.filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name }) {
                when (val parsed = MediaManifests.parse(child)) {
                    MediaManifests.Parsed.Missing -> Unit   // not (or no longer) an item
                    MediaManifests.Parsed.Unreadable -> unreadable += child
                    is MediaManifests.Parsed.Ok -> {
                        val m = parsed.manifest
                        when {
                            !valid(root, child, m) -> unreadable += child
                            // The same item on two volumes: the first (internal storage) wins.
                            seen.add(MediaManifests.relativeDir(m)) -> items += child to m
                        }
                    }
                }
            }
        }
        return Survey(items, unreadable, unlisted)
    }

    /** Known source and kind, a well-formed ref naming [dir] itself, and file names that stay inside it. */
    private fun valid(root: File, dir: File, m: MediaManifest): Boolean = try {
        m.version <= MANIFEST_VERSION &&
            m.kind in KINDS &&
            when (m.source) {
                MediaManifest.LIBRIVOX -> m.kind == MediaManifest.AUDIOBOOK && LIBRIVOX_ID.matches(m.ref)
                MediaManifest.ARCHIVE_ORG -> IDENTIFIER.matches(m.ref) && !m.ref.startsWith(".")
                else -> false
            } &&
            File(root, MediaManifests.relativeDir(m)).canonicalFile == dir.canonicalFile &&
            (m.files + listOfNotNull(m.cover)).let { all -> all.all { isSafeName(it.name) } && all.map { it.name }.toSet().size == all.size }
    } catch (e: Exception) {
        false
    }

    private fun isSafeName(name: String) =
        name.isNotEmpty() && !name.startsWith(".") && '/' !in name && '\\' !in name && '\u0000' !in name && File(name).name == name

    private suspend fun runScan(): Int {
        val volumes = try {
            roots()
        } catch (e: Exception) {
            return 0   // unknown, not empty: nothing changes
        }
        val survey = survey(volumes)
        lastUnreadable = survey.unreadable
        if (survey.unreadable.isNotEmpty()) {
            runCatching { android.util.Log.w(TAG, "Skipped unreadable media manifests: ${survey.unreadable.joinToString()}") }
        }
        val skipTags = tagGuard.skipped()

        var read = 0
        val tracks = mutableMapOf<MediaOrigin, MutableList<Track>>()
        val books = mutableMapOf<MediaOrigin, MutableList<ScannedAudiobook>>()
        for ((dir, m) in survey.items) {
            val filed = file(dir, m, skipTags)
            read += filed.read
            val origin = DownloadedIds.origin(m)
            tracks.getOrPut(origin) { mutableListOf() } += filed.tracks
            filed.book?.let { books.getOrPut(origin) { mutableListOf() } += it }
        }

        // Rows under a folder that couldn't be read or listed are unknown, not gone.
        val unknown = (survey.unreadable + survey.unlisted).map { Uri.fromFile(it).toString() + "/" }
        for (origin in ORIGINS) {
            val result = ScanResult(tracks[origin].orEmpty(), books[origin].orEmpty())
            media.sync(origin, result) { uri -> unknown.any { uri.startsWith(it) } }
        }
        return read
    }

    private class Filed(val tracks: List<Track>, val book: ScannedAudiobook?, val read: Int)

    private fun file(dir: File, original: MediaManifest, skipTags: Set<String>): Filed {
        val folder = MediaManifests.relativeDir(original) + "/"
        val present = original.files.indices.filter { i ->
            val f = original.files[i]
            File(dir, f.name).let { it.isFile && it.length() == f.sizeBytes }
        }

        // Tags for present files not yet cached: read once, then kept in the manifest.
        val files = original.files.toMutableList()
        var read = 0
        var changed = false
        for (i in present) {
            val f = files[i]
            if (f.tags != null) continue
            val (tags, wasRead) = readTags(dir, folder, f, skipTags) ?: continue
            files[i] = f.copy(tags = tags)
            changed = true
            if (wasRead) read++
        }
        val m = original.copy(files = files)
        if (changed) writeBack(dir, original, files)

        val coverFile = m.cover?.let { c -> File(dir, c.name).takeIf { it.isFile && it.length() == c.sizeBytes } }
        val audiobookId = if (m.kind == MediaManifest.AUDIOBOOK) DownloadedIds.audiobookId(m) else null
        val album = MediaManifests.displayTitle(m)
        val tracks = present.map { i -> track(dir, folder, m, i, album, coverFile, audiobookId) }
        val book = audiobookId?.takeIf { tracks.isNotEmpty() }?.let { id ->
            val readers = present.flatMap { m.files[it].readers }.distinct().ifEmpty { m.readers }
            ScannedAudiobook(
                book = Audiobook(
                    id = id, origin = DownloadedIds.origin(m), sourceRef = m.ref, title = album,
                    author = m.creators.joinToString(JOIN), narrator = readers.joinToString(JOIN), description = m.description,
                    coverPath = coverFile?.path ?: tracks.firstNotNullOfOrNull { it.coverPath },
                    durationMs = tracks.sumOf { it.durationMs }, status = PackStatus.OK, addedAt = 0L,
                ),
                trackIds = tracks.map { it.id },
                chapters = tracks.map { it.id to ScannedChapter(it.title, 0L) },
            )
        }
        return Filed(tracks, book, read)
    }

    private fun track(dir: File, folder: String, m: MediaManifest, index: Int, album: String, coverFile: File?, audiobookId: String?): Track {
        val f = m.files[index]
        val tags = f.tags
        val file = File(dir, f.name)
        val embedded = tags?.embeddedCover?.takeIf { EMBEDDED_COVER.matches(it) }?.let { File(dir, it) }?.takeIf { it.isFile }
        return Track(
            id = DownloadedIds.trackId(m, f), origin = DownloadedIds.origin(m), uri = Uri.fromFile(file).toString(), folder = folder,
            title = f.title.trim().ifEmpty { tags?.title?.trim().orEmpty() }.ifEmpty { stem(f.remoteName) },
            album = album, albumKey = Albums.itemKey(m.source, m.ref), albumArtists = m.creators.joinToString(JOIN),
            artists = f.readers.ifEmpty { m.creators }.joinToString(JOIN), composers = "", genres = m.genres.joinToString(JOIN),
            disc = null, trackNo = f.trackNo ?: (index + 1), date = m.date, originalDate = "", releaseType = "",
            sortTitle = "", sortAlbum = "", sortArtist = "", sortAlbumArtist = "",
            durationMs = tags?.durationMs?.takeIf { it > 0 } ?: f.durationMs ?: 0L,
            rgTrackGain = tags?.rgTrackGain, rgTrackPeak = tags?.rgTrackPeak, rgAlbumGain = tags?.rgAlbumGain, rgAlbumPeak = tags?.rgAlbumPeak,
            coverPath = coverFile?.path ?: embedded?.path, audiobookId = audiobookId, status = PackStatus.OK, addedAt = 0L,
        )
    }

    /**
     * The file's tags, and whether TagLib was asked (false for a file that crashed it before: it's cached with no tags,
     * so it's never tried again). null when the file couldn't be opened or read: it's filed untagged and tried next scan.
     * The file's uri is marked in [tagGuard] while TagLib reads; only a return or an exception clears it, so a crash
     * leaves it for the next scan to skip.
     */
    private fun readTags(dir: File, folder: String, f: ManifestFile, skipTags: Set<String>): Pair<CachedTags, Boolean>? {
        val file = File(dir, f.name)
        val uri = Uri.fromFile(file).toString()
        if (uri in skipTags) return NO_TAGS to false
        val found = FoundFile(uri, f.name, folder, f.sizeBytes, file.lastModified(), 0L, systemAudiobook = false, inAudiobookFolder = false)
        val raw = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                tagGuard.begin(uri)
                val tags = try {
                    tagReader.read(pfd, found)
                } catch (e: Exception) {
                    tagGuard.end()
                    throw e
                }
                tagGuard.end()
                tags.raw
            }
        } catch (e: Exception) {
            return null
        }
        return cached(dir, raw, f.name) to true
    }

    private fun cached(dir: File, raw: RawTags?, name: String): CachedTags {
        val title = raw?.properties?.get("TITLE")?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val rg = TagParser.parse(raw, name, 0L).replayGain
        return CachedTags(
            title = title, durationMs = raw?.durationMs?.coerceAtLeast(0L) ?: 0L,
            rgTrackGain = rg?.trackGainDb, rgTrackPeak = rg?.trackPeak, rgAlbumGain = rg?.albumGainDb, rgAlbumPeak = rg?.albumPeak,
            embeddedCover = raw?.cover?.takeIf { it.isNotEmpty() }?.let { saveCover(dir, it) },
        )
    }

    /** `.cover-<sha1 of the image>.jpg` in the item folder: art embedded in every track is stored once. Its name, or null. */
    private fun saveCover(dir: File, bytes: ByteArray): String? = try {
        val name = ".cover-${sha1(bytes)}.jpg"
        val target = File(dir, name)
        if (!target.exists()) {
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) tmp.delete()
        }
        name.takeIf { target.isFile }
    } catch (e: Exception) {
        null
    }

    /**
     * Adds the new tags to the manifest as it is on disk now (it may have been rewritten since it was read, by a Get on
     * the same item): only files with the same name and hash, still without tags, take them. Failing to write only
     * means the tags are read again next time.
     */
    private fun writeBack(dir: File, original: MediaManifest, files: List<ManifestFile>) {
        try {
            val current = (MediaManifests.parse(dir) as? MediaManifests.Parsed.Ok)?.manifest ?: return
            if (current.source != original.source || current.ref != original.ref) return
            val fresh = files.filter { it.tags != null }.associateBy { it.name to it.hash }
            val merged = current.files.map { f -> if (f.tags == null) fresh[f.name to f.hash]?.let { f.copy(tags = it.tags) } ?: f else f }
            if (merged != current.files) MediaManifests.write(dir, current.copy(files = merged))
        } catch (e: Exception) {
            // Not cached: read again next scan.
        }
    }

    private fun stem(remoteName: String): String {
        val name = remoteName.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    private fun sha1(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)

    private companion object {
        const val TAG = "Granger"
        const val JOIN = "; "
        const val MANIFEST_VERSION = 1
        val PARENTS = listOf("music/archive-org", "audiobooks/archive-org", "audiobooks/librivox")
        val ORIGINS = listOf(MediaOrigin.LIBRIVOX, MediaOrigin.ARCHIVE_ORG)
        val KINDS = setOf(MediaManifest.AUDIOBOOK, MediaManifest.ALBUM)
        val LIBRIVOX_ID = Regex("[0-9]+")
        val IDENTIFIER = Regex("[A-Za-z0-9._-]+")
        val EMBEDDED_COVER = Regex("\\.cover-[0-9a-f]{16}\\.jpg")
        val NO_TAGS = CachedTags("", 0L, null, null, null, null, null)
    }
}
