package app.granger.core.library

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
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

    /** Moves bookmarks to another pack; one that already exists there wins. */
    @Query("UPDATE OR IGNORE bookmarks SET packId = :to WHERE packId = :from")
    suspend fun moveToPack(from: String, to: String)

    @Query("DELETE FROM bookmarks WHERE packId = :packId")
    suspend fun deleteForPack(packId: String)
}

@Dao
interface ReadingPositionDao {
    @Upsert
    suspend fun upsert(position: ReadingPosition)

    @Query("SELECT * FROM reading_positions WHERE packId = :packId AND path = :path")
    suspend fun get(packId: String, path: String): ReadingPosition?

    @Query("SELECT * FROM reading_positions WHERE packId = :packId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun lastRead(packId: String): ReadingPosition?

    @Query("UPDATE OR IGNORE reading_positions SET packId = :to WHERE packId = :from")
    suspend fun moveToPack(from: String, to: String)

    @Query("DELETE FROM reading_positions WHERE packId = :packId")
    suspend fun deleteForPack(packId: String)
}

@Dao
interface ShelfDao {
    @Query("SELECT * FROM media_types ORDER BY sortOrder")
    fun observeMediaTypes(): Flow<List<MediaType>>

    @Query("SELECT * FROM media_types ORDER BY sortOrder")
    suspend fun getMediaTypes(): List<MediaType>

    @Upsert
    suspend fun upsertMediaType(type: MediaType)

    @Query("DELETE FROM media_types WHERE id = :id")
    suspend fun deleteMediaType(id: String)

    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE")
    fun observeCollections(): Flow<List<ShelfCollection>>

    @Query("SELECT * FROM collections")
    suspend fun getCollections(): List<ShelfCollection>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun collection(id: String): ShelfCollection?

    @Query("SELECT * FROM collections WHERE defaultKey = :key")
    suspend fun collectionByDefaultKey(key: String): ShelfCollection?

    @Upsert
    suspend fun upsertCollection(collection: ShelfCollection)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteCollection(id: String)

    @Query("SELECT * FROM placements")
    fun observePlacements(): Flow<List<Placement>>

    @Query("SELECT * FROM placements")
    suspend fun getPlacements(): List<Placement>

    @Query("SELECT * FROM placements WHERE packKey = :packKey")
    suspend fun placement(packKey: String): Placement?

    @Query("SELECT * FROM placements WHERE collectionId = :collectionId")
    suspend fun placementsIn(collectionId: String): List<Placement>

    @Upsert
    suspend fun upsertPlacement(placement: Placement)

    @Query("DELETE FROM placements WHERE packKey = :packKey")
    suspend fun deletePlacement(packKey: String)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM reading_tabs WHERE onRail = 1 ORDER BY openedAt DESC, id DESC LIMIT :limit")
    fun observeRailTabs(limit: Int): Flow<List<ReadingTab>>

    /** The most recently used tab still on the rail; a closed tab is never restored. */
    @Query("SELECT * FROM reading_tabs WHERE onRail = 1 ORDER BY openedAt DESC, id DESC LIMIT 1")
    suspend fun latestTab(): ReadingTab?

    @Query("SELECT * FROM reading_tabs WHERE onRail = 1 AND packId = :packId AND path = :path ORDER BY openedAt DESC, id DESC LIMIT 1")
    suspend fun railTabShowing(packId: String, path: String): ReadingTab?

    @Query("SELECT * FROM reading_tabs WHERE id = :id")
    suspend fun tab(id: Long): ReadingTab?

    @Insert
    suspend fun insertTab(tab: ReadingTab): Long

    @Update
    suspend fun updateTab(tab: ReadingTab)

    @Query("DELETE FROM reading_tabs WHERE id = :id")
    suspend fun deleteTab(id: Long)

    @Query("UPDATE reading_tabs SET packId = :to WHERE packId = :from")
    suspend fun moveTabsToPack(from: String, to: String)

    @Query("DELETE FROM reading_tabs WHERE packId = :packId")
    suspend fun deleteTabsForPack(packId: String)

    @Upsert
    suspend fun upsertHistory(entry: HistoryEntry)

    @Query("SELECT * FROM history ORDER BY openedAt DESC")
    fun observeHistory(): Flow<List<HistoryEntry>>

    /** Moves history to another pack; an article already recorded there wins. */
    @Query("UPDATE OR IGNORE history SET packId = :to WHERE packId = :from")
    suspend fun moveHistoryToPack(from: String, to: String)

    @Query("DELETE FROM history WHERE packId = :packId")
    suspend fun deleteHistoryForPack(packId: String)

    @Upsert
    suspend fun upsertRecentSearch(search: RecentSearch)

    @Query("SELECT * FROM recent_searches ORDER BY searchedAt DESC, query LIMIT :limit")
    fun observeRecentSearches(limit: Int): Flow<List<RecentSearch>>

    /** Keeps only the [limit] newest searches. */
    @Query(
        "DELETE FROM recent_searches WHERE query NOT IN " +
            "(SELECT query FROM recent_searches ORDER BY searchedAt DESC, query LIMIT :limit)",
    )
    suspend fun trimRecentSearches(limit: Int)
}

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<Book>>

    @Query("SELECT * FROM books")
    suspend fun getAll(): List<Book>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): Book?

    @Upsert
    suspend fun upsert(book: Book)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE books SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: PackStatus)

    @Query("SELECT * FROM books WHERE status = 'OK' AND indexedAt IS NULL")
    suspend fun notIndexed(): List<Book>

    @Query("UPDATE books SET indexedAt = :at WHERE id = :id")
    suspend fun setIndexed(id: String, at: Long)

    @Query("UPDATE books SET indexedAt = NULL")
    suspend fun clearIndexed()

    @Upsert
    suspend fun upsertPosition(position: BookPosition)

    @Query("SELECT * FROM book_positions WHERE bookId = :bookId")
    suspend fun position(bookId: String): BookPosition?

    @Query("SELECT * FROM book_positions")
    fun observePositions(): Flow<List<BookPosition>>

    @Query("SELECT * FROM book_marks WHERE bookId = :bookId ORDER BY createdAt, id")
    fun observeMarks(bookId: String): Flow<List<BookAnnotation>>

    @Insert
    suspend fun insertMark(mark: BookAnnotation): Long

    @Update
    suspend fun updateMark(mark: BookAnnotation)

    @Delete
    suspend fun deleteMark(mark: BookAnnotation)

    @Query("DELETE FROM book_marks WHERE id = :id")
    suspend fun deleteMarkById(id: Long)

    @Insert
    suspend fun insertChunk(chunk: BookChunk): Long

    @Query("DELETE FROM book_chunks WHERE bookId = :bookId")
    suspend fun deleteChunks(bookId: String)

    @Query("SELECT COUNT(*) FROM book_chunks")
    suspend fun chunkCount(): Int

    @Query("SELECT * FROM book_chunks WHERE id = :id")
    suspend fun chunk(id: Long): BookChunk?

    /** For queries over `book_text`, which Room doesn't know about; they must select `book_chunks` columns. */
    @RawQuery
    suspend fun chunksMatching(query: SupportSQLiteQuery): List<BookChunk>
}

@Dao
interface ResearchDao {
    @Query("SELECT * FROM research_chats ORDER BY updatedAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ResearchChat>>

    @Query("SELECT * FROM research_chats WHERE id = :id")
    suspend fun chat(id: Long): ResearchChat?

    @Insert
    suspend fun insertChat(chat: ResearchChat): Long

    @Query("UPDATE research_chats SET updatedAt = :at WHERE id = :id")
    suspend fun touchChat(id: Long, at: Long)

    @Query("SELECT * FROM research_turns WHERE chatId = :chatId ORDER BY createdAt, id")
    fun observeTurns(chatId: Long): Flow<List<ResearchTurn>>

    @Query("SELECT * FROM research_passages WHERE turnId IN (SELECT id FROM research_turns WHERE chatId = :chatId) ORDER BY turnId, ordinal")
    fun observePassages(chatId: Long): Flow<List<ResearchPassage>>

    @Query("SELECT * FROM research_turns WHERE id = :id")
    suspend fun turn(id: Long): ResearchTurn?

    @Insert
    suspend fun insertTurn(turn: ResearchTurn): Long

    @Update
    suspend fun updateTurn(turn: ResearchTurn)

    @Insert
    suspend fun insertPassages(passages: List<ResearchPassage>)

    @Query("DELETE FROM research_passages WHERE turnId = :turnId")
    suspend fun deletePassages(turnId: Long)

    @Query("UPDATE research_turns SET outcome = 'INTERRUPTED' WHERE outcome = 'PENDING'")
    suspend fun interruptPending(): Int

    @Query("DELETE FROM research_passages WHERE turnId IN (SELECT id FROM research_turns WHERE chatId = :chatId)")
    suspend fun deletePassagesForChat(chatId: Long)

    @Query("DELETE FROM research_turns WHERE chatId = :chatId")
    suspend fun deleteTurnsForChat(chatId: Long)

    @Query("DELETE FROM research_chats WHERE id = :id")
    suspend fun deleteChat(id: Long)
}
