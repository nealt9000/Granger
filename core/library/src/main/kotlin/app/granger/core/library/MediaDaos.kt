package app.granger.core.library

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The media catalog: tracks, audiobooks and chapters. Written only by [RoomMediaLibrary.sync], and deleted from only by
 * [RoomMediaLibrary.forget] (removed downloads; never a PHONE row).
 */
@Dao
interface MediaDao {
    /** Music only: audiobook files are left out. */
    @Query("SELECT * FROM tracks WHERE audiobookId IS NULL ORDER BY COALESCE(NULLIF(sortTitle, ''), title) COLLATE NOCASE, id")
    fun observeMusic(): Flow<List<Track>>

    @Query("SELECT * FROM audiobooks ORDER BY title COLLATE NOCASE, id")
    fun observeAudiobooks(): Flow<List<Audiobook>>

    @Query("SELECT * FROM audiobook_chapters WHERE audiobookId = :audiobookId ORDER BY ordinal")
    fun observeChapters(audiobookId: String): Flow<List<AudiobookChapter>>

    @Query("SELECT * FROM audiobook_chapters ORDER BY audiobookId, ordinal")
    fun observeAllChapters(): Flow<List<AudiobookChapter>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun track(id: String): Track?

    @Query("SELECT * FROM audiobooks WHERE id = :id")
    suspend fun audiobook(id: String): Audiobook?

    /** Every cover any track or audiobook row names: any origin, any status, audiobook files included. */
    @Query("SELECT coverPath FROM tracks WHERE coverPath IS NOT NULL UNION SELECT coverPath FROM audiobooks WHERE coverPath IS NOT NULL")
    suspend fun coverPaths(): List<String>

    @Query("SELECT * FROM tracks WHERE origin = :origin")
    suspend fun tracksOf(origin: MediaOrigin): List<Track>

    @Query("SELECT * FROM audiobooks WHERE origin = :origin")
    suspend fun audiobooksOf(origin: MediaOrigin): List<Audiobook>

    @Upsert
    suspend fun upsertTracks(tracks: List<Track>)

    @Upsert
    suspend fun upsertAudiobooks(books: List<Audiobook>)

    @Query("UPDATE tracks SET status = :status WHERE id = :id")
    suspend fun setTrackStatus(id: String, status: PackStatus)

    @Query("UPDATE audiobooks SET status = :status WHERE id = :id")
    suspend fun setAudiobookStatus(id: String, status: PackStatus)

    @Query("UPDATE tracks SET status = :status WHERE origin = :origin")
    suspend fun setTrackStatusForOrigin(origin: MediaOrigin, status: PackStatus)

    @Query("UPDATE audiobooks SET status = :status WHERE origin = :origin")
    suspend fun setAudiobookStatusForOrigin(origin: MediaOrigin, status: PackStatus)

    @Query("DELETE FROM audiobook_chapters WHERE audiobookId = :audiobookId")
    suspend fun deleteChapters(audiobookId: String)

    @Insert
    suspend fun insertChapters(chapters: List<AudiobookChapter>)

    /** Downloaded rows only: a PHONE row is never deleted, whatever its id. Keep [ids] under 999 variables; callers chunk. */
    @Query("DELETE FROM tracks WHERE id IN (:ids) AND origin != 'PHONE'")
    suspend fun deleteTracks(ids: List<String>)

    /** The chapters of these downloaded audiobooks (none of a PHONE one). Run before [deleteAudiobooks]; callers chunk. */
    @Query("DELETE FROM audiobook_chapters WHERE audiobookId IN (SELECT id FROM audiobooks WHERE id IN (:ids) AND origin != 'PHONE')")
    suspend fun deleteChaptersOf(ids: List<String>)

    /** Downloaded rows only, as [deleteTracks]. Their chapters go first ([deleteChaptersOf]); callers chunk. */
    @Query("DELETE FROM audiobooks WHERE id IN (:ids) AND origin != 'PHONE'")
    suspend fun deleteAudiobooks(ids: List<String>)
}

/** What the listener made: positions, bookmarks, playlists, the music queue, recent plays, plus scan settings. */
@Dao
interface PlaybackDao {
    @Query("SELECT * FROM audiobook_states WHERE audiobookId = :audiobookId")
    suspend fun audiobookState(audiobookId: String): AudiobookState?

    @Upsert
    suspend fun upsertAudiobookState(state: AudiobookState)

    @Query("SELECT * FROM audiobook_states")
    fun observeAudiobookStates(): Flow<List<AudiobookState>>

    @Query("SELECT * FROM music_state WHERE id = 0")
    suspend fun musicState(): MusicState?

    @Query("SELECT * FROM music_state WHERE id = 0")
    fun observeMusicState(): Flow<MusicState?>

    @Upsert
    suspend fun upsertMusicState(state: MusicState)

    @Query("SELECT * FROM scan_cache WHERE uri = :uri")
    suspend fun scanCache(uri: String): ScanCacheEntry?

    @Upsert
    suspend fun upsertScanCache(entry: ScanCacheEntry)

    @Query("SELECT uri FROM scan_cache")
    suspend fun scanCacheUris(): List<String>

    /** Keep [uris] under SQLite's 999-variable limit (API 29); callers chunk. */
    @Query("DELETE FROM scan_cache WHERE uri IN (:uris)")
    suspend fun deleteScanCache(uris: List<String>)

    @Query("SELECT * FROM audio_bookmarks WHERE audiobookId = :audiobookId ORDER BY createdAt, id")
    fun observeBookmarks(audiobookId: String): Flow<List<AudioBookmark>>

    @Insert
    suspend fun insertBookmark(bookmark: AudioBookmark): Long

    @Update
    suspend fun updateBookmark(bookmark: AudioBookmark)

    @Delete
    suspend fun deleteBookmark(bookmark: AudioBookmark)

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE, id")
    fun observePlaylists(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun playlist(id: Long): Playlist?

    @Insert
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY ordinal")
    fun observeEntries(playlistId: Long): Flow<List<PlaylistEntry>>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY ordinal")
    suspend fun entries(playlistId: Long): List<PlaylistEntry>

    @Insert
    suspend fun insertEntries(entries: List<PlaylistEntry>)

    @Query("UPDATE playlists SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String, updatedAt: Long)

    @Query("UPDATE playlists SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchPlaylist(id: Long, updatedAt: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun deleteEntries(playlistId: Long)

    /** The playlist's tracks in entry order, MISSING ones included; an entry whose track row doesn't exist is left out. */
    @Query(
        "SELECT tracks.* FROM playlist_entries INNER JOIN tracks ON tracks.id = playlist_entries.trackId " +
            "WHERE playlist_entries.playlistId = :playlistId ORDER BY playlist_entries.ordinal",
    )
    fun observePlaylistTracks(playlistId: Long): Flow<List<Track>>

    @Upsert
    suspend fun upsertRecentPlay(play: RecentPlay)

    @Query("SELECT * FROM recent_plays ORDER BY playedAt DESC, `key` LIMIT :limit")
    fun observeRecentPlays(limit: Int): Flow<List<RecentPlay>>

    /** A folder already added keeps its first [MediaFolder.addedAt]. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(folder: MediaFolder)

    @Query("DELETE FROM media_folders WHERE uri = :uri")
    suspend fun deleteFolder(uri: String)

    @Query("SELECT * FROM media_folders ORDER BY addedAt, uri")
    fun observeFolders(): Flow<List<MediaFolder>>
}
