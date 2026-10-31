package app.granger.core.library

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
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

    private fun SupportSQLiteDatabase.insertPack(id: String, name: String, title: String, description: String) = execSQL(
        "INSERT INTO packs (id, filePath, title, description, language, date, sizeBytes, hasFulltextIndex, status, addedAt, name) " +
            "VALUES ('$id', '/p/$id.zim', '$title', '$description', 'eng', '2026-03-05', 10, 1, 'OK', 1, '$name')",
    )

    private fun titles(db: SupportSQLiteDatabase): Map<String, String> = db.query("SELECT id, title FROM packs").use {
        buildMap { while (it.moveToNext()) put(it.getString(0), it.getString(1)) }
    }

    /** Plan KB Decision 3: packs gain a nullable scraper, null for every row until the library reads the file again. */
    @Test fun migration14To15AddsANullScraperAndKeepsEveryPack() {
        helper.createDatabase("migrate15.db", 14).apply {
            insertPack("u-w", "zimgit-water_en", "Water", "Water treatment")
            insertPack("u-m", "gutenberg_en_lcc-m", "Music and books on music · Project Gutenberg", "Music")
            execSQL("INSERT INTO placements (packKey, collectionId, userSet) VALUES ('zimgit-water_en', 'c:pack:zimgit-water_en', 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate("migrate15.db", 15, true, LibraryDatabase.MIGRATION_14_15)
        db.query("SELECT id, title, scraper FROM packs ORDER BY id").use {
            val rows = buildList { while (it.moveToNext()) add(Triple(it.getString(0), it.getString(1), it.isNull(2))) }
            assertThat(rows).containsExactly(
                Triple("u-m", "Music and books on music · Project Gutenberg", true),
                Triple("u-w", "Water", true),
            ).inOrder()
        }
        db.query("SELECT COUNT(*) FROM placements").use { it.moveToFirst(); assertThat(it.getInt(0)).isEqualTo(1) }
        db.execSQL("UPDATE packs SET scraper = 'nautiluszim 1.1.1' WHERE id = 'u-w'")
        db.query("SELECT scraper FROM packs WHERE id = 'u-w'").use { it.moveToFirst(); assertThat(it.getString(0)).isEqualTo("nautiluszim 1.1.1") }
    }

    @Test fun migration13To14NamesGutenbergPacksForTheirSubject() {
        helper.createDatabase("migrate14.db", 13).apply {
            insertPack("u-m", "gutenberg_en_lcc-m", "Project Gutenberg Library", "music and books on music")
            insertPack("u-all", "gutenberg_en_all", "Project Gutenberg Library", "All books in English from the first producer")
            insertPack("u-blank", "gutenberg_en_lcc-x", "Project Gutenberg Library", " ")
            insertPack("u-wp", "wikipedia_en_top", "Wikipedia", "The best articles")
            // `_` is a LIKE wildcard: only a real `gutenberg_` prefix counts.
            insertPack("u-odd", "gutenbergXen", "Odd", "Something")
            close()
        }
        val db = helper.runMigrationsAndValidate("migrate14.db", 14, true, LibraryDatabase.MIGRATION_13_14)
        assertThat(titles(db)).containsExactly(
            "u-m", "Music and books on music · Project Gutenberg",
            "u-all", "Every English book · Project Gutenberg",
            "u-blank", "Project Gutenberg Library",
            "u-wp", "Wikipedia",
            "u-odd", "Odd",
        )
    }

    @Test fun migration13To14TakesGutenbergPacksOutOfTheKiwixFolderUnlessTheUserPutThemThere() {
        helper.createDatabase("migrate14b.db", 13).apply {
            insertPack("u-m", "gutenberg_en_lcc-m", "Project Gutenberg Library", "Music and books on music")
            execSQL("INSERT INTO collections (id, name, mediaTypeId, userCreated, defaultKey, parentId) VALUES ('c:kiwix', 'Kiwix', 'literature', 0, 'kiwix', NULL)")
            execSQL("INSERT INTO collections (id, name, mediaTypeId, userCreated, defaultKey, parentId) VALUES ('c:gutenberg', 'Gutenberg', 'literature', 0, 'gutenberg', 'c:kiwix')")
            execSQL("INSERT INTO collections (id, name, mediaTypeId, userCreated, defaultKey, parentId) VALUES ('c:wikipedia', 'Wikipedia', 'literature', 0, 'wikipedia', 'c:kiwix')")
            execSQL("INSERT INTO placements (packKey, collectionId, userSet) VALUES ('gutenberg_en_lcc-m', 'c:gutenberg', 0)")
            execSQL("INSERT INTO placements (packKey, collectionId, userSet) VALUES ('wikipedia_en_top', 'c:wikipedia', 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate("migrate14b.db", 14, true, LibraryDatabase.MIGRATION_13_14)
        db.query("SELECT packKey FROM placements").use {
            val keys = buildList { while (it.moveToNext()) add(it.getString(0)) }
            assertThat(keys).containsExactly("wikipedia_en_top")
        }
        db.query("SELECT id FROM collections ORDER BY id").use {
            val ids = buildList { while (it.moveToNext()) add(it.getString(0)) }
            assertThat(ids).containsExactly("c:kiwix", "c:wikipedia").inOrder()
        }
    }

    @Test fun migration13To14KeepsAGutenbergPackTheUserMoved() {
        helper.createDatabase("migrate14c.db", 13).apply {
            execSQL("INSERT INTO collections (id, name, mediaTypeId, userCreated, defaultKey, parentId) VALUES ('c:gutenberg', 'Gutenberg', 'literature', 0, 'gutenberg', 'c:kiwix')")
            execSQL("INSERT INTO placements (packKey, collectionId, userSet) VALUES ('gutenberg_en_lcc-m', 'c:gutenberg', 1)")
            execSQL("INSERT INTO placements (packKey, collectionId, userSet) VALUES ('gutenberg_en_lcc-pf', 'c:gutenberg', 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate("migrate14c.db", 14, true, LibraryDatabase.MIGRATION_13_14)
        db.query("SELECT packKey FROM placements").use {
            val keys = buildList { while (it.moveToNext()) add(it.getString(0)) }
            assertThat(keys).containsExactly("gutenberg_en_lcc-m")
        }
        db.query("SELECT COUNT(*) FROM collections WHERE id = 'c:gutenberg'").use { it.moveToFirst(); assertThat(it.getInt(0)).isEqualTo(1) }
    }

    @Test fun migration12To13GivesEveryBookEpubAndSearchable() {
        helper.createDatabase("migrate13.db", 12).apply {
            execSQL(
                "INSERT INTO books (id, origin, sourceRef, filePath, title, author, translator, date, language, subjects, " +
                    "description, coverPath, sizeBytes, status, indexedAt, addedAt) VALUES ('book:granger:hamlet', 'GRANGER', " +
                    "'hamlet', '/b/hamlet.epub', 'Hamlet', 'W. S.', '', '', 'en', '', '', NULL, 10, 'OK', 5, 1)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate("migrate13.db", 13, true, LibraryDatabase.MIGRATION_12_13)
        db.query("SELECT title, format, searchable, indexedAt FROM books").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("Hamlet")
            assertThat(it.getString(1)).isEqualTo("EPUB")
            assertThat(it.getInt(2)).isEqualTo(1)
            assertThat(it.getLong(3)).isEqualTo(5)
        }
    }

    @Test fun migration11To12KeepsRowsAndCreatesHomeTables() {
        helper.createDatabase("migrate12.db", 11).apply {
            execSQL("INSERT INTO recent_plays (`key`, title, playedAt) VALUES ('album:x', 'X', 1)")
            execSQL(
                "INSERT INTO voice_states (sourceKey, address, speed, title, section, voiceId, updatedAt) " +
                    "VALUES ('book:granger:hamlet', 'a', 1.0, 'Hamlet', NULL, 'v', 7)",
            )
            execSQL("INSERT INTO rail_pins (`key`, pinnedAt) VALUES ('tab:1', 5)")
            close()
        }
        val db = helper.runMigrationsAndValidate("migrate12.db", 12, true, LibraryDatabase.MIGRATION_11_12)
        db.execSQL("INSERT INTO home_feeds (scope, kind, `query`, ordinal, addedAt) VALUES ('family:wikipedia', 'ON_THIS_DAY', '', 0, 9)")
        db.execSQL("INSERT INTO home_draws (feedId, day, cardsJson) VALUES (1, '2026-10-22', '[]')")
        db.query("SELECT scope, kind FROM home_feeds").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("family:wikipedia")
            assertThat(it.getString(1)).isEqualTo("ON_THIS_DAY")
        }
        db.query("SELECT COUNT(*) FROM home_draws").use { it.moveToFirst(); assertThat(it.getInt(0)).isEqualTo(1) }
        db.query("SELECT COUNT(*) FROM recent_plays").use { it.moveToFirst(); assertThat(it.getInt(0)).isEqualTo(1) }
        db.query("SELECT COUNT(*) FROM voice_states").use { it.moveToFirst(); assertThat(it.getInt(0)).isEqualTo(1) }
        db.query("SELECT COUNT(*) FROM rail_pins").use { it.moveToFirst(); assertThat(it.getInt(0)).isEqualTo(1) }
    }

    @Test fun migration10To11AddsVoiceStates() {
        helper.createDatabase("migrate11.db", 10).apply {
            execSQL("INSERT INTO recent_plays (`key`, title, playedAt) VALUES ('album:x', 'X', 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate("migrate11.db", 11, true, LibraryDatabase.MIGRATION_10_11)
        db.execSQL(
            "INSERT INTO voice_states (sourceKey, address, speed, title, section, voiceId, updatedAt) " +
                "VALUES ('book:granger:hamlet', 'book:granger:hamlet@{}:3', 1.0, 'Hamlet', 'Act I', 'granger:vits-piper-en_US-ljspeech-medium', 7)",
        )
        db.query("SELECT sourceKey, section FROM voice_states").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("book:granger:hamlet")
            assertThat(it.getString(1)).isEqualTo("Act I")
        }
        db.query("SELECT COUNT(*) FROM recent_plays").use { it.moveToFirst(); assertThat(it.getInt(0)).isEqualTo(1) }
    }

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

    /** One v9 `collections` row. */
    private fun SupportSQLiteDatabase.collection(id: String, name: String, type: String, user: Boolean, key: String?) =
        execSQL(
            "INSERT INTO collections (id, name, mediaTypeId, userCreated, defaultKey) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, name, type, if (user) 1 else 0, key),
        )

    private fun SupportSQLiteDatabase.placement(packKey: String, collectionId: String, userSet: Boolean = false) =
        execSQL("INSERT INTO placements (packKey, collectionId, userSet) VALUES (?, ?, ?)", arrayOf<Any>(packKey, collectionId, if (userSet) 1 else 0))

    /** Every collection, by id, as (name, mediaTypeId, parentId). */
    private fun SupportSQLiteDatabase.collections(): Map<String, Triple<String, String, String?>> = buildMap {
        query("SELECT id, name, mediaTypeId, parentId FROM collections").use {
            while (it.moveToNext()) put(it.getString(0), Triple(it.getString(1), it.getString(2), if (it.isNull(3)) null else it.getString(3)))
        }
    }

    private fun SupportSQLiteDatabase.placements(): List<List<Any>> = buildList {
        query("SELECT packKey, collectionId, userSet FROM placements ORDER BY packKey").use {
            while (it.moveToNext()) add(listOf(it.getString(0), it.getString(1), it.getInt(2)))
        }
    }

    @Test fun migration9To10FilesKiwixCollectionsInTheFolderAndMapsUnderMaps() {
        helper.createDatabase("migrate10.db", 9).apply {
            execSQL("INSERT INTO media_types (id, name, builtIn, sortOrder) VALUES ('literature', 'Literature', 1, 0)")
            execSQL("INSERT INTO media_types (id, name, builtIn, sortOrder) VALUES ('u-ref', 'Reference', 0, 4)")
            collection("c:wikipedia", "Wikipedia", "literature", false, "wikipedia")
            collection("c:devdocs", "DevDocs", "literature", false, "devdocs")
            collection("c:pack:devdocs_en_rust", "Rust", "literature", false, "pack:devdocs_en_rust")
            collection("c:maps", "Maps", "literature", false, "maps")
            collection("c:granger-classics", "Granger Classics", "literature", false, "granger-classics")
            collection("c:my-books", "My books", "literature", false, "my-books")
            collection("c:archive-org", "Archive.org", "literature", false, "archive-org")
            collection("c:other", "Other", "literature", false, "other")
            collection("u-1", "Mine", "literature", true, null)
            // A Kiwix collection the user moved to their own type, and a media one they moved into Literature.
            collection("c:gutenberg", "Gutenberg", "u-ref", false, "gutenberg")
            collection("c:music:on-this-phone", "On this phone", "literature", false, "music:on-this-phone")
            placement("wikipedia_en_top", "c:wikipedia")
            placement("devdocs_en_python", "c:devdocs")
            placement("devdocs_en_rust", "c:pack:devdocs_en_rust")
            placement("maps_en_haiti", "c:maps")
            placement("book:granger:pride", "c:granger-classics")
            placement("wikipedia_en_medicine", "u-1", userSet = true)
            placement("gutenberg_en_all", "c:gutenberg")
            close()
        }
        val db = helper.runMigrationsAndValidate("migrate10.db", 10, false, LibraryDatabase.MIGRATION_9_10)
        assertThat(db.collections()).containsExactly(
            // The folder, and Kiwix's reading collections in it.
            "c:kiwix", Triple("Kiwix", "literature", null),
            "c:wikipedia", Triple("Wikipedia", "literature", "c:kiwix"),
            "c:devdocs", Triple("DevDocs", "literature", "c:kiwix"),
            "c:pack:devdocs_en_rust", Triple("Rust", "literature", "c:kiwix"),
            // Maps is its own type now.
            "c:maps", Triple("OpenStreetMap", "maps", null),
            // The rest stay where they were.
            "c:granger-classics", Triple("Granger Classics", "literature", null),
            "c:my-books", Triple("My books", "literature", null),
            "c:archive-org", Triple("Archive.org", "literature", null),
            "c:other", Triple("Other", "literature", null),
            "u-1", Triple("Mine", "literature", null),
            "c:gutenberg", Triple("Gutenberg", "u-ref", null),
            "c:music:on-this-phone", Triple("On this phone", "literature", null),
        )
        // No placement changes: packs go with their collections, and the user's move stands.
        assertThat(db.placements()).containsExactly(
            listOf("book:granger:pride", "c:granger-classics", 0),
            listOf("devdocs_en_python", "c:devdocs", 0),
            listOf("devdocs_en_rust", "c:pack:devdocs_en_rust", 0),
            listOf("gutenberg_en_all", "c:gutenberg", 0),
            listOf("maps_en_haiti", "c:maps", 0),
            listOf("wikipedia_en_medicine", "u-1", 1),
            listOf("wikipedia_en_top", "c:wikipedia", 0),
        ).inOrder()
        val types = mutableListOf<List<Any>>()
        db.query("SELECT id, name, builtIn, sortOrder FROM media_types ORDER BY id").use {
            while (it.moveToNext()) types += listOf(it.getString(0), it.getString(1), it.getInt(2), it.getInt(3))
        }
        assertThat(types).containsExactly(
            listOf("literature", "Literature", 1, 0),
            listOf("maps", "Maps", 1, 5),
            listOf("u-ref", "Reference", 0, 4),
        ).inOrder()
    }

    @Test fun migration9To10LeavesAMapsCollectionTheUserMovedAndMakesNoEmptyFolder() {
        helper.createDatabase("migrate10b.db", 9).apply {
            execSQL("INSERT INTO media_types (id, name, builtIn, sortOrder) VALUES ('u-places', 'Places', 0, 4)")
            collection("c:maps", "Maps", "u-places", false, "maps")
            collection("c:granger-classics", "Granger Classics", "literature", false, "granger-classics")
            placement("maps_en_haiti", "c:maps")
            close()
        }
        val db = helper.runMigrationsAndValidate("migrate10b.db", 10, false, LibraryDatabase.MIGRATION_9_10)
        // No Kiwix collections, so no folder; the moved maps collection keeps its type and name.
        assertThat(db.collections()).containsExactly(
            "c:maps", Triple("Maps", "u-places", null),
            "c:granger-classics", Triple("Granger Classics", "literature", null),
        )
        assertThat(db.placements()).containsExactly(listOf("maps_en_haiti", "c:maps", 0))
    }
}
