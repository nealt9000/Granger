package app.granger.core.library

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * [now] stamps `addedAt` on rows new to the library; a row already there keeps its own. A re-synced audiobook's
 * chapters are replaced (they come from the files). Otherwise [sync] deletes nothing, for any origin; only [forget]
 * deletes catalog rows, and only downloaded ones.
 */
class RoomMediaLibrary(private val db: LibraryDatabase, private val now: () -> Long = System::currentTimeMillis) : MediaLibrary {
    private val dao = db.mediaDao()

    override val tracks: Flow<List<Track>> = dao.observeMusic()
    override val audiobooks: Flow<List<Audiobook>> = dao.observeAudiobooks()
    override fun chapters(audiobookId: String): Flow<List<AudiobookChapter>> = dao.observeChapters(audiobookId)
    override val allChapters: Flow<List<AudiobookChapter>> = dao.observeAllChapters()
    override val albumNotes: Flow<List<AlbumNote>> = dao.observeAlbumNotes()
    override suspend fun track(id: String): Track? = dao.track(id)
    override suspend fun audiobook(id: String): Audiobook? = dao.audiobook(id)
    override suspend fun coverPaths(): Set<String> = dao.coverPaths().toSet()

    override suspend fun sync(origin: MediaOrigin, result: ScanResult, keep: (uri: String) -> Boolean) {
        require(result.tracks.all { it.origin == origin }) { "Scan for $origin holds tracks of another origin" }
        require(result.audiobooks.all { it.book.origin == origin }) { "Scan for $origin holds audiobooks of another origin" }
        val scannedAlbums = result.tracks.filter { it.audiobookId == null }.mapTo(HashSet()) { it.albumKey }
        require(result.albumNotes.all { it.albumKey in scannedAlbums }) { "Scan for $origin holds notes for albums it didn't scan" }
        db.withTransaction {
            val at = now()

            val oldTracks = dao.tracksOf(origin).associateBy { it.id }
            val newTracks = result.tracks.associateBy { it.id }
            dao.upsertTracks(newTracks.values.mapNotNull { track ->
                val old = oldTracks[track.id]
                val merged = track.copy(addedAt = old?.addedAt ?: at)
                merged.takeIf { it != old }
            })
            val kept = oldTracks.values.filter { it.id !in newTracks && keep(it.uri) }
            val keptIds = kept.mapTo(HashSet()) { it.id }
            val keptBooks = kept.mapNotNullTo(HashSet()) { it.audiobookId }
            for (old in oldTracks.values) {
                if (old.id !in newTracks && old.id !in keptIds && old.status != PackStatus.MISSING) {
                    dao.setTrackStatus(old.id, PackStatus.MISSING)
                }
            }

            val oldBooks = dao.audiobooksOf(origin).associateBy { it.id }
            val newBooks = result.audiobooks.associateBy { it.book.id }
            dao.upsertAudiobooks(newBooks.values.mapNotNull { scanned ->
                val old = oldBooks[scanned.book.id]
                val merged = scanned.book.copy(addedAt = old?.addedAt ?: at)
                merged.takeIf { it != old }
            })
            for (old in oldBooks.values) {
                if (old.id !in newBooks && old.id !in keptBooks && old.status != PackStatus.MISSING) {
                    dao.setAudiobookStatus(old.id, PackStatus.MISSING)
                }
            }

            for (scanned in newBooks.values) {
                dao.deleteChapters(scanned.book.id)
                dao.insertChapters(chapterRows(scanned, newTracks))
            }

            val noted = result.albumNotes.associateBy { it.albumKey }
            for (keys in (scannedAlbums - noted.keys).chunked(MAX_IDS)) dao.deleteAlbumNotes(keys)
            dao.upsertAlbumNotes(noted.values.toList())
        }
    }

    override suspend fun forget(trackIds: Collection<String>, audiobookIds: Collection<String>) {
        if (trackIds.isEmpty() && audiobookIds.isEmpty()) return
        db.withTransaction {
            for (ids in audiobookIds.distinct().chunked(MAX_IDS)) {
                dao.deleteChaptersOf(ids)
                dao.deleteAudiobooks(ids)
            }
            for (ids in trackIds.distinct().chunked(MAX_IDS)) dao.deleteTracks(ids)
            dao.deleteOrphanAlbumNotes()
        }
    }

    /** A chapter runs to the next chapter in the same file, or to the end of the file. */
    private suspend fun chapterRows(scanned: ScannedAudiobook, tracks: Map<String, Track>): List<AudiobookChapter> =
        scanned.chapters.mapIndexed { ordinal, (trackId, chapter) ->
            val next = scanned.chapters.getOrNull(ordinal + 1)?.takeIf { it.first == trackId }?.second?.startMs
            val end = next ?: (tracks[trackId] ?: dao.track(trackId))?.durationMs ?: chapter.startMs
            AudiobookChapter(scanned.book.id, ordinal, chapter.title, trackId, chapter.startMs, (end - chapter.startMs).coerceAtLeast(0L))
        }
}

/** Ids per delete statement: under SQLite's 999-variable limit on API 29. */
private const val MAX_IDS = 900
