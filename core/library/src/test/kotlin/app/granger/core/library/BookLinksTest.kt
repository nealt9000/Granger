package app.granger.core.library

import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookLinksTest {
    @get:Rule val tmp = TemporaryFolder()
    private var made = 0

    /** A .glink with tools/links/build.py's schema. */
    private fun glink(kind: String = "glink", schema: Int = 1, source: String = "a".repeat(64), qid: Long? = 170583L): File {
        val f = File(tmp.root, "gc-pp-${made++}.glink")
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("INSERT INTO meta VALUES ('kind', '$kind'), ('schemaVersion', '$schema'), ('sourceSha256', '$source')")
            db.execSQL("CREATE TABLE book(id TEXT NOT NULL, qid INTEGER, year INTEGER)")
            db.execSQL("INSERT INTO book VALUES ('gc-pp', ?, 1813)", arrayOf(qid))
            db.execSQL(
                "CREATE TABLE mentions(book TEXT NOT NULL, href TEXT NOT NULL, start INTEGER NOT NULL, len INTEGER NOT NULL, " +
                    "quote TEXT NOT NULL, before TEXT NOT NULL, qid INTEGER NOT NULL, tier INTEGER NOT NULL, conf REAL NOT NULL)",
            )
            listOf<List<Any>>(
                listOf("OEBPS/c002.xhtml", 40, 6, "London", "went up to ", 84L, 1, 1.0),
                listOf("OEBPS/c001.xhtml", 90, 5, "Derby", "the county of ", 23098L, 2, 0.9),
                listOf("OEBPS/c001.xhtml", 10, 6, "London", "Ruskin House, ", 84L, 1, 1.0),
            ).forEach { db.execSQL("INSERT INTO mentions VALUES ('gc-pp', ?, ?, ?, ?, ?, ?, ?, ?)", it.toTypedArray()) }
        }
        return f
    }

    @Test fun readsTheBookAndItsMentionsByTier() {
        BookLinks.open(glink(), "a".repeat(64))!!.use { links ->
            assertThat(links.bookId).isEqualTo("gc-pp")
            assertThat(links.workQid).isEqualTo(170583L)
            assertThat(links.year).isEqualTo(1813)
            assertThat(links.mentions(1).map { it.href to it.start }).containsExactly("OEBPS/c001.xhtml" to 10, "OEBPS/c002.xhtml" to 40).inOrder()
            assertThat(links.mentions(2)).hasSize(3)
            assertThat(links.mentions(2)[1]).isEqualTo(BookMention("OEBPS/c001.xhtml", 90, "Derby", "the county of ", 23098L, 2))
            assertThat(links.countOf(84L)).isEqualTo(2)
            assertThat(links.passagesOf(84L, 1).map { it.href }).containsExactly("OEBPS/c001.xhtml")
            assertThat(links.passagesOf(84L, 3).map { it.before }).containsExactly("Ruskin House, ", "went up to ").inOrder()
            assertThat(links.passagesOf(9L, 3)).isEmpty()
        }
    }

    @Test fun aBookWithNoWorkHasANullQid() {
        BookLinks.open(glink(qid = null))!!.use { assertThat(it.workQid).isNull() }
    }

    @Test fun refusesAnotherKindANewerSchemaOrAnotherEpub() {
        assertThat(BookLinks.open(glink(kind = "gqid"))).isNull()
        assertThat(BookLinks.open(glink(schema = 2))).isNull()
        assertThat(BookLinks.open(glink(), sourceSha256 = "b".repeat(64))).isNull()
        assertThat(BookLinks.open(File(tmp.root, "missing.glink"))).isNull()
    }
}
