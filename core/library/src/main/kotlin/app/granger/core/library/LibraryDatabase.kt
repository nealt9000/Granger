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
        Book::class, BookPosition::class, BookMark::class, BookChunk::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun packDao(): PackDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun readingPositionDao(): ReadingPositionDao
    abstract fun shelfDao(): ShelfDao
    abstract fun sessionDao(): SessionDao
    abstract fun bookDao(): BookDao

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

        /** Adds books, positions, marks and text chunks, and the `book_text` search index. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `books` (`id` TEXT NOT NULL, `origin` TEXT NOT NULL, `sourceRef` TEXT NOT NULL, `filePath` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT NOT NULL, `translator` TEXT NOT NULL, `date` TEXT NOT NULL, `language` TEXT NOT NULL, `subjects` TEXT NOT NULL, `description` TEXT NOT NULL, `coverPath` TEXT, `sizeBytes` INTEGER NOT NULL, `status` TEXT NOT NULL, `indexedAt` INTEGER, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `book_positions` (`bookId` TEXT NOT NULL, `locatorJson` TEXT NOT NULL, `totalProgression` REAL NOT NULL, `chapterTitle` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`bookId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `book_marks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` TEXT NOT NULL, `kind` TEXT NOT NULL, `locatorJson` TEXT NOT NULL, `text` TEXT NOT NULL, `color` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_marks_bookId` ON `book_marks` (`bookId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `book_chunks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` TEXT NOT NULL, `href` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `progression` REAL NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_chunks_bookId` ON `book_chunks` (`bookId`)")
                createBookText(db)
            }
        }

        /**
         * Seeds the built-in media types and creates `book_text` on a fresh install. Every database builder
         * must add this: Room doesn't manage `book_text`, so only this callback and [MIGRATION_3_4] create it.
         */
        val SeedCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                seedBuiltIns(db)
                createBookText(db)
            }
        }

        /** Contentless FTS4 index of book text; a row's `docid` is its `BookChunk.id`. Room doesn't manage it. */
        private fun createBookText(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS book_text USING fts4(content=\"\", text, tokenize=unicode61)")
        }

        private fun seedBuiltIns(db: SupportSQLiteDatabase) {
            listOf("literature" to "Literature", "audiobooks" to "Audiobooks", "music" to "Music", "video" to "Video")
                .forEachIndexed { index, (id, name) ->
                    db.execSQL("INSERT OR IGNORE INTO media_types (id, name, builtIn, sortOrder) VALUES ('$id', '$name', 1, $index)")
                }
        }

        fun create(context: Context): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                .addCallback(SeedCallback)
                .build()
    }
}
