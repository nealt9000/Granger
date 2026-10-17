package app.granger.core.library

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Pack::class, Bookmark::class, ReadingPosition::class,
        MediaType::class, ShelfCollection::class, Placement::class,
        ReadingTab::class, HistoryEntry::class, RecentSearch::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun packDao(): PackDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun readingPositionDao(): ReadingPositionDao
    abstract fun shelfDao(): ShelfDao
    abstract fun sessionDao(): SessionDao

    companion object {
        /** Adds media types, collections, placements, reading tabs, history and recent searches. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `media_types` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `builtIn` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `collections` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `mediaTypeId` TEXT NOT NULL, `userCreated` INTEGER NOT NULL, `defaultKey` TEXT, PRIMARY KEY(`id`))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_collections_defaultKey` ON `collections` (`defaultKey`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `placements` (`packKey` TEXT NOT NULL, `collectionId` TEXT NOT NULL, `userSet` INTEGER NOT NULL, PRIMARY KEY(`packKey`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `reading_tabs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packId` TEXT NOT NULL, `path` TEXT NOT NULL, `title` TEXT NOT NULL, `openedAt` INTEGER NOT NULL, `onRail` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `history` (`packId` TEXT NOT NULL, `path` TEXT NOT NULL, `title` TEXT NOT NULL, `openedAt` INTEGER NOT NULL, PRIMARY KEY(`packId`, `path`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_openedAt` ON `history` (`openedAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `recent_searches` (`query` TEXT NOT NULL, `searchedAt` INTEGER NOT NULL, PRIMARY KEY(`query`))")
                seedBuiltIns(db)
            }
        }

        /** Seeds the built-in media types on a fresh install. */
        val SeedCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = seedBuiltIns(db)
        }

        private fun seedBuiltIns(db: SupportSQLiteDatabase) {
            listOf("literature" to "Literature", "audiobooks" to "Audiobooks", "music" to "Music", "video" to "Video")
                .forEachIndexed { index, (id, name) ->
                    db.execSQL("INSERT OR IGNORE INTO media_types (id, name, builtIn, sortOrder) VALUES ('$id', '$name', 1, $index)")
                }
        }

        fun create(context: Context): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
                .addMigrations(MIGRATION_2_3)
                .addCallback(SeedCallback)
                .build()
    }
}
