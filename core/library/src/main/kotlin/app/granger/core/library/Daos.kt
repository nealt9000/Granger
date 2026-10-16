package app.granger.core.library

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PackDao {
    @Query("SELECT * FROM packs ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<Pack>>

    @Query("SELECT * FROM packs")
    suspend fun getAll(): List<Pack>

    /** Blocking; never call on the main thread. */
    @Query("SELECT * FROM packs WHERE id = :id")
    fun get(id: String): Pack?

    @Upsert
    suspend fun upsert(pack: Pack)

    @Query("DELETE FROM packs WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Bookmark>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE packId = :packId AND path = :path)")
    fun observeExists(packId: String, path: String): Flow<Boolean>

    @Upsert
    suspend fun upsert(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE packId = :packId AND path = :path")
    suspend fun delete(packId: String, path: String)
}

@Dao
interface ReadingPositionDao {
    @Upsert
    suspend fun upsert(position: ReadingPosition)

    @Query("SELECT * FROM reading_positions WHERE packId = :packId AND path = :path")
    suspend fun get(packId: String, path: String): ReadingPosition?

    @Query("SELECT * FROM reading_positions WHERE packId = :packId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun lastRead(packId: String): ReadingPosition?
}
