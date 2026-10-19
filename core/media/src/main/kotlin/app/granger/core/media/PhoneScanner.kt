package app.granger.core.media

import android.os.ParcelFileDescriptor
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
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Scans the phone's music and audiobooks into [MediaLibrary], referenced in place.
 *
 * - Every file is opened through [open], which opens read-only ([ContentResolverOpener]); nothing else touches
 *   the phone's files. Covers are copied into [coversDir], Granger's own storage.
 * - A file's id is its [Fingerprint], so a moved or renamed file keeps its id, position and playlist entries.
 * - An unchanged file (same uri, size and date as in the scan cache) isn't opened: its row in [media] is reused.
 * - Without [hasPermission], every phone item goes MISSING (kept, with its state) and no source is asked.
 * - A source (or one picked folder) that throws means "unknown", not "empty": the items it owns are left exactly as
 *   they are while the other sources sync. If nothing could be listed, nothing changes.
 * - A file that can't be opened or read is skipped; one TagLib can't parse still becomes a track named from its file.
 * - A file whose tags crashed the app (native TagLib) during an earlier scan is never read for tags again, see
 *   [TagReadGuard]: it becomes a track named from its file.
 * - After a scan in which every source listed, covers in [coversDir] that no row names are deleted ([CoverSweep]).
 * - Only PHONE rows are synced: downloaded media (LIBRIVOX, ARCHIVE_ORG) belong to [DownloadedMediaScanner].
 */
class PhoneScanner(
    private val sources: List<AudioSource>,
    private val open: (String) -> ParcelFileDescriptor?,
    private val media: MediaLibrary,
    private val playback: PlaybackDao,
    private val coversDir: File,
    private val hasPermission: () -> Boolean,
    private val tagReader: TagReader = NativeTagReader,
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
        if (!hasPermission()) {
            media.markAllMissing(MediaOrigin.PHONE)
            return 0
        }
        // Each piece is listed on its own; a failed one (null) is unknown, and the rows it owns are left as they are.
        val listings: List<Pair<AudioSource, List<FoundFile>?>> = sources.flatMap { source ->
            val parts = attempt { source.parts() } ?: return@flatMap listOf(source to null)
            parts.map { part -> part to attempt { part.list() } }
        }
        if (listings.none { it.second != null }) return 0
        val failed = listings.filter { it.second == null }.map { it.first }
        val found = listings.flatMap { it.second.orEmpty() }
        return withContext(Dispatchers.IO) {
            val read = readAndSync(found) { uri -> failed.any { it.owns(uri) } }
            // Only after a complete listing: a source that failed may own rows whose covers would look unreferenced.
            if (failed.isEmpty()) sweepCovers()
            read
        }
    }

    /**
     * Deletes covers in [coversDir] that no row (of any status) names. Downloaded media keep their covers in their own
     * item folders, so only phone rows name files here. Never fails the scan.
     */
    private suspend fun sweepCovers() {
        try {
            val referenced = HashSet<String>()
            media.tracks.first().mapNotNullTo(referenced) { it.coverPath }
            media.audiobooks.first().mapNotNullTo(referenced) { it.coverPath }
            // Audiobook files aren't in [MediaLibrary.tracks]; every one of them has a chapter.
            media.allChapters.first().mapTo(HashSet()) { it.trackId }
                .forEach { id -> media.track(id)?.coverPath?.let(referenced::add) }
            CoverSweep.sweep(coversDir, referenced)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A cover left behind costs a little space; the next complete scan tries again.
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun readAndSync(found: List<FoundFile>, unknown: (String) -> Boolean): Int {
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
            byFingerprint.putIfAbsent(e.fingerprint, e)
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
        playback.scanCacheUris().filter { it !in seen && !unknown(it) }
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
        val chapters = track.audiobookId?.let { id -> storedChapters.getOrPut(id) { media.chapters(id).first() } }
            .orEmpty()
            .filter { it.trackId == track.id }
            .map { Chapter(it.title, it.startMs) }
        return Entry(file, cache.fingerprint, track.toTags(), chapters, track.coverPath, audiobook, wasRead = false)
    }

    /** Opens and reads [file]; null (skipped) if it can't be opened or its bytes can't be read. */
    private fun read(file: FoundFile, folderCovers: MutableMap<String, String?>, skipTags: Set<String>): Entry? {
        val pfd = try {
            open(file.uri)
        } catch (e: Exception) {
            null
        } ?: return null
        val (fingerprint, read) = try {
            pfd.use {
                // Not closed: closing a stream made from a FileDescriptor would close the fd under pfd.
                val fingerprint = Fingerprint.of(FileInputStream(it.fileDescriptor).channel)
                fingerprint to readTags(it, file, skipTags)
            }
        } catch (e: Exception) {
            return null
        }
        val tags = TagParser.parse(read.raw, file.displayName, file.durationMs)
        val cover = read.raw?.cover?.let { embeddedCover(it) }
            ?: file.folderCoverUri?.let { uri -> folderCovers.getOrPut(uri) { folderCover(uri) } }
        return Entry(file, fingerprint, tags, read.chapters, cover, MediaClassifier.isAudiobook(file), wasRead = true)
    }

    /**
     * The file's tags, with [file]'s uri marked in [tagGuard] while TagLib reads it. Not cleared in a `finally`: only a
     * return or an exception clears it, so a crash (or an Error) leaves it for the next scan to skip.
     */
    private fun readTags(pfd: ParcelFileDescriptor, file: FoundFile, skipTags: Set<String>): FileTags {
        if (file.uri in skipTags) return FileTags(raw = null, chapters = emptyList())
        tagGuard.begin(file.uri)
        val tags = try {
            tagReader.read(pfd, file)
        } catch (e: Exception) {
            tagGuard.end()
            throw e
        }
        tagGuard.end()
        return tags
    }

    /** `coversDir/<sha1 of the image>.jpg`: an album's art embedded in every track is stored once, and shared. */
    private fun embeddedCover(bytes: ByteArray): String? = save(File(coversDir, "${sha1(bytes)}.jpg")) { bytes }

    /** A folder's `folder.jpg`/`cover.jpg`, copied once per image (not per track), named by its uri. */
    private fun folderCover(uri: String): String? = save(File(coversDir, "folder-${sha1(uri)}.jpg")) {
        open(uri)?.use { pfd -> FileInputStream(pfd.fileDescriptor).readBytes() }
    }

    private fun save(target: File, bytes: () -> ByteArray?): String? = try {
        if (!target.exists()) {
            val data = bytes()?.takeIf { it.isNotEmpty() } ?: return null
            coversDir.mkdirs()
            val tmp = File(coversDir, "${target.name}.tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(target)) tmp.delete()
        }
        target.takeIf { it.exists() }?.path
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

    private fun sha1(s: String) = sha1(s.toByteArray())

    private fun sha1(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)

    private companion object {
        /** How [Track] joins multi-valued tags (see its doc). TagParser splits on ";", so no value contains it. */
        const val JOIN = "; "
        /** Under SQLite's 999 bound variables on API 29. */
        const val CACHE_DELETE_BATCH = 500
        val NO_REPLAY_GAIN = ReplayGain(null, null, null, null)
    }
}
