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
}
