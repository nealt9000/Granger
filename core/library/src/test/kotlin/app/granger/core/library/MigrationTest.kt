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
        val db = helper.runMigrationsAndValidate("migrate.db", 2, true)
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
}
