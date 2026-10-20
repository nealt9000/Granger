package app.granger.core.library

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LibraryDatabase::class.java)

    @Test fun version1PacksGetABlankNameAndKeepTheirRows() {
        helper.createDatabase("migrate.db", 1).apply {
            execSQL(
                "INSERT INTO packs (id, filePath, title, description, language, date, sizeBytes, hasFulltextIndex, status, addedAt) " +
                    "VALUES ('u-a', '/p/a.zim', 'A', '', 'eng', '2026-10-15', 10, 1, 'OK', 5)",
            )
            execSQL("INSERT INTO bookmarks (packId, path, title, createdAt) VALUES ('u-a', 'index', 'Home', 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate("migrate.db", 3, true, LibraryDatabase.MIGRATION_2_3)
        db.query("SELECT id, name FROM packs").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("u-a")
            assertThat(it.getString(1)).isEqualTo("")
        }
        db.query("SELECT COUNT(*) FROM bookmarks").use {
            it.moveToFirst()
            assertThat(it.getInt(0)).isEqualTo(1)
        }
    }

    @Test fun version2DataSurvivesAndBuiltInsAreSeeded() {
        helper.createDatabase("migrate3.db", 2).apply {
            execSQL(
                "INSERT INTO packs (id, filePath, title, description, language, date, sizeBytes, hasFulltextIndex, status, addedAt, name) " +
                    "VALUES ('u-a', '/p/a.zim', 'A', '', 'eng', '2026-10-15', 10, 1, 'OK', 5, 'a')",
            )
            execSQL("INSERT INTO bookmarks (packId, path, title, createdAt) VALUES ('u-a', 'index', 'Home', 1)")
            execSQL("INSERT INTO reading_positions (packId, path, scrollFraction, updatedAt) VALUES ('u-a', 'index', 0.5, 2)")
            close()
        }
        val db = helper.runMigrationsAndValidate("migrate3.db", 3, true, LibraryDatabase.MIGRATION_2_3)
        for (table in listOf("packs", "bookmarks", "reading_positions")) {
            db.query("SELECT COUNT(*) FROM $table").use {
                it.moveToFirst()
                assertThat(it.getInt(0)).isEqualTo(1)
            }
        }
        val ids = mutableListOf<String>()
        db.query("SELECT id FROM media_types ORDER BY sortOrder").use { while (it.moveToNext()) ids += it.getString(0) }
        assertThat(ids).containsExactly("literature", "audiobooks", "music", "video").inOrder()
    }

    @Test fun version3DataSurvivesAndBookTablesExist() {
        helper.createDatabase("migrate4.db", 3).apply {
            execSQL(
                "INSERT INTO packs (id, filePath, title, description, language, date, sizeBytes, hasFulltextIndex, status, addedAt, name) " +
                    "VALUES ('u-a', '/p/a.zim', 'A', '', 'eng', '2026-10-15', 10, 1, 'OK', 5, 'a')",
            )
            execSQL("INSERT INTO placements (packKey, collectionId, userSet) VALUES ('a', 'c', 0)")
            execSQL("INSERT INTO reading_tabs (packId, path, title, openedAt, onRail) VALUES ('u-a', 'index', 'Home', 1, 1)")
            execSQL("INSERT INTO history (packId, path, title, openedAt) VALUES ('u-a', 'index', 'Home', 1)")
            close()
        }
        // validateDroppedTables is off: book_text is raw FTS4 that Room doesn't manage, so the helper calls it unexpected.
        // Room still validates every table it does manage.
        val db = helper.runMigrationsAndValidate("migrate4.db", 4, false, LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4)
        for (table in listOf("packs", "placements", "reading_tabs", "history")) {
            db.query("SELECT COUNT(*) FROM $table").use {
                it.moveToFirst()
                assertThat(it.getInt(0)).isEqualTo(1)
            }
        }
        for (table in listOf("books", "book_positions", "book_marks", "book_chunks")) {
            db.query("SELECT COUNT(*) FROM $table").use {
                it.moveToFirst()
                assertThat(it.getInt(0)).isEqualTo(0)
            }
        }
        db.execSQL("INSERT INTO book_text (docid, text) VALUES (7, 'the quick brown fox')")
        db.query("SELECT docid FROM book_text WHERE book_text MATCH 'quick'").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getLong(0)).isEqualTo(7L)
        }
    }

    @Test fun migrate4To5KeepsBooksTabsAndAddsChats() {
        helper.createDatabase("migrate5.db", 4).apply {
            execSQL(
                "INSERT INTO books (id, origin, sourceRef, filePath, title, author, translator, date, language, subjects, description, coverPath, sizeBytes, status, indexedAt, addedAt) " +
                    "VALUES ('b', 'IMPORT', 'r', '/b.epub', 'B', 'A', '', '', 'eng', '', '', NULL, 10, 'OK', NULL, 1)",
            )
            execSQL("INSERT INTO reading_tabs (packId, path, title, openedAt, onRail) VALUES ('u-a', 'index', 'Home', 1, 1)")
            execSQL("INSERT INTO recent_searches (query, searchedAt) VALUES ('cats', 3)")
            close()
        }
        val db = helper.runMigrationsAndValidate("migrate5.db", 5, false, LibraryDatabase.MIGRATION_4_5)
        for (table in listOf("books", "reading_tabs", "recent_searches")) {
            db.query("SELECT COUNT(*) FROM $table").use {
                it.moveToFirst()
                assertThat(it.getInt(0)).isEqualTo(1)
            }
        }
        for (table in listOf("research_chats", "research_turns", "research_passages")) {
            db.query("SELECT COUNT(*) FROM $table").use {
                it.moveToFirst()
                assertThat(it.getInt(0)).isEqualTo(0)
            }
        }
    }

    @Test fun migration5To6KeepsChatsAndDefaultsGuessToEmpty() {
        helper.createDatabase("migrate6.db", 5).apply {
            execSQL("INSERT INTO research_chats (id, title, createdAt, updatedAt) VALUES (1, 'Chat', 1, 2)")
            execSQL(
                "INSERT INTO research_turns (id, chatId, question, outcome, answer, medical, usedLarge, askedDeeper, keywords, createdAt) " +
                    "VALUES (1, 1, 'Why?', 'DECLINED', '', 0, 0, 0, '', 3)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate("migrate6.db", 6, true, LibraryDatabase.MIGRATION_5_6)
        db.query("SELECT question, outcome, guess, guessLarge, guessFellBack FROM research_turns").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("Why?")
            assertThat(it.getString(1)).isEqualTo("DECLINED")
            assertThat(it.getString(2)).isEqualTo("")
            assertThat(it.getInt(3)).isEqualTo(0)
            assertThat(it.getInt(4)).isEqualTo(0)
        }
        db.query("SELECT COUNT(*) FROM research_chats").use {
            it.moveToFirst()
            assertThat(it.getInt(0)).isEqualTo(1)
        }
    }

    @Test fun migrate6To7KeepsDataAndCreatesMediaTables() {
        helper.createDatabase("migrate7.db", 6).apply {
            execSQL(
                "INSERT INTO packs (id, filePath, title, description, language, date, sizeBytes, hasFulltextIndex, status, addedAt, name) " +
                    "VALUES ('u-a', '/p/a.zim', 'A', '', 'eng', '2026-10-15', 10, 1, 'OK', 5, 'a')",
            )
            execSQL("INSERT INTO research_chats (id, title, createdAt, updatedAt) VALUES (1, 'Chat', 1, 2)")
            execSQL(
                "INSERT INTO research_turns (id, chatId, question, outcome, answer, medical, usedLarge, askedDeeper, keywords, createdAt, guess, guessLarge, guessFellBack) " +
                    "VALUES (1, 1, 'Why?', 'ANSWERED', 'Because.', 0, 1, 0, '', 3, '', 0, 0)",
            )
            // As on a real install: book_text exists beside the Room tables and must come through untouched.
            LibraryDatabase.createBookText(this)
            execSQL("INSERT INTO book_text (docid, text) VALUES (7, 'the quick brown fox')")
            close()
        }
        // validateDroppedTables is off for book_text, as in version3DataSurvivesAndBookTablesExist.
        val db = helper.runMigrationsAndValidate("migrate7.db", 7, false, LibraryDatabase.MIGRATION_6_7)
        for (table in listOf("packs", "research_chats", "research_turns")) {
            db.query("SELECT COUNT(*) FROM $table").use {
                it.moveToFirst()
                assertThat(it.getInt(0)).isEqualTo(1)
            }
        }
        db.query("SELECT answer, usedLarge FROM research_turns").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("Because.")
            assertThat(it.getInt(1)).isEqualTo(1)
        }
        val mediaTables = listOf(
            "tracks", "audiobooks", "audiobook_chapters", "audiobook_states", "audio_bookmarks", "playlists",
            "playlist_entries", "music_state", "recent_plays", "media_folders", "scan_cache",
        )
        for (table in mediaTables) {
            db.query("SELECT COUNT(*) FROM $table").use {
                it.moveToFirst()
                assertThat(it.getInt(0)).isEqualTo(0)
            }
        }
        db.query("SELECT docid FROM book_text WHERE book_text MATCH 'quick'").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getLong(0)).isEqualTo(7L)
        }
    }
    @Test fun migrate7To8KeepsDataAndCreatesAlbumNotes() {
        helper.createDatabase("migrate8.db", 7).apply {
            execSQL(
                "INSERT INTO packs (id, filePath, title, description, language, date, sizeBytes, hasFulltextIndex, status, addedAt, name) " +
                    "VALUES ('u-a', '/p/a.zim', 'A', '', 'eng', '2026-10-15', 10, 1, 'OK', 5, 'a')",
            )
            execSQL(
                "INSERT INTO tracks (id, origin, uri, folder, title, album, albumKey, albumArtists, artists, composers, genres, disc, trackNo, " +
                    "date, originalDate, releaseType, sortTitle, sortAlbum, sortArtist, sortAlbumArtist, durationMs, rgTrackGain, rgTrackPeak, " +
                    "rgAlbumGain, rgAlbumPeak, coverPath, audiobookId, status, addedAt) VALUES ('track:archive_org:x', 'ARCHIVE_ORG', 'file:///x.mp3', " +
                    "'music/archive-org/x/', 'Mars', 'The Planets', '|item|archive-org:x', 'Gustav Holst', 'Gustav Holst', '', '', NULL, 1, '', '', '', " +
                    "'', '', '', '', 1000, NULL, NULL, NULL, NULL, NULL, NULL, 'OK', 9)",
            )
            execSQL("INSERT INTO audiobook_states (audiobookId, trackId, positionMs, speed, skipSilence, volumeBoostMb, finished, updatedAt) VALUES ('b', 't', 42, 1.0, 0, 0, 0, 1)")
            LibraryDatabase.createBookText(this)
            execSQL("INSERT INTO book_text (docid, text) VALUES (7, 'the quick brown fox')")
            close()
        }
        val db = helper.runMigrationsAndValidate("migrate8.db", 8, false, LibraryDatabase.MIGRATION_7_8)
        db.query("SELECT title, albumKey FROM tracks").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("Mars")
            assertThat(it.getString(1)).isEqualTo("|item|archive-org:x")
        }
        db.query("SELECT positionMs FROM audiobook_states").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getLong(0)).isEqualTo(42L)
        }
        db.query("SELECT COUNT(*) FROM packs").use {
            it.moveToFirst()
            assertThat(it.getInt(0)).isEqualTo(1)
        }
        db.execSQL("INSERT INTO album_notes (albumKey, description) VALUES ('|item|archive-org:x', 'LSO')")
        db.query("SELECT description FROM album_notes").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("LSO")
        }
        db.query("SELECT docid FROM book_text WHERE book_text MATCH 'quick'").use {
            assertThat(it.moveToFirst()).isTrue()
        }
    }

    @Test fun migration8To9KeepsTabsAndAddsEmptyStacks() {
        helper.createDatabase("migrate9.db", 8).apply {
            execSQL("INSERT INTO reading_tabs (id, packId, path, title, openedAt, onRail) VALUES (1, 'u-a', 'Cat', 'Cat', 5, 1)")
            execSQL("INSERT INTO reading_tabs (id, packId, path, title, openedAt, onRail) VALUES (2, 'u-a', 'Dog', 'Dog', 7, 1)")
            execSQL("INSERT INTO reading_tabs (id, packId, path, title, openedAt, onRail) VALUES (3, 'u-a', 'Eel', 'Eel', 9, 0)")
            execSQL("INSERT INTO recent_searches (query, searchedAt) VALUES ('cats', 3)")
            LibraryDatabase.createBookText(this)
            execSQL("INSERT INTO book_text (docid, text) VALUES (7, 'the quick brown fox')")
            close()
        }
        val db = helper.runMigrationsAndValidate("migrate9.db", 9, false, LibraryDatabase.MIGRATION_8_9)
        val tabs = mutableListOf<List<Any>>()
        db.query("SELECT id, path, openedAt, onRail, backStack FROM reading_tabs ORDER BY openedAt DESC").use {
            while (it.moveToNext()) tabs += listOf(it.getLong(0), it.getString(1), it.getLong(2), it.getInt(3), it.getString(4))
        }
        // Same rows, same order, each with an empty stack (the first page shown starts it).
        assertThat(tabs).containsExactly(
            listOf(3L, "Eel", 9L, 0, ""),
            listOf(2L, "Dog", 7L, 1, ""),
            listOf(1L, "Cat", 5L, 1, ""),
        ).inOrder()
        db.query("SELECT COUNT(*) FROM recent_searches").use {
            it.moveToFirst()
            assertThat(it.getInt(0)).isEqualTo(1)
        }
        db.query("SELECT COUNT(*) FROM rail_pins").use {
            it.moveToFirst()
            assertThat(it.getInt(0)).isEqualTo(0)
        }
        db.execSQL("INSERT INTO rail_pins (`key`, pinnedAt) VALUES ('tab:1', 11)")
        db.query("SELECT `key` FROM rail_pins").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("tab:1")
        }
        db.query("SELECT docid FROM book_text WHERE book_text MATCH 'quick'").use {
            assertThat(it.moveToFirst()).isTrue()
        }
    }
}
