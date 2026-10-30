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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    /** Every record in queue order (Plan DL Decision 2). */
    @Query("SELECT * FROM downloads ORDER BY $QUEUE_ORDER")
    fun observeAll(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM downloads ORDER BY $QUEUE_ORDER")
    suspend fun getAll(): List<DownloadRecord>

    /** The records still waiting or running, in queue order: what the queue works through. */
    @Query("SELECT * FROM downloads WHERE state IN ('QUEUED', 'RUNNING') ORDER BY $QUEUE_ORDER")
    suspend fun waiting(): List<DownloadRecord>

    @Query("SELECT MAX(queueOrder) FROM downloads")
    suspend fun maxQueueOrder(): Long?

    @Query("UPDATE downloads SET queueOrder = :queueOrder WHERE itemId = :itemId AND hash = :hash")
    suspend fun setQueueOrder(itemId: String, hash: String, queueOrder: Long)

    /** A try failed for now: the record waits until [retryAt], keeping its part file and the error. */
    @Query(
        "UPDATE downloads SET attempts = :attempts, retryAt = :retryAt, updatedAt = :now " +
            "WHERE itemId = :itemId AND hash = :hash AND state = 'QUEUED'",
    )
    suspend fun setRetry(itemId: String, hash: String, attempts: Int, retryAt: Long?, now: Long): Int

    @Query("SELECT * FROM downloads WHERE itemId = :itemId")
    suspend fun forItem(itemId: String): List<DownloadRecord>

    @Query("SELECT * FROM downloads WHERE itemId = :itemId AND hash = :hash")
    suspend fun get(itemId: String, hash: String): DownloadRecord?

    @Query("SELECT * FROM downloads WHERE groupId = :groupId ORDER BY $QUEUE_ORDER")
    suspend fun forGroup(groupId: String): List<DownloadRecord>

    @Upsert
    suspend fun upsert(record: DownloadRecord)

    /** Many records in one transaction (an onboarding group can be hundreds). */
    @Upsert
    suspend fun upsertAll(records: List<DownloadRecord>)

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

/** The queue's order: its place, then the older record, then the id (Plan DL Decision 2). */
private const val QUEUE_ORDER = "queueOrder, createdAt, itemId, hash"

class Converters {
    @TypeConverter fun fromUrls(urls: List<String>): String = urls.joinToString("\n")
    @TypeConverter fun toUrls(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split("\n")
}

@Database(
    entities = [DownloadRecord::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = DownloadDatabase.Rename1To2::class),
        // Adds the nullable groupId column only; every existing record keeps its row and gets no group.
        AutoMigration(from = 2, to = 3),
    ],
)
@TypeConverters(Converters::class)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    @RenameColumn(tableName = "downloads", fromColumnName = "sha256", toColumnName = "hash")
    class Rename1To2 : AutoMigrationSpec

    companion object {
        fun create(context: Context): DownloadDatabase =
            Room.databaseBuilder(context, DownloadDatabase::class.java, "downloads.db").addMigrations(MIGRATION_3_4).build()

        /**
         * Plan DL Decision 2: the queue's stored order, the tries so far and when to try again. Every record gets its
         * place from when it was queued, then its id (a group's files share one createdAt, so their list order wasn't
         * stored); nothing is retrying yet.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN queueOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN retryAt INTEGER")
                db.execSQL(
                    "UPDATE downloads SET queueOrder = (SELECT COUNT(*) FROM downloads d WHERE d.createdAt < downloads.createdAt " +
                        "OR (d.createdAt = downloads.createdAt AND (d.itemId < downloads.itemId " +
                        "OR (d.itemId = downloads.itemId AND d.hash < downloads.hash))))",
                )
            }
        }
    }
}
