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

    @Test fun version2RecordsHaveNoGroup() {
        val pack = "ab".repeat(32)
        val book = "cd".repeat(20)
        helper.createDatabase("migrate2.db", 2).apply {
            execSQL(
                "INSERT INTO downloads (itemId, hash, hashAlgorithm, kind, title, urls, sizeBytes, filePath, snapshotDate, wifiOnly, state, " +
                    "bytesDone, error, hashFailures, verifiedAt, createdAt, updatedAt) " +
                    "VALUES ('med', '$pack', 'SHA256', 'PACK', 'WikiMed', 'https://a.test/med.zim\nhttps://b.test/med.zim', 10, " +
                    "'/p/packs/med_2026-10-10.zim', '2026-10-10', 1, 'INSTALLED', 10, NULL, 0, 5, 1, 2)",
            )
            execSQL(
                "INSERT INTO downloads (itemId, hash, hashAlgorithm, kind, title, urls, sizeBytes, filePath, snapshotDate, wifiOnly, state, " +
                    "bytesDone, error, hashFailures, verifiedAt, createdAt, updatedAt) " +
                    "VALUES ('archive-org:scan1881', '$book', 'SHA1', 'BOOK', 'Scan', 'https://archive.org/download/scan1881/x.epub', 20, " +
                    "'/p/books/archive-org/scan1881.epub', '2026-10-17', 0, 'INSTALLED', 20, NULL, 0, NULL, 3, 4)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate("migrate2.db", 3, true)
        db.query(
            "SELECT itemId, hash, hashAlgorithm, kind, title, urls, sizeBytes, filePath, snapshotDate, wifiOnly, state, bytesDone, " +
                "error, hashFailures, verifiedAt, createdAt, updatedAt, groupId FROM downloads ORDER BY itemId",
        ).use {
            assertThat(it.count).isEqualTo(2)
            it.moveToFirst()
            assertThat(it.getString(0)).isEqualTo("archive-org:scan1881")
            assertThat(it.getString(1)).isEqualTo(book)
            assertThat(it.getString(2)).isEqualTo("SHA1")
            assertThat(it.getString(3)).isEqualTo("BOOK")
            assertThat(it.getString(7)).isEqualTo("/p/books/archive-org/scan1881.epub")
            assertThat(it.getInt(9)).isEqualTo(0)
            assertThat(it.getString(10)).isEqualTo("INSTALLED")
            assertThat(it.isNull(14)).isTrue()
            assertThat(it.isNull(17)).isTrue()
            it.moveToNext()
            assertThat(it.getString(0)).isEqualTo("med")
            assertThat(it.getString(1)).isEqualTo(pack)
            assertThat(it.getString(2)).isEqualTo("SHA256")
            assertThat(it.getString(3)).isEqualTo("PACK")
            assertThat(it.getString(4)).isEqualTo("WikiMed")
            assertThat(it.getString(5)).isEqualTo("https://a.test/med.zim\nhttps://b.test/med.zim")
            assertThat(it.getLong(6)).isEqualTo(10)
            assertThat(it.getString(7)).isEqualTo("/p/packs/med_2026-10-10.zim")
            assertThat(it.getString(8)).isEqualTo("2026-10-10")
            assertThat(it.getInt(9)).isEqualTo(1)
            assertThat(it.getString(10)).isEqualTo("INSTALLED")
            assertThat(it.getLong(11)).isEqualTo(10)
            assertThat(it.isNull(12)).isTrue()
            assertThat(it.getInt(13)).isEqualTo(0)
            assertThat(it.getLong(14)).isEqualTo(5)
            assertThat(it.getLong(15)).isEqualTo(1)
            assertThat(it.getLong(16)).isEqualTo(2)
            assertThat(it.isNull(17)).isTrue()
        }
    }

    @Test fun version3RecordsGetTheirQueuePlaceFromWhenTheyCameThenTheirId() {
        fun insert(db: androidx.sqlite.db.SupportSQLiteDatabase, id: String, state: String, created: Long, group: String?) = db.execSQL(
            "INSERT INTO downloads (itemId, hash, hashAlgorithm, kind, title, urls, sizeBytes, filePath, snapshotDate, groupId, " +
                "wifiOnly, state, bytesDone, error, hashFailures, verifiedAt, createdAt, updatedAt) " +
                "VALUES ('$id', '${"ab".repeat(32)}', 'SHA256', 'PACK', '$id', 'https://a.test/$id.zim', 10, '/p/packs/$id.zim', " +
                "'2026-10-10', ${group?.let { "'$it'" } ?: "NULL"}, 1, '$state', 0, " +
                "${if (state == "FAILED") "'lb.download.kiwix.org answered 404'" else "NULL"}, 0, NULL, $created, $created)",
        )
        helper.createDatabase("migrate4.db", 3).apply {
            // An onboarding group shares one createdAt; a pack got later; one got before.
            insert(this, "wikem", "FAILED", 20, "onboarding:20")
            insert(this, "knots", "INSTALLED", 20, "onboarding:20")
            insert(this, "later", "QUEUED", 30, null)
            insert(this, "early", "INSTALLED", 10, null)
            close()
        }
        val db = helper.runMigrationsAndValidate("migrate4.db", 4, true, DownloadDatabase.MIGRATION_3_4)
        db.query("SELECT itemId, queueOrder, attempts, retryAt, state, error, groupId FROM downloads ORDER BY queueOrder").use {
            val rows = generateSequence { if (it.moveToNext()) Triple(it.getString(0), it.getLong(1), it.getInt(2)) else null }.toList()
            assertThat(rows).containsExactly(
                Triple("early", 0L, 0), Triple("knots", 1L, 0), Triple("wikem", 2L, 0), Triple("later", 3L, 0),
            ).inOrder()
            it.moveToPosition(2)
            assertThat(it.isNull(3)).isTrue()
            assertThat(it.getString(4)).isEqualTo("FAILED")
            assertThat(it.getString(5)).isEqualTo("lb.download.kiwix.org answered 404")
            assertThat(it.getString(6)).isEqualTo("onboarding:20")
        }
    }

    @Test fun migratedRecordsReadBackThroughRoom() = kotlinx.coroutines.test.runTest {
        val hash = "ab".repeat(32)
        helper.createDatabase("migrate3.db", 2).apply {
            execSQL(
                "INSERT INTO downloads (itemId, hash, hashAlgorithm, kind, title, urls, sizeBytes, filePath, snapshotDate, wifiOnly, state, " +
                    "bytesDone, error, hashFailures, verifiedAt, createdAt, updatedAt) " +
                    "VALUES ('med', '$hash', 'SHA256', 'PACK', 'WikiMed', 'https://a.test/med.zim', 10, '/p/med.zim', '2026-10-10', 1, " +
                    "'INSTALLED', 10, NULL, 0, NULL, 1, 2)",
            )
            close()
        }
        val db = androidx.room.Room.databaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(), DownloadDatabase::class.java, "migrate3.db",
        ).addMigrations(DownloadDatabase.MIGRATION_3_4).allowMainThreadQueries().build()
        try {
            val record = db.downloadDao().getAll().single()
            assertThat(record.itemId).isEqualTo("med")
            assertThat(record.state).isEqualTo(DownloadState.INSTALLED)
            assertThat(record.urls).containsExactly("https://a.test/med.zim")
            assertThat(record.groupId).isNull()
            assertThat(record.queueOrder).isEqualTo(0)
            assertThat(record.attempts).isEqualTo(0)
            assertThat(record.retryAt).isNull()
        } finally {
            db.close()
        }
    }
}
