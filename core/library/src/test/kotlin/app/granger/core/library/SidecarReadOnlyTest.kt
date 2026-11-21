package app.granger.core.library

import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.MessageDigest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A sidecar is read, never written (Linked Library spec §5.2: it must keep matching its checksum). Since Plan I3 Android
 * opened sidecars read-write with `query_only`, and the framework wrote its `android_metadata` table into each on first
 * read: the Pixel 6's .gqid and .gtime files grew a page, left a journal, and Outfitting called their packs incomplete.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SidecarReadOnlyTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun readingASidecarLeavesItsBytesAlone() {
        // Built as tools/qidindex/build.py builds one: plain SQLite, no android_metadata.
        val f = File(tmp.root, "x.gqid")
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE articles(path TEXT PRIMARY KEY, qid INTEGER NOT NULL, rank INTEGER NOT NULL, other TEXT) WITHOUT ROWID")
            db.execSQL("INSERT INTO meta VALUES ('kind', 'gqid'), ('schemaVersion', '1'), ('sourceSha256', '${"a".repeat(64)}')")
            db.execSQL("INSERT INTO articles VALUES ('Moon', 405, 90, NULL)")
        }
        File(tmp.root, "x.gqid-journal").delete()
        val before = sha(f)
        val size = f.length()

        val index = QidIndex.open(f.path, "pack")!!
        assertThat(index.qidOf("Moon")).isEqualTo(405L)
        index.close()

        assertThat(f.length()).isEqualTo(size)
        assertThat(sha(f)).isEqualTo(before)
        assertThat(File(tmp.root, "x.gqid-journal").exists()).isFalse()
    }
}
