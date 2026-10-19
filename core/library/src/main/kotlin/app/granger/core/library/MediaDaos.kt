package app.granger.core.library

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The media catalog: tracks, audiobooks and chapters. Written only by [RoomMediaLibrary.sync]. */
@Dao
interface MediaDao {
    /** Music only: audiobook files are left out. */
    @Query("SELECT * FROM tracks WHERE audiobookId IS NULL ORDER BY COALESCE(NULLIF(sortTitle, ''), title) COLLATE NOCASE, id")
    fun observeMusic(): Flow<List<Track>>

    @Query("SELECT * FROM audiobooks ORDER BY title COLLATE NOCASE, id")
    fun observeAudiobooks(): Flow<List<Audiobook>>

    @Query("SELECT * FROM audiobook_chapters WHERE audiobookId = :audiobookId ORDER BY ordinal")
    fun observeChapters(audiobookId: String): Flow<List<AudiobookChapter>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun track(id: String): Track?

    @Query("SELECT * FROM audiobooks WHERE id = :id")
    suspend fun audiobook(id: String): Audiobook?

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
}

/** What the listener made: positions, bookmarks, playlists, the music queue, recent plays, plus scan settings. */
@Dao
interface PlaybackDao {
    @Query("SELECT * FROM audiobook_states WHERE audiobookId = :audiobookId")
    suspend fun audiobookState(audiobookId: String): AudiobookState?

    @Upsert
    suspend fun upsertAudiobookState(state: AudiobookState)

    @Query("SELECT * FROM music_state WHERE id = 0")
    suspend fun musicState(): MusicState?

    @Upsert
    suspend fun upsertMusicState(state: MusicState)

    @Query("SELECT * FROM scan_cache WHERE uri = :uri")
    suspend fun scanCache(uri: String): ScanCacheEntry?

    @Upsert
    suspend fun upsertScanCache(entry: ScanCacheEntry)

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
