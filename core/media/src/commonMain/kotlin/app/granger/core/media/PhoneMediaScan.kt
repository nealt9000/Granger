package app.granger.core.media

import app.granger.core.library.Albums
import app.granger.core.library.Audiobook
import app.granger.core.library.AudiobookChapter
import app.granger.core.library.MediaLibrary
import app.granger.core.library.MediaOrigin
import app.granger.core.library.PackStatus
import app.granger.core.library.PlaybackDao
import app.granger.core.library.ScanCacheEntry
import app.granger.core.library.ScanResult
import app.granger.core.library.ScannedAudiobook
import app.granger.core.library.ScannedChapter
import app.granger.core.library.Track
import app.granger.core.text.hex
import app.granger.core.text.sha1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * How one phone opens its own files for [PhoneMediaScan] (Plan I6 Task 6): Android through its content resolver (a
 * `ParcelFileDescriptor`, TagLib over JNI), the iPhone through file paths and the Music library (AVAsset, TagLib for MP3).
 */
interface PhoneFiles {
    /**
     * Opens [file] read-only and hands it to [use] (closed after); null when it can't be opened. An exception from [use]
     * comes out of here; so does an `Error` (the stand-in for a native crash), which must never be caught.
     */
    fun <T> open(file: FoundFile, use: (OpenedFile) -> T): T?

    /** A small file's bytes (a folder's `cover.jpg`), or null. */
    fun readBytes(uri: String): ByteArray?
}

/** One file [PhoneFiles] opened. Both reads throw when the bytes can't be read; the scanner then skips the file. */
interface OpenedFile {
    /** The file's [Fingerprint] (or the phone's own lasting id for a file it can't read the bytes of). */
    fun fingerprint(): String

    /** Its tags (null when they can't be parsed) and chapters. */
    fun tags(): FileTags
}

/**
 * Scans the phone's music and audiobooks into [MediaLibrary], referenced in place. Shared since Plan I6 Task 6 (paths as
 * strings, files through [PhoneFiles] and [MediaDisk]); Android's `PhoneScanner` keeps its constructor over this.
 *
 * - Every file is opened through [files], read-only; nothing else touches the phone's files. Covers are copied into
 *   [coversDir], Granger's own storage.
 * - A file's id is its [Fingerprint], so a moved or renamed file keeps its id, position and playlist entries.
 * - An unchanged file (same uri, size and date as in the scan cache) isn't opened: its row in [media] is reused.
 * - Without [hasPermission], the items of a source that needs it go MISSING (kept, with its state) and that source
 *   isn't asked; picked folders hold their own grants and scan as usual. Their scan-cache rows stay, so allowing the
 *   permission again doesn't read every file over.
 * - A source (or one picked folder) that throws means "unknown", not "empty": the items it owns are left exactly as
 *   they are while the other sources sync. If nothing could be listed, nothing changes.
 * - A file that can't be opened or read is skipped; one whose tags can't be parsed still becomes a track named from
 *   its file.
 * - A file whose tags crashed the app (native TagLib) during an earlier scan is never read for tags again, see
 *   [TagReadGuard]: it becomes a track named from its file.
 * - After a scan in which every source listed, covers in [coversDir] that no row names are deleted ([sweepCovers]).
 *   A cache hit whose cover file is gone is read again, so its art comes back.
 * - Only PHONE rows are synced: downloaded media (LIBRIVOX, ARCHIVE_ORG) belong to [DownloadedMediaScan].
 */
open class PhoneMediaScan(
    private val sources: List<AudioSource>,
    private val files: PhoneFiles,
    private val media: MediaLibrary,
    private val playback: PlaybackDao,
    private val coversDir: String,
    private val hasPermission: () -> Boolean,
    private val tagGuard: TagReadGuard = TagReadGuard.NONE,
) {
    private val lock = Mutex()
    private var running: CompletableDeferred<Int>? = null

    /**
     * Runs one scan; returns how many files were opened and read (cache hits don't count). Mutex-guarded; a scan
     * already running is joined, not repeated.
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
        val result = runCatching { runScan() }
        // Cleared before completing, so a call arriving after this point starts a fresh scan.
        withContext(NonCancellable) { lock.withLock { running = null } }
        job.completeWith(result)
        return result.getOrThrow()
    }

    /** One file's identity and tags, freshly read or rebuilt from its stored row. */
    private class Entry(
        val file: FoundFile,
        val fingerprint: String,
        val tags: AudioTags,
        val chapters: List<Chapter>,
        val coverPath: String?,
        val audiobook: Boolean,
        val wasRead: Boolean,
    )

    private suspend fun runScan(): Int {
        val allowed = hasPermission()
        val (scanned, refused) = sources.partition { allowed || !it.needsAudioPermission }
        // Each piece is listed on its own; a failed one (null) is unknown, and the rows it owns are left as they are.
        val listings: List<Pair<AudioSource, List<FoundFile>?>> = refused.map { it to emptyList<FoundFile>() } + scanned.flatMap { source ->
            val parts = attempt { source.parts() } ?: return@flatMap listOf(source to null)
            parts.map { part -> part to attempt { part.list() } }
        }
        if (listings.none { it.second != null }) return 0
        val failed = listings.filter { it.second == null }.map { it.first }
        val found = listings.flatMap { it.second.orEmpty() }
        return withContext(Dispatchers.IO) {
            val read = readAndSync(found, refused, unknown = { uri -> failed.any { it.owns(uri) } })
            // Only after a complete listing: a source that failed or wasn't asked may own rows whose covers would look unreferenced.
            if (failed.isEmpty() && refused.isEmpty()) sweepCovers()
            read
        }
    }

    /**
     * Deletes covers in [coversDir] that no row (of any status) names. Downloaded media keep their covers in their own
     * item folders, so only phone rows name files here. Never fails the scan.
     */
    private suspend fun sweepCovers() {
        try {
            // Straight from the rows: a book file that went MISSING has no chapter, but its row still names its cover.
            sweep(coversDir, media.coverPaths())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A cover left behind costs a little space; the next complete scan tries again.
        }
    }

    /** Deletes every `*.jpg` directly in [dir] whose path isn't in [referenced]. */
    protected open fun sweep(dir: String, referenced: Set<String>) {
        val files = MediaDisk.list(dir) ?: return
        for (f in files) if (MediaDisk.isFile(f) && MediaDisk.nameOf(f).endsWith(".jpg") && f !in referenced) MediaDisk.delete(f)
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun readAndSync(found: List<FoundFile>, refused: List<AudioSource>, unknown: (String) -> Boolean): Int {
        val storedChapters = mutableMapOf<String, List<AudiobookChapter>>()
        val folderCovers = mutableMapOf<String, String?>()
        val skipTags = tagGuard.skipped()
        val entries = found.distinctBy { it.uri }.mapNotNull { file ->
            cached(file, storedChapters) ?: read(file, folderCovers, skipTags)
        }

        // The same bytes from two sources (a picked folder inside shared storage) are one track; the first source wins.
        val byFingerprint = LinkedHashMap<String, Entry>()
        val audiobookFingerprints = mutableSetOf<String>()
        for (e in entries) {
            if (e.fingerprint !in byFingerprint) byFingerprint[e.fingerprint] = e
            if (e.audiobook) audiobookFingerprints += e.fingerprint
        }
        val unique = byFingerprint.values.toList()

        val (bookEntries, musicEntries) = unique.partition { it.fingerprint in audiobookFingerprints }
        val entryOf = bookEntries.associateBy { it.file }
        val books = AudiobookGrouper.group(bookEntries.map { it.file to it.tags }) { entryOf.getValue(it).chapters }
            .map { files -> audiobook(files.map(entryOf::getValue)) }

        val tracks = musicEntries.map { track(it, audiobookId = null) } + books.flatMap { it.second }
        media.sync(MediaOrigin.PHONE, ScanResult(tracks, books.map { it.first }), keep = unknown)

        val read = entries.filter { it.wasRead }
        for (e in read) playback.upsertScanCache(ScanCacheEntry(e.file.uri, e.file.sizeBytes, e.file.modifiedMs, e.fingerprint))
        // Files no longer listed drop out of the cache, except those of a piece that couldn't be listed this time.
        val seen = found.mapTo(HashSet()) { it.uri }
        playback.scanCacheUris().filter { uri -> uri !in seen && !unknown(uri) && refused.none { it.owns(uri) } }
            .chunked(CACHE_DELETE_BATCH)
            .forEach { playback.deleteScanCache(it) }
        return read.size
    }

    /** A cache hit rebuilt from the stored row, without opening the file; null when the file must be read. */
    private suspend fun cached(file: FoundFile, storedChapters: MutableMap<String, List<AudiobookChapter>>): Entry? {
        val cache = playback.scanCache(file.uri) ?: return null
        if (cache.sizeBytes != file.sizeBytes || cache.modifiedMs != file.modifiedMs) return null
        val track = media.track(trackId(cache.fingerprint)) ?: return null
        val audiobook = MediaClassifier.isAudiobook(file)
        // Filed as music before: its chapters were never stored, so read them now.
        if (audiobook && track.audiobookId == null) return null
        // Its cover file is gone (deleted by hand, or swept): read the file again, so its art comes back.
        if (track.coverPath?.let { !MediaDisk.exists(it) } == true) return null
        val chapters = track.audiobookId?.let { id -> storedChapters.getOrPut(id) { media.chapters(id).first() } }
            .orEmpty()
            .filter { it.trackId == track.id }
            .map { Chapter(it.title, it.startMs) }
        return Entry(file, cache.fingerprint, track.toTags(), chapters, track.coverPath, audiobook, wasRead = false)
    }

    /** Opens and reads [file]; null (skipped) if it can't be opened or its bytes can't be read. */
    private fun read(file: FoundFile, folderCovers: MutableMap<String, String?>, skipTags: Set<String>): Entry? {
        val (fingerprint, read) = try {
            files.open(file) { opened -> opened.fingerprint() to readTags(opened, file, skipTags) }
        } catch (e: Exception) {
            null
        } ?: return null
        val tags = TagParser.parse(read.raw, file.displayName, file.durationMs)
        val cover = read.raw?.cover?.let { embeddedCover(it) }
            ?: file.folderCoverUri?.let { uri -> folderCovers.getOrPut(uri) { folderCover(uri) } }
        return Entry(file, fingerprint, tags, read.chapters, cover, MediaClassifier.isAudiobook(file), wasRead = true)
    }

    /**
     * The file's tags, with [file]'s uri marked in [tagGuard] while they're read. Not cleared in a `finally`: only a
     * return or an exception clears it, so a crash (or an Error) leaves it for the next scan to skip.
     */
    private fun readTags(opened: OpenedFile, file: FoundFile, skipTags: Set<String>): FileTags {
        if (file.uri in skipTags) return FileTags(raw = null, chapters = emptyList())
        tagGuard.begin(file.uri)
        val tags = try {
            opened.tags()
        } catch (e: Exception) {
            tagGuard.end()
            throw e
        }
        tagGuard.end()
        return tags
    }

    /** `coversDir/<sha1 of the image>.jpg`: an album's art embedded in every track is stored once, and shared. */
    private fun embeddedCover(bytes: ByteArray): String? = save("${sha1Of(bytes)}.jpg") { bytes }

    /** A folder's `folder.jpg`/`cover.jpg`, copied once per image (not per track), named by its uri. */
    private fun folderCover(uri: String): String? = save("folder-${sha1Of(uri.encodeToByteArray())}.jpg") { files.readBytes(uri) }

    private fun save(name: String, bytes: () -> ByteArray?): String? = try {
        val target = MediaDisk.child(coversDir, name)
        if (!MediaDisk.exists(target)) {
            val data = bytes()?.takeIf { it.isNotEmpty() } ?: return null
            MediaDisk.writeReplacing(coversDir, name, data)
        }
        target.takeIf { MediaDisk.exists(it) }
    } catch (e: Exception) {
        null
    }

    private fun track(e: Entry, audiobookId: String?): Track {
        val t = e.tags
        val albumArtists = t.albumArtists.joinToString(JOIN)
        return Track(
            id = trackId(e.fingerprint), origin = MediaOrigin.PHONE, uri = e.file.uri, folder = e.file.folder,
            title = t.title, album = t.album, albumKey = Albums.key(albumArtists, t.album, e.file.folder),
            albumArtists = albumArtists, artists = t.artists.joinToString(JOIN), composers = t.composers.joinToString(JOIN),
            genres = t.genres.joinToString(JOIN), disc = t.disc, trackNo = t.track, date = t.date,
            originalDate = t.originalDate, releaseType = t.releaseType, sortTitle = t.sort.title, sortAlbum = t.sort.album,
            sortArtist = t.sort.artist, sortAlbumArtist = t.sort.albumArtist, durationMs = t.durationMs,
            rgTrackGain = t.replayGain?.trackGainDb, rgTrackPeak = t.replayGain?.trackPeak,
            rgAlbumGain = t.replayGain?.albumGainDb, rgAlbumPeak = t.replayGain?.albumPeak,
            coverPath = e.coverPath, audiobookId = audiobookId, status = PackStatus.OK, addedAt = 0L,
        )
    }

    /** The book and its file tracks. Its id is the first file's fingerprint, so it survives the files moving. */
    private fun audiobook(files: List<Entry>): Pair<ScannedAudiobook, List<Track>> {
        val first = files.first()
        val id = "audiobook:phone:${first.fingerprint}"
        val tracks = files.map { track(it, audiobookId = id) }
        val folderName = first.file.folder.trimEnd('/').substringAfterLast('/')
        val title = first.tags.album.ifBlank { if (files.size == 1) first.tags.title else folderName }.ifBlank { first.tags.title }
        val book = Audiobook(
            id = id, origin = MediaOrigin.PHONE, sourceRef = first.fingerprint, title = title,
            author = files.firstNotNullOfOrNull { e -> e.tags.albumArtists.takeIf { it.isNotEmpty() } }.orEmpty().joinToString(JOIN),
            narrator = first.tags.composers.joinToString(JOIN), description = "",
            coverPath = files.firstNotNullOfOrNull { it.coverPath }, durationMs = files.sumOf { it.tags.durationMs },
            status = PackStatus.OK, addedAt = 0L,
        )
        // A file's own chapters if it has any, else the file is one chapter named by its title.
        val chapters = files.zip(tracks).flatMap { (e, t) ->
            e.chapters.ifEmpty { listOf(Chapter(e.tags.title, 0L)) }.map { t.id to ScannedChapter(it.title, it.startMs) }
        }
        return ScannedAudiobook(book, tracks.map { it.id }, chapters) to tracks
    }

    /** The inverse of [track]'s tag columns; totals aren't stored and nothing downstream needs them. */
    private fun Track.toTags(): AudioTags {
        fun split(s: String) = if (s.isEmpty()) emptyList() else s.split(JOIN)
        val rg = ReplayGain(rgTrackGain, rgTrackPeak, rgAlbumGain, rgAlbumPeak).takeUnless { it == NO_REPLAY_GAIN }
        return AudioTags(
            title = title, album = album, albumArtists = split(albumArtists), artists = split(artists),
            composers = split(composers), genres = split(genres), disc = disc, discTotal = null, track = trackNo,
            trackTotal = null, date = date, originalDate = originalDate, releaseType = releaseType,
            sort = SortTags(sortTitle, sortAlbum, sortArtist, sortAlbumArtist), replayGain = rg, durationMs = durationMs,
        )
    }

    private fun trackId(fingerprint: String) = "track:phone:$fingerprint"

    private fun sha1Of(bytes: ByteArray) = sha1(bytes).hex().take(16)

    private companion object {
        /** How [Track] joins multi-valued tags (see its doc). TagParser splits on ";", so no value contains it. */
        const val JOIN = "; "
        /** Under SQLite's 999 bound variables on API 29. */
        const val CACHE_DELETE_BATCH = 500
        val NO_REPLAY_GAIN = ReplayGain(null, null, null, null)
    }
}
