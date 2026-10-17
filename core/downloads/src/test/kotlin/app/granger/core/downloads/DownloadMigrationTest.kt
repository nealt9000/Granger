package app.granger.core.downloads

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
class DownloadMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DownloadDatabase::class.java)

    @Test fun version1RecordsBecomeSha256() {
        val hash = "ab".repeat(32)
        helper.createDatabase("migrate.db", 1).apply {
            execSQL(
                "INSERT INTO downloads (itemId, sha256, kind, title, urls, sizeBytes, filePath, snapshotDate, wifiOnly, state, " +
                    "bytesDone, error, hashFailures, verifiedAt, createdAt, updatedAt) " +
                    "VALUES ('med', '$hash', 'PACK', 'WikiMed', 'https://a.test/med.zim', 10, '/p/med.zim', '2026-10-10', 1, 'INSTALLED', " +
                    "10, NULL, 0, NULL, 1, 2)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate("migrate.db", 2, true)
        db.query("SELECT hash, hashAlgorithm FROM downloads").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo(hash)
            assertThat(it.getString(1)).isEqualTo("SHA256")
        }
    }
}
