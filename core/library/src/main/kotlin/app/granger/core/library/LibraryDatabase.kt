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
        Book::class, BookPosition::class, BookAnnotation::class, BookChunk::class,
        ResearchChat::class, ResearchTurn::class, ResearchPassage::class,
        Track::class, Audiobook::class, AudiobookChapter::class, AudiobookState::class, AudioBookmark::class,
        Playlist::class, PlaylistEntry::class, MusicState::class, RecentPlay::class, MediaFolder::class, ScanCacheEntry::class,
        AlbumNote::class, RailPin::class,
    ],
    version = 10,
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
    abstract fun researchDao(): ResearchDao
    abstract fun mediaDao(): MediaDao
    abstract fun playbackDao(): PlaybackDao

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
                seedBuiltIns(db, BUILT_INS.take(4))
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

        /** Adds kept Research chats, their turns and cited passages. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `research_chats` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `research_turns` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chatId` INTEGER NOT NULL, `question` TEXT NOT NULL, `outcome` TEXT NOT NULL, `answer` TEXT NOT NULL, `medical` INTEGER NOT NULL, `usedLarge` INTEGER NOT NULL, `askedDeeper` INTEGER NOT NULL, `keywords` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_research_turns_chatId` ON `research_turns` (`chatId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `research_passages` (`turnId` INTEGER NOT NULL, `ordinal` INTEGER NOT NULL, `role` TEXT NOT NULL, `kind` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `sourceTitle` TEXT NOT NULL, `packTitle` TEXT NOT NULL, `location` TEXT NOT NULL, `anchor` TEXT, `progression` REAL NOT NULL, `heading` TEXT NOT NULL, `text` TEXT NOT NULL, `author` TEXT NOT NULL, `date` TEXT NOT NULL, `subjects` TEXT NOT NULL, `collection` TEXT NOT NULL, PRIMARY KEY(`turnId`, `ordinal`))")
            }
        }

        /** Adds the model's best guess to a turn: blank text, and whether the large model wrote it or fell back. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `research_turns` ADD COLUMN `guess` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `research_turns` ADD COLUMN `guessLarge` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `research_turns` ADD COLUMN `guessFellBack` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Adds tracks, audiobooks, chapters, playback state, audio bookmarks, playlists, recent plays, media folders
         * and the scan cache. Only creates tables: nothing existing is altered or dropped.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `tracks` (`id` TEXT NOT NULL, `origin` TEXT NOT NULL, `uri` TEXT NOT NULL, `folder` TEXT NOT NULL, `title` TEXT NOT NULL, `album` TEXT NOT NULL, `albumKey` TEXT NOT NULL, `albumArtists` TEXT NOT NULL, `artists` TEXT NOT NULL, `composers` TEXT NOT NULL, `genres` TEXT NOT NULL, `disc` INTEGER, `trackNo` INTEGER, `date` TEXT NOT NULL, `originalDate` TEXT NOT NULL, `releaseType` TEXT NOT NULL, `sortTitle` TEXT NOT NULL, `sortAlbum` TEXT NOT NULL, `sortArtist` TEXT NOT NULL, `sortAlbumArtist` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `rgTrackGain` REAL, `rgTrackPeak` REAL, `rgAlbumGain` REAL, `rgAlbumPeak` REAL, `coverPath` TEXT, `audiobookId` TEXT, `status` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_albumKey` ON `tracks` (`albumKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_audiobookId` ON `tracks` (`audiobookId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `audiobooks` (`id` TEXT NOT NULL, `origin` TEXT NOT NULL, `sourceRef` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT NOT NULL, `narrator` TEXT NOT NULL, `description` TEXT NOT NULL, `coverPath` TEXT, `durationMs` INTEGER NOT NULL, `status` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `audiobook_chapters` (`audiobookId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `title` TEXT NOT NULL, `trackId` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, PRIMARY KEY(`audiobookId`, `ordinal`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `audiobook_states` (`audiobookId` TEXT NOT NULL, `trackId` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `speed` REAL NOT NULL, `skipSilence` INTEGER NOT NULL, `volumeBoostMb` INTEGER NOT NULL, `finished` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`audiobookId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `audio_bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `audiobookId` TEXT NOT NULL, `trackId` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `title` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audio_bookmarks_audiobookId` ON `audio_bookmarks` (`audiobookId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_entries` (`playlistId` INTEGER NOT NULL, `ordinal` INTEGER NOT NULL, `trackId` TEXT NOT NULL, PRIMARY KEY(`playlistId`, `ordinal`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `music_state` (`id` INTEGER NOT NULL, `queue` TEXT NOT NULL, `index` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, `shuffle` INTEGER NOT NULL, `shuffledOrder` TEXT NOT NULL, `repeatMode` INTEGER NOT NULL, `sourceKey` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `recent_plays` (`key` TEXT NOT NULL, `title` TEXT NOT NULL, `playedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recent_plays_playedAt` ON `recent_plays` (`playedAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `media_folders` (`uri` TEXT NOT NULL, `audiobooks` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`uri`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `scan_cache` (`uri` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `modifiedMs` INTEGER NOT NULL, `fingerprint` TEXT NOT NULL, PRIMARY KEY(`uri`))")
            }
        }

        /** Adds album notes (a downloaded album's description). Only creates a table. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `album_notes` (`albumKey` TEXT NOT NULL, `description` TEXT NOT NULL, PRIMARY KEY(`albumKey`))")
            }
        }

        /**
         * Adds rail pins and a back stack per reading tab. Existing tabs keep their rows and order; their stacks start
         * empty and fill from the next page shown.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `rail_pins` (`key` TEXT NOT NULL, `pinnedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
                db.execSQL("ALTER TABLE `reading_tabs` ADD COLUMN `backStack` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Adds folders (`collections.parentId`) and the built-in Maps type. The app-made collections of Kiwix's
         * reading packs that are still in Literature go in the new Kiwix folder; the folder row is made only when one
         * does. The app-made maps collection moves to Maps as "OpenStreetMap" unless the user moved it out of
         * Literature. Nothing the user made or moved is touched, and no placement changes.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `collections` ADD COLUMN `parentId` TEXT")
                seedBuiltIns(db, BUILT_INS.drop(4))
                db.execSQL(
                    "INSERT OR IGNORE INTO collections (id, name, mediaTypeId, userCreated, defaultKey, parentId) " +
                        "SELECT 'c:${Filing.FOLDER_KEY}', '${Filing.FOLDER_NAME}', 'literature', 0, '${Filing.FOLDER_KEY}', NULL " +
                        "WHERE EXISTS (SELECT 1 FROM collections WHERE $IN_KIWIX_FOLDER)",
                )
                db.execSQL("UPDATE collections SET parentId = 'c:${Filing.FOLDER_KEY}' WHERE $IN_KIWIX_FOLDER")
                db.execSQL(
                    "UPDATE collections SET mediaTypeId = 'maps', name = 'OpenStreetMap' " +
                        "WHERE id = 'c:maps' AND userCreated = 0 AND mediaTypeId = 'literature'",
                )
            }
        }

        /**
         * The v9 collections that go in the Kiwix folder: app-made, in Literature, and filed for a Kiwix pack. That
         * leaves out the book collections, Other, Maps, the folder itself and the media ones (`music:on-this-phone`
         * and the like, should the user have moved one here); of the keys with a `:`, only `pack:` ones are packs'.
         */
        private val IN_KIWIX_FOLDER =
            "userCreated = 0 AND mediaTypeId = 'literature' AND defaultKey IS NOT NULL " +
                "AND defaultKey NOT IN ('granger-classics', 'my-books', 'archive-org', 'other', 'maps', '${Filing.FOLDER_KEY}') " +
                "AND (defaultKey LIKE 'pack:%' OR defaultKey NOT LIKE '%:%')"

        /**
         * Seeds the built-in media types and creates `book_text` on a fresh install. Every database builder
         * must add this: Room doesn't manage `book_text`, so only this callback and [MIGRATION_3_4] create it.
         */
        val SeedCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                seedBuiltIns(db, BUILT_INS)
                createBookText(db)
            }
        }

        /** Contentless FTS4 index of book text; a row's `docid` is its `BookChunk.id`. Room doesn't manage it. */
        internal fun createBookText(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS book_text USING fts4(content=\"\", text, tokenize=unicode61)")
        }

        /**
         * The built-in media types in sort order (each one's index). Version 3 brought the first four; [MIGRATION_9_10]
         * brought Maps.
         */
        private val BUILT_INS = listOf(
            "literature" to "Literature", "audiobooks" to "Audiobooks", "music" to "Music", "video" to "Video", "maps" to "Maps",
        )

        private fun seedBuiltIns(db: SupportSQLiteDatabase, types: List<Pair<String, String>>) {
            for ((id, name) in types) {
                val sortOrder = BUILT_INS.indexOf(id to name)
                db.execSQL("INSERT OR IGNORE INTO media_types (id, name, builtIn, sortOrder) VALUES ('$id', '$name', 1, $sortOrder)")
            }
        }

        fun create(context: Context): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
                .addMigrations(
                    MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                    MIGRATION_9_10,
                )
                .addCallback(SeedCallback)
                .build()
    }
}
