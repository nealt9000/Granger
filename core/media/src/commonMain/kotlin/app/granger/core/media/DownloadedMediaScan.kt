package app.granger.core.media

import app.granger.core.library.AlbumNote
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
import app.granger.core.text.hex
import app.granger.core.text.sha1
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
 *   Its description is filed as its [AlbumNote].
 *   Embedded tags give a track its title (when the origin has none), duration, ReplayGain and art, never its album.
 * - A file is present when it exists at its full size; `*.part` files never count. Present files' tags are read once,
 *   read-only and under [tagGuard], and cached in the manifest, so later scans never open them.
 * - A manifest that can't be read (torn, not JSON, or naming another folder or unsafe file names) skips only its
 *   item, and is listed in [lastUnreadable]: its rows are left exactly as they are. An item on an unmounted volume
 *   is absent, so its rows go MISSING with their state kept.
 *
 * Shared since Plan I6 (paths as strings, files through [MediaDisk]); Android's `DownloadedMediaScanner` keeps its
 * `File` constructor over this.
 */
open class DownloadedMediaScan(
    private val roots: () -> List<String>,
    private val media: MediaLibrary,
    private val tagReader: PathTagReader,
    private val tagGuard: TagReadGuard = TagReadGuard.NONE,
) {
    private val lock = Mutex()
    private var running: CompletableDeferred<Int>? = null

    /** Item folders whose manifest couldn't be read at the last scan (skipped, rows untouched). */
    @Volatile var lastUnreadablePaths: List<String> = emptyList()
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
    suspend fun itemPaths(): List<Pair<String, MediaManifest>> = withContext(Dispatchers.IO) { survey(roots()).items }

    private class Survey(val items: List<Pair<String, MediaManifest>>, val unreadable: List<String>, val unlisted: List<String>)

    private fun survey(volumes: List<String>): Survey {
        val items = mutableListOf<Pair<String, MediaManifest>>()
        val unreadable = mutableListOf<String>()
        val unlisted = mutableListOf<String>()
        val seen = HashSet<String>()
        for (root in volumes) for (parent in PARENTS) {
            val dir = MediaDisk.child(root, parent)
            if (!MediaDisk.exists(dir)) continue
            val children = MediaDisk.list(dir)
            if (children == null) {
                unlisted += dir
                continue
            }
            for (child in children.filter { MediaDisk.isDirectory(it) && !MediaDisk.nameOf(it).startsWith(".") }.sortedBy { MediaDisk.nameOf(it) }) {
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
    private fun valid(root: String, dir: String, m: MediaManifest): Boolean = try {
        m.version <= MANIFEST_VERSION &&
            m.kind in KINDS &&
            when (m.source) {
                MediaManifest.LIBRIVOX -> m.kind == MediaManifest.AUDIOBOOK && LIBRIVOX_ID.matches(m.ref)
                MediaManifest.ARCHIVE_ORG -> IDENTIFIER.matches(m.ref) && !m.ref.startsWith(".")
                else -> false
            } &&
            MediaDisk.canonical(MediaDisk.child(root, MediaManifests.relativeDir(m))) == MediaDisk.canonical(dir) &&
            (m.files + listOfNotNull(m.cover)).let { all -> all.all { isSafeName(it.name) } && all.map { it.name }.toSet().size == all.size }
    } catch (e: Exception) {
        false
    }

    private fun isSafeName(name: String) =
        name.isNotEmpty() && !name.startsWith(".") && '/' !in name && '\\' !in name && '\u0000' !in name && MediaDisk.nameOf(name) == name

    private suspend fun runScan(): Int {
        val volumes = try {
            roots()
        } catch (e: Exception) {
            return 0   // unknown, not empty: nothing changes
        }
        val survey = survey(volumes)
        lastUnreadablePaths = survey.unreadable
        if (survey.unreadable.isNotEmpty()) {
            MediaDisk.logWarn("Skipped unreadable media manifests: ${survey.unreadable.joinToString()}")
        }
        val skipTags = tagGuard.skipped()

        var read = 0
        val tracks = mutableMapOf<MediaOrigin, MutableList<Track>>()
        val books = mutableMapOf<MediaOrigin, MutableList<ScannedAudiobook>>()
        val notes = mutableMapOf<MediaOrigin, MutableList<AlbumNote>>()
        for ((dir, m) in survey.items) {
            val filed = file(dir, m, skipTags)
            read += filed.read
            val origin = DownloadedIds.origin(m)
            tracks.getOrPut(origin) { mutableListOf() } += filed.tracks
            filed.book?.let { books.getOrPut(origin) { mutableListOf() } += it }
            filed.note?.let { notes.getOrPut(origin) { mutableListOf() } += it }
        }

        // Rows under a folder that couldn't be read or listed are unknown, not gone.
        val unknown = (survey.unreadable + survey.unlisted).map { MediaDisk.fileUri(it) + "/" }
        for (origin in ORIGINS) {
            val result = ScanResult(tracks[origin].orEmpty(), books[origin].orEmpty(), notes[origin].orEmpty())
            media.sync(origin, result) { uri -> unknown.any { uri.startsWith(it) } }
        }
        return read
    }

    private class Filed(val tracks: List<Track>, val book: ScannedAudiobook?, val note: AlbumNote?, val read: Int)

    private fun file(dir: String, original: MediaManifest, skipTags: Set<String>): Filed {
        val folder = MediaManifests.relativeDir(original) + "/"
        val present = original.files.indices.filter { i ->
            val f = original.files[i]
            MediaDisk.child(dir, f.name).let { MediaDisk.isFile(it) && MediaDisk.length(it) == f.sizeBytes }
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

        val coverFile = m.cover?.let { c -> MediaDisk.child(dir, c.name).takeIf { MediaDisk.isFile(it) && MediaDisk.length(it) == c.sizeBytes } }
        val audiobookId = if (m.kind == MediaManifest.AUDIOBOOK) DownloadedIds.audiobookId(m) else null
        val album = MediaManifests.displayTitle(m)
        val tracks = present.map { i -> track(dir, folder, m, i, album, coverFile, audiobookId) }
        val book = audiobookId?.takeIf { tracks.isNotEmpty() }?.let { id ->
            val readers = present.flatMap { m.files[it].readers }.distinct().ifEmpty { m.readers }
            ScannedAudiobook(
                book = Audiobook(
                    id = id, origin = DownloadedIds.origin(m), sourceRef = m.ref, title = album,
                    author = m.creators.joinToString(JOIN), narrator = readers.joinToString(JOIN), description = m.description,
                    coverPath = coverFile ?: tracks.firstNotNullOfOrNull { it.coverPath },
                    durationMs = tracks.sumOf { it.durationMs }, status = PackStatus.OK, addedAt = 0L,
                ),
                trackIds = tracks.map { it.id },
                chapters = tracks.map { it.id to ScannedChapter(it.title, 0L) },
            )
        }
        // An album's description has no track column: it's kept as the album's note.
        val note = m.description.trim().takeIf { m.kind == MediaManifest.ALBUM && it.isNotEmpty() && tracks.isNotEmpty() }
            ?.let { AlbumNote(Albums.itemKey(m.source, m.ref), it) }
        return Filed(tracks, book, note, read)
    }

    private fun track(dir: String, folder: String, m: MediaManifest, index: Int, album: String, coverFile: String?, audiobookId: String?): Track {
        val f = m.files[index]
        val tags = f.tags
        val file = MediaDisk.child(dir, f.name)
        val embedded = tags?.embeddedCover?.takeIf { EMBEDDED_COVER.matches(it) }?.let { MediaDisk.child(dir, it) }?.takeIf { MediaDisk.isFile(it) }
        return Track(
            id = DownloadedIds.trackId(m, f), origin = DownloadedIds.origin(m), uri = MediaDisk.fileUri(file), folder = folder,
            title = f.title.trim().ifEmpty { tags?.title?.trim().orEmpty() }.ifEmpty { stem(f.remoteName) },
            album = album, albumKey = Albums.itemKey(m.source, m.ref), albumArtists = m.creators.joinToString(JOIN),
            artists = f.readers.ifEmpty { m.creators }.joinToString(JOIN), composers = "", genres = m.genres.joinToString(JOIN),
            disc = null, trackNo = f.trackNo ?: (index + 1), date = m.date, originalDate = "", releaseType = "",
            sortTitle = "", sortAlbum = "", sortArtist = "", sortAlbumArtist = "",
            durationMs = tags?.durationMs?.takeIf { it > 0 } ?: f.durationMs ?: 0L,
            rgTrackGain = tags?.rgTrackGain, rgTrackPeak = tags?.rgTrackPeak, rgAlbumGain = tags?.rgAlbumGain, rgAlbumPeak = tags?.rgAlbumPeak,
            coverPath = coverFile ?: embedded, audiobookId = audiobookId, status = PackStatus.OK, addedAt = 0L,
        )
    }

    /**
     * The file's tags, and whether TagLib was asked (false for a file that crashed it before: it's cached with no tags,
     * so it's never tried again). null when the file couldn't be opened or read: it's filed untagged and tried next scan.
     * The file's uri is marked in [tagGuard] while TagLib reads; only a return or an exception clears it, so a crash
     * leaves it for the next scan to skip.
     */
    private fun readTags(dir: String, folder: String, f: ManifestFile, skipTags: Set<String>): Pair<CachedTags, Boolean>? {
        val file = MediaDisk.child(dir, f.name)
        val uri = MediaDisk.fileUri(file)
        if (uri in skipTags) return NO_TAGS to false
        val found = FoundFile(uri, f.name, folder, f.sizeBytes, MediaDisk.lastModified(file), 0L, systemAudiobook = false, inAudiobookFolder = false)
        val raw = try {
            tagReader.read(file, found, tagGuard).raw
        } catch (e: Exception) {
            return null
        }
        return cached(dir, raw, f.name) to true
    }

    private fun cached(dir: String, raw: RawTags?, name: String): CachedTags {
        val title = raw?.properties?.get("TITLE")?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val rg = TagParser.parse(raw, name, 0L).replayGain
        return CachedTags(
            title = title, durationMs = raw?.durationMs?.coerceAtLeast(0L) ?: 0L,
            rgTrackGain = rg?.trackGainDb, rgTrackPeak = rg?.trackPeak, rgAlbumGain = rg?.albumGainDb, rgAlbumPeak = rg?.albumPeak,
            embeddedCover = raw?.cover?.takeIf { it.isNotEmpty() }?.let { saveCover(dir, it) },
        )
    }

    /** `.cover-<sha1 of the image>.jpg` in the item folder: art embedded in every track is stored once. Its name, or null. */
    private fun saveCover(dir: String, bytes: ByteArray): String? = try {
        val name = ".cover-${hash16(bytes)}.jpg"
        val target = MediaDisk.child(dir, name)
        if (!MediaDisk.exists(target)) {
            val tmp = MediaDisk.child(dir, "$name.tmp")
            MediaDisk.writeBytes(tmp, bytes)
            if (!MediaDisk.renameTo(tmp, target)) MediaDisk.delete(tmp)
        }
        name.takeIf { MediaDisk.isFile(target) }
    } catch (e: Exception) {
        null
    }

    /**
     * Adds the new tags to the manifest as it is on disk now (it may have been rewritten since it was read, by a Get on
     * the same item): only files with the same name and hash, still without tags, take them. Failing to write only
     * means the tags are read again next time.
     */
    private fun writeBack(dir: String, original: MediaManifest, files: List<ManifestFile>) {
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

    private fun hash16(bytes: ByteArray) = sha1(bytes).hex().take(16)

    private companion object {
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
