package app.granger.core.library

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/** :core:library can't see :core:media's Chapter; the scanner maps each Chapter to a ScannedChapter. */
data class ScannedChapter(val title: String, val startMs: Long)

/** [chapters] pairs each chapter with its track id, in listening order. */
data class ScannedAudiobook(val book: Audiobook, val trackIds: List<String>, val chapters: List<Pair<String, ScannedChapter>>)

/** [tracks] holds every scanned track, audiobook files included (with `audiobookId` set). */
data class ScanResult(val tracks: List<Track>, val audiobooks: List<ScannedAudiobook>)

interface MediaLibrary {
    /** Music only (audiobookId == null), all statuses. */
    val tracks: Flow<List<Track>>
    val audiobooks: Flow<List<Audiobook>>
    fun chapters(audiobookId: String): Flow<List<AudiobookChapter>>

    /** Every audiobook's chapters, by book then ordinal (the shelf's progress bars). */
    val allChapters: Flow<List<AudiobookChapter>>
    suspend fun track(id: String): Track?
    suspend fun audiobook(id: String): Audiobook?

    /**
     * Replaces [origin]'s catalog with [result]: new rows inserted, changed rows updated, absent rows set MISSING.
     * Never deletes rows, states, bookmarks or playlist entries. A MISSING row that reappears goes back to OK.
     *
     * An absent track whose uri [keep] accepts is left exactly as it is (its source couldn't be listed, so it's
     * unknown, not gone), and so is an absent audiobook with any such track.
     */
    suspend fun sync(origin: MediaOrigin, result: ScanResult, keep: (uri: String) -> Boolean = { false })

    /** Sets every [origin] row MISSING (permission lost). */
    suspend fun markAllMissing(origin: MediaOrigin)
}

/**
 * [now] stamps `addedAt` on rows new to the library; a row already there keeps its own. A re-synced audiobook's
 * chapters are replaced (they come from the files); nothing else is ever deleted.
 */
class RoomMediaLibrary(private val db: LibraryDatabase, private val now: () -> Long = System::currentTimeMillis) : MediaLibrary {
    private val dao = db.mediaDao()

    override val tracks: Flow<List<Track>> = dao.observeMusic()
    override val audiobooks: Flow<List<Audiobook>> = dao.observeAudiobooks()
    override fun chapters(audiobookId: String): Flow<List<AudiobookChapter>> = dao.observeChapters(audiobookId)
    override val allChapters: Flow<List<AudiobookChapter>> = dao.observeAllChapters()
    override suspend fun track(id: String): Track? = dao.track(id)
    override suspend fun audiobook(id: String): Audiobook? = dao.audiobook(id)

    override suspend fun sync(origin: MediaOrigin, result: ScanResult, keep: (uri: String) -> Boolean) {
        require(result.tracks.all { it.origin == origin }) { "Scan for $origin holds tracks of another origin" }
        require(result.audiobooks.all { it.book.origin == origin }) { "Scan for $origin holds audiobooks of another origin" }
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
        }
    }

    override suspend fun markAllMissing(origin: MediaOrigin) {
        db.withTransaction {
            dao.setTrackStatusForOrigin(origin, PackStatus.MISSING)
            dao.setAudiobookStatusForOrigin(origin, PackStatus.MISSING)
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
