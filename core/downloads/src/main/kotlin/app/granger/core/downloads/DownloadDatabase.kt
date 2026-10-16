package app.granger.core.downloads

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt")
    fun observeAll(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM downloads")
    suspend fun getAll(): List<DownloadRecord>

    @Query("SELECT * FROM downloads WHERE itemId = :itemId")
    suspend fun forItem(itemId: String): List<DownloadRecord>

    @Query("SELECT * FROM downloads WHERE itemId = :itemId AND sha256 = :sha256")
    suspend fun get(itemId: String, sha256: String): DownloadRecord?

    @Upsert
    suspend fun upsert(record: DownloadRecord)

    @Query("DELETE FROM downloads WHERE itemId = :itemId AND sha256 = :sha256")
    suspend fun delete(itemId: String, sha256: String)

    /** Updates in place and returns the rows changed; 0 means the record was cancelled or removed. */
    @Query(
        "UPDATE downloads SET state = :state, error = :error, bytesDone = :bytesDone, hashFailures = :hashFailures, " +
            "updatedAt = :now WHERE itemId = :itemId AND sha256 = :sha256",
    )
    suspend fun setState(
        itemId: String, sha256: String, state: DownloadState, error: String?, bytesDone: Long, hashFailures: Int, now: Long,
    ): Int

    @Query(
        "UPDATE downloads SET state = 'INSTALLED', bytesDone = sizeBytes, error = NULL, hashFailures = 0, " +
            "verifiedAt = :now, updatedAt = :now WHERE itemId = :itemId AND sha256 = :sha256",
    )
    suspend fun markInstalled(itemId: String, sha256: String, now: Long): Int

    /** Blocking: called from the download thread's progress callback. */
    @Query(
        "UPDATE downloads SET bytesDone = :bytesDone, updatedAt = :now " +
            "WHERE itemId = :itemId AND sha256 = :sha256 AND state = 'RUNNING'",
    )
    fun setProgress(itemId: String, sha256: String, bytesDone: Long, now: Long)

    @Query(
        "UPDATE downloads SET state = 'QUEUED', updatedAt = :now " +
            "WHERE itemId = :itemId AND sha256 = :sha256 AND state = 'RUNNING'",
    )
    suspend fun requeueIfRunning(itemId: String, sha256: String, now: Long)

    @Query("UPDATE downloads SET verifiedAt = :verifiedAt WHERE itemId = :itemId AND sha256 = :sha256")
    suspend fun setVerifiedAt(itemId: String, sha256: String, verifiedAt: Long?)

    @Query("UPDATE downloads SET wifiOnly = :wifiOnly WHERE state != 'INSTALLED'")
    suspend fun setWifiOnlyForActive(wifiOnly: Boolean)
}

class Converters {
    @TypeConverter fun fromUrls(urls: List<String>): String = urls.joinToString("\n")
    @TypeConverter fun toUrls(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split("\n")
}

@Database(entities = [DownloadRecord::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        fun create(context: Context): DownloadDatabase =
            Room.databaseBuilder(context, DownloadDatabase::class.java, "downloads.db").build()
    }
}
