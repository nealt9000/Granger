package app.granger.core.downloads

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.RenameColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.room.migration.AutoMigrationSpec
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt")
    fun observeAll(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM downloads")
    suspend fun getAll(): List<DownloadRecord>

    @Query("SELECT * FROM downloads WHERE itemId = :itemId")
    suspend fun forItem(itemId: String): List<DownloadRecord>

    @Query("SELECT * FROM downloads WHERE itemId = :itemId AND hash = :hash")
    suspend fun get(itemId: String, hash: String): DownloadRecord?

    @Upsert
    suspend fun upsert(record: DownloadRecord)

    @Query("DELETE FROM downloads WHERE itemId = :itemId AND hash = :hash")
    suspend fun delete(itemId: String, hash: String)

    /** Updates in place and returns the rows changed; 0 means the record was cancelled or removed. */
    @Query(
        "UPDATE downloads SET state = :state, error = :error, bytesDone = :bytesDone, hashFailures = :hashFailures, " +
            "updatedAt = :now WHERE itemId = :itemId AND hash = :hash",
    )
    suspend fun setState(
        itemId: String, hash: String, state: DownloadState, error: String?, bytesDone: Long, hashFailures: Int, now: Long,
    ): Int

    /** Like [setState] but never touches an INSTALLED row (e.g. one a cancelled repair restored). */
    @Query(
        "UPDATE downloads SET state = :state, error = :error, bytesDone = :bytesDone, hashFailures = :hashFailures, " +
            "updatedAt = :now WHERE itemId = :itemId AND hash = :hash AND state != 'INSTALLED'",
    )
    suspend fun setActiveState(
        itemId: String, hash: String, state: DownloadState, error: String?, bytesDone: Long, hashFailures: Int, now: Long,
    ): Int

    @Query(
        "UPDATE downloads SET state = 'INSTALLED', bytesDone = sizeBytes, error = NULL, hashFailures = 0, " +
            "verifiedAt = :now, updatedAt = :now WHERE itemId = :itemId AND hash = :hash",
    )
    suspend fun markInstalled(itemId: String, hash: String, now: Long): Int

    /** Blocking: called from the download thread's progress callback. */
    @Query(
        "UPDATE downloads SET bytesDone = :bytesDone, updatedAt = :now " +
            "WHERE itemId = :itemId AND hash = :hash AND state = 'RUNNING'",
    )
    fun setProgress(itemId: String, hash: String, bytesDone: Long, now: Long)

    @Query(
        "UPDATE downloads SET state = 'QUEUED', updatedAt = :now " +
            "WHERE itemId = :itemId AND hash = :hash AND state = 'RUNNING'",
    )
    suspend fun requeueIfRunning(itemId: String, hash: String, now: Long)

    @Query("UPDATE downloads SET verifiedAt = :verifiedAt WHERE itemId = :itemId AND hash = :hash")
    suspend fun setVerifiedAt(itemId: String, hash: String, verifiedAt: Long?)

    @Query("UPDATE downloads SET wifiOnly = :wifiOnly WHERE state != 'INSTALLED'")
    suspend fun setWifiOnlyForActive(wifiOnly: Boolean)
}

class Converters {
    @TypeConverter fun fromUrls(urls: List<String>): String = urls.joinToString("\n")
    @TypeConverter fun toUrls(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split("\n")
}

@Database(
    entities = [DownloadRecord::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2, spec = DownloadDatabase.Rename1To2::class)],
)
@TypeConverters(Converters::class)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    @RenameColumn(tableName = "downloads", fromColumnName = "sha256", toColumnName = "hash")
    class Rename1To2 : AutoMigrationSpec

    companion object {
        fun create(context: Context): DownloadDatabase =
            Room.databaseBuilder(context, DownloadDatabase::class.java, "downloads.db").build()
    }
}
