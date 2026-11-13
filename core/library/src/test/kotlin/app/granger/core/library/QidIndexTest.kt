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
class QidIndexTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A .gqid with tools/qidindex/build.py's schema: (path, qid, rank, other) articles and (qid, prop, value) links. */
    private fun gqid(
        name: String, articles: List<List<Any?>>, links: List<List<Any>> = emptyList(), coords: List<List<Any>> = emptyList(),
        kind: String = "gqid", schema: Int = 1, source: String = "a".repeat(64), sourceZim: String? = null,
    ): File {
        val f = File(tmp.root, "$name.gqid")
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE articles(path TEXT PRIMARY KEY, qid INTEGER NOT NULL, rank INTEGER NOT NULL, other TEXT) WITHOUT ROWID")
            db.execSQL("CREATE TABLE links(qid INTEGER NOT NULL, prop TEXT NOT NULL, value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE coords(qid INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL)")
            db.execSQL("INSERT INTO meta VALUES ('kind', '$kind'), ('schemaVersion', '$schema'), ('sourceSha256', '$source')")
            sourceZim?.let { db.execSQL("INSERT INTO meta VALUES ('sourceZim', ?)", arrayOf(it)) }
            articles.forEach { db.execSQL("INSERT INTO articles VALUES (?, ?, ?, ?)", it.toTypedArray()) }
            links.forEach { db.execSQL("INSERT INTO links VALUES (?, ?, ?)", it.toTypedArray()) }
            coords.forEach { db.execSQL("INSERT INTO coords VALUES (?, ?, ?)", it.toTypedArray()) }
        }
        return f
    }

    private val top = listOf(
        listOf("Jane_Austen", 36322L, 150, "Jane Austen"),
        listOf("Pride_and_Prejudice", 170583L, 90, "Orgullo y prejuicio"),
        listOf("London", 84L, 300, null),
    )
    private val links = listOf(listOf(170583L, "P50", "Q36322"), listOf(170583L, "P2034", "1342"), listOf(36322L, "P800", "Q170583"))

    @Test fun itResolvesArticlesTitlesAndJoins() {
        val index = QidIndex.open(gqid("top", top, links, listOf(listOf(84L, 51.5, -0.13))), "top")!!
        assertThat(index.qidOf("Pride_and_Prejudice")).isEqualTo(170583L)
        assertThat(index.qidOf("Nowhere")).isNull()
        assertThat(index.otherTitle("Pride_and_Prejudice")).isEqualTo("Orgullo y prejuicio")
        assertThat(index.otherTitle("London")).isNull()
        assertThat(index.itemsWith("P2034", "1342")).containsExactly(170583L)
        assertThat(index.linksOf(36322L, "P800")).containsExactly("Q170583")
        assertThat(index.coordinates(84L)).isEqualTo(51.5 to -0.13)
        index.close()
    }

    @Test fun theWrongKindANewerSchemaOrAnotherSourceDoesntOpen() {
        assertThat(QidIndex.open(gqid("a", top, kind = "glink"), "a")).isNull()
        assertThat(QidIndex.open(gqid("b", top, schema = 2), "b")).isNull()
        assertThat(QidIndex.open(gqid("c", top), "c", sourceSha256 = "b".repeat(64))).isNull()
        assertThat(QidIndex.open(gqid("d", top), "d", sourceSha256 = "a".repeat(64))).isNotNull()
        assertThat(QidIndex.open(File(tmp.root, "missing.gqid"), "e")).isNull()
        val notSqlite = File(tmp.root, "junk.gqid").apply { writeText("not a database") }
        assertThat(QidIndex.open(notSqlite, "f")).isNull()
    }

    @Test fun oneIndexServesEveryFlavourOfItsEdition() {
        // Plan LL1 Task 10: the catalog gives the nopic build to maxi; the maxi pack's sha differs but the edition matches.
        val nopic = "wikipedia_en_top_nopic_2026-09-14.zim"
        val other = "b".repeat(64)
        assertThat(QidIndex.open(gqid("m", top, sourceZim = nopic), "m", other, "wikipedia_en_top_maxi_2026-09-14.zim")).isNotNull()
        assertThat(QidIndex.open(gqid("n", top, sourceZim = nopic), "n", other, "wikipedia_en_top_mini_2026-09-14.zim")).isNotNull()
        assertThat(QidIndex.open(gqid("o", top, sourceZim = nopic), "o", other, "wikipedia_en_top_maxi_2026-10-14.zim")).isNull()
        assertThat(QidIndex.open(gqid("p", top, sourceZim = nopic), "p", other, "wikipedia_es_top_maxi_2026-09-14.zim")).isNull()
        assertThat(QidIndex.open(gqid("q", top, sourceZim = nopic), "q", other, "wikipedia_en_top1m_maxi_2026-09-14.zim")).isNull()
        assertThat(QidIndex.open(gqid("r", top), "r", other, "wikipedia_en_top_maxi_2026-09-14.zim")).isNull()
        assertThat(QidIndex.sameEdition("wikipedia_en_history_maxi_2026-10.zim", "wikipedia_en_history_nopic_2026-10.zim")).isTrue()
    }

    @Test fun aBooksLanguageComesFirst() {
        // Plan LL1 Task 10: English first in the library, but Don Quijote's Aristóteles opens the Spanish article.
        val en = QidIndex.open(gqid("en", listOf(listOf("Aristotle", 868L, 300, "Aristóteles")), sourceZim = "wikipedia_en_top_maxi_2026-09-14.zim"), "en")!!
        val es = QidIndex.open(gqid("es", listOf(listOf("Aristóteles", 868L, 300, "Aristotle")), sourceZim = "wikipedia_es_top_nopic_2026-10-10.zim"), "es")!!
        val all = QidIndexes(listOf(en, es))
        assertThat(en.language).isEqualTo("en")
        assertThat(all.articlesOf(868L, "es").map { it.path }).containsExactly("Aristóteles", "Aristotle").inOrder()
        assertThat(all.articlesOf(868L, "en").map { it.path }).containsExactly("Aristotle", "Aristóteles").inOrder()
        assertThat(all.articlesOf(868L, null).map { it.path }).containsExactly("Aristotle", "Aristóteles").inOrder()
        assertThat(all.articlesOf(868L, "fr").map { it.path }).containsExactly("Aristotle", "Aristóteles").inOrder()
        all.close()
    }

    @Test fun severalPacksResolveInPreferenceOrder() {
        val history = gqid("history", listOf(listOf("London", 84L, 300, null), listOf("Hastings", 83224L, 80, null)))
        val all = QidIndexes(listOf(QidIndex.open(gqid("top", top, links), "top")!!, QidIndex.open(history, "history")!!))
        assertThat(all.articlesOf(84L).map { it.packId }).containsExactly("top", "history").inOrder()
        assertThat(all.articlesOf(83224L).single().path).isEqualTo("Hastings")
        assertThat(all.articlesOf(1L)).isEmpty()
        assertThat(all.qidOf("history", "Hastings")).isEqualTo(83224L)
        assertThat(all.linksOf(170583L, "P50")).containsExactly("Q36322")
        all.close()
    }
}
