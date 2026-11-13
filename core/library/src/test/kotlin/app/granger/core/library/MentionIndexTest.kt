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
class MentionIndexTest {
    @get:Rule val tmp = TemporaryFolder()
    private var made = 0

    /** A .glink with tools/links/build.py's schema: [mentions] are (qid, href, start). */
    private fun glink(work: Long?, vararg mentions: Triple<Long, String, Int>, kind: String = "glink"): LinksFile {
        val f = File(tmp.root, "b-${made++}.glink")
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("INSERT INTO meta VALUES ('kind', '$kind'), ('schemaVersion', '1'), ('sourceSha256', 'sha')")
            db.execSQL("CREATE TABLE book(id TEXT NOT NULL, qid INTEGER, year INTEGER)")
            db.execSQL("INSERT INTO book VALUES ('x', ?, 1800)", arrayOf<Any?>(work))
            db.execSQL("CREATE TABLE mentions(book TEXT, href TEXT, start INTEGER, len INTEGER, quote TEXT, before TEXT, qid INTEGER, tier INTEGER, conf REAL)")
            for ((qid, href, start) in mentions) {
                db.execSQL("INSERT INTO mentions VALUES ('x', ?, ?, 6, 'London', 'in ', ?, 1, 1.0)", arrayOf<Any>(href, start, qid))
            }
        }
        return LinksFile(f, "sha")
    }

    @Test fun holdsTheInstalledBooksAndAnswersByQid() {
        val pp = glink(170583L, Triple(84L, "c2.xhtml", 5), Triple(84L, "c1.xhtml", 9), Triple(84L, "c1.xhtml", 2), Triple(90L, "c1.xhtml", 40))
        val emma = glink(191047L, Triple(84L, "c1.xhtml", 1))
        val plain = glink(null, Triple(90L, "c3.xhtml", 0))
        val file = File(tmp.root, "idx/mentions.sqlite")
        val index = MentionIndex(file)
        assertThat(index.sync(mapOf("pp" to pp, "emma" to emma, "plain" to plain))).isEqualTo(3)
        assertThat(index.works()).containsExactly("pp", 170583L, "emma", 191047L)
        assertThat(index.counts(84L)).containsExactly("pp" to 3, "emma" to 1).inOrder()
        assertThat(index.counts(90L)).containsExactly("plain" to 1, "pp" to 1).inOrder()
        assertThat(index.passages("pp", 84L, 2).map { it.href to it.start }).containsExactly("c1.xhtml" to 2, "c1.xhtml" to 9).inOrder()
        assertThat(index.counts(7L)).isEmpty()
        // The same set again is nothing to do; a new process finds the books already in.
        assertThat(index.sync(mapOf("pp" to pp, "emma" to emma, "plain" to plain))).isEqualTo(0)
        index.close()
        val again = MentionIndex(file)
        assertThat(again.sync(mapOf("pp" to pp, "emma" to emma, "plain" to plain))).isEqualTo(0)
        assertThat(again.counts(84L)).hasSize(2)
        // A book removed goes; a book updated to a new file is copied again.
        val emma2 = glink(191047L, Triple(84L, "c1.xhtml", 1), Triple(84L, "c4.xhtml", 3))
        assertThat(again.sync(mapOf("pp" to pp, "emma" to emma2))).isEqualTo(1)
        assertThat(again.counts(84L)).containsExactly("pp" to 3, "emma" to 2).inOrder()
        assertThat(again.counts(90L)).containsExactly("pp" to 1)
        assertThat(again.works().keys).containsExactly("pp", "emma")
    }

    @Test fun leavesOutAFileThatIsntLinksOrIsFromAnotherEpub() {
        val index = MentionIndex(File(tmp.root, "m.sqlite"))
        val wrongKind = glink(1L, Triple(84L, "c1.xhtml", 1), kind = "gqid")
        val wrongEpub = glink(2L, Triple(84L, "c1.xhtml", 1)).copy(sourceSha256 = "other")
        val missing = LinksFile(File(tmp.root, "gone.glink"), "sha")
        assertThat(index.sync(mapOf("a" to wrongKind, "b" to wrongEpub, "c" to missing))).isEqualTo(0)
        assertThat(index.counts(84L)).isEmpty()
    }
}
