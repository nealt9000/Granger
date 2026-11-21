package app.granger.core.library

import kotlinx.coroutines.flow.Flow

// Plan I5 Task 1: shared so the shelves (Shelves) can file albums on both phones; Android's RoomMediaLibrary implements it.

/** :core:library can't see :core:media's Chapter; the scanner maps each Chapter to a ScannedChapter. */
data class ScannedChapter(val title: String, val startMs: Long)

/** [chapters] pairs each chapter with its track id, in listening order. */
data class ScannedAudiobook(val book: Audiobook, val trackIds: List<String>, val chapters: List<Pair<String, ScannedChapter>>)

/**
 * [tracks] holds every scanned track, audiobook files included (with `audiobookId` set). [albumNotes] describe albums
 * among [tracks] (downloaded ones; a phone scan has none).
 */
data class ScanResult(val tracks: List<Track>, val audiobooks: List<ScannedAudiobook>, val albumNotes: List<AlbumNote> = emptyList())

interface MediaLibrary {
    /** Music only (audiobookId == null), all statuses. */
    val tracks: Flow<List<Track>>
    val audiobooks: Flow<List<Audiobook>>
    fun chapters(audiobookId: String): Flow<List<AudiobookChapter>>

    /** Every audiobook's chapters, by book then ordinal (the shelf's progress bars). */
    val allChapters: Flow<List<AudiobookChapter>>

    /** Every album note (downloaded albums' descriptions), any status. */
    val albumNotes: Flow<List<AlbumNote>>
    suspend fun track(id: String): Track?
    suspend fun audiobook(id: String): Audiobook?

    /** Every cover path any row names (tracks, audiobook files and audiobooks; any origin, any status). */
    suspend fun coverPaths(): Set<String>

    /**
     * Replaces [origin]'s catalog with [result]: new rows inserted, changed rows updated, absent rows set MISSING.
     * Never deletes rows, states, bookmarks or playlist entries. A MISSING row that reappears goes back to OK.
     *
     * An absent track whose uri [keep] accepts is left exactly as it is (its source couldn't be listed, so it's
     * unknown, not gone), and so is an absent audiobook with any such track.
     *
     * Album notes: each of [result]'s notes is written; a scanned album with no note in [result] loses its old one. An
     * absent album's note stays, as its rows do.
     */
    suspend fun sync(origin: MediaOrigin, result: ScanResult, keep: (uri: String) -> Boolean = { false })

    /**
     * Deletes these catalog rows (tracks, audiobooks, and the audiobooks' chapters), and the notes of albums left with
     * no track row. Audiobook states, bookmarks, playlist
     * entries and placements stay, so the same ids coming back (a re-download) find them again.
     *
     * For removed downloads only (LIBRIVOX, ARCHIVE_ORG): a PHONE id is ignored, since phone files are never Granger's to
     * remove. An audiobook's file tracks go only if they're named in [trackIds] too.
     */
    suspend fun forget(trackIds: Collection<String>, audiobookIds: Collection<String>)
}
