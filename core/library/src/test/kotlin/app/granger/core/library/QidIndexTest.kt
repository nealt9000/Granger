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
        subjects: List<List<Any>>? = null,
        facets: List<List<Any>>? = null, facetList: String? = null,
        topicParts: List<List<Any?>>? = null,
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
            if (subjects != null) {
                db.execSQL("CREATE TABLE subjects(qid INTEGER NOT NULL, ord INTEGER NOT NULL, subject TEXT NOT NULL, PRIMARY KEY(qid, ord)) WITHOUT ROWID")
                db.execSQL("CREATE INDEX subjects_subject ON subjects(subject, qid)")
                subjects.forEach { db.execSQL("INSERT INTO subjects VALUES (?, ?, ?)", it.toTypedArray()) }
            }
            if (facets != null) {
                db.execSQL("CREATE TABLE facets(facet INTEGER NOT NULL, qid INTEGER NOT NULL, PRIMARY KEY(facet, qid)) WITHOUT ROWID")
                facets.forEach { db.execSQL("INSERT INTO facets VALUES (?, ?)", it.toTypedArray()) }
                facetList?.let { db.execSQL("INSERT INTO meta VALUES ('facets', ?)", arrayOf(it)) }
            }
            if (topicParts != null) {
                db.execSQL("CREATE TABLE topics(qid INTEGER PRIMARY KEY, under INTEGER NOT NULL, inlinks INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE topic_parts(topic INTEGER NOT NULL, part INTEGER NOT NULL, ord INTEGER NOT NULL, item INTEGER, grp INTEGER, text TEXT, note TEXT, PRIMARY KEY(topic, part, ord)) WITHOUT ROWID")
                topicParts.map { it[0] }.distinct().forEach { db.execSQL("INSERT INTO topics VALUES (?, 72, 86)", arrayOf(it)) }
                topicParts.forEach { db.execSQL("INSERT INTO topic_parts VALUES (?, ?, ?, ?, ?, ?, ?)", it.toTypedArray()) }
            }
        }
        return f
    }

    @Test fun aBroadTopicsPartsReadInOrderFromItsOwnPackFirstThenItsLanguage() {
        val parts = listOf<List<Any?>>(
            listOf(197L, 1, 0, 127771L, 2, "Fighter aircraft", null),
            listOf(197L, 2, 0, 1005L, 0, null, null), listOf(197L, 2, 1, 1006L, -1, null, null),
            listOf(197L, 3, 0, null, null, "History", "History"),
            listOf(197L, 4, 0, 8008L, -1, null, null), listOf(197L, 4, 1, 9009L, 0, null, null),
            listOf(197L, 5, 0, null, 0, "The Wright Flyer, 1903", "_assets_/a/Flyer.jpg"),
            listOf(197L, 6, 0, 1385L, null, null, null),
        )
        val en = QidIndex.open(gqid("en", listOf(listOf("Airplane", 197L, 86, "Avión")), sourceZim = "wikipedia_en_top_maxi_2026-09-14.zim", topicParts = parts), "en")!!
        val es = QidIndex.open(gqid("es", listOf(listOf("Avión", 197L, 86, "Airplane")), sourceZim = "wikipedia_es_top_nopic_2026-10-10.zim",
            topicParts = listOf(listOf(197L, 3, 0, null, null, "Historia", "Historia"))), "es")!!
        val old = QidIndex.open(gqid("old", top), "old")!!   // built before topics
        val all = QidIndexes(listOf(old, es, en))
        val p = all.topicParts(197L, packId = "en", language = "en")!!
        assertThat(p.packId).isEqualTo("en")
        assertThat(p.under).isEqualTo(72)
        assertThat(p.groups).containsExactly(TopicParts.Group(127771L, "Fighter aircraft", 2))
        assertThat(p.kinds).containsExactly(TopicParts.Kind(1005L, 0), TopicParts.Kind(1006L, -1)).inOrder()
        assertThat(p.sections).containsExactly(TopicParts.Section("History", "History"))
        assertThat(p.links).containsExactly(TopicParts.Link(8008L, -1), TopicParts.Link(9009L, 0)).inOrder()
        assertThat(p.figures).containsExactly(TopicParts.Figure("_assets_/a/Flyer.jpg", "The Wright Flyer, 1903", 0))
        assertThat(p.people).containsExactly(1385L)
        // From a Spanish page: the Spanish parts.
        assertThat(all.topicParts(197L, packId = "es", language = "es")!!.sections.single().title).isEqualTo("Historia")
        assertThat(all.topicParts(36322L, packId = "en", language = "en")).isNull()
        assertThat(old.topicParts(197L)).isNull()
    }

    @Test fun aSubjectsBroadTopicsFitForASpreadAreItsOwnWithEnoughFiguresBestKnownFirst() {
        fun figure(topic: Long, ord: Int) = listOf<Any?>(topic, 5, ord, null, 0, "A caption", "_assets_/a/$topic-$ord.jpg")
        fun kind(topic: Long) = listOf<Any?>(topic, 2, 0, 1005L, -1, null, null)
        // Airplane and Ship have two figures; Kite has one; Bread's main subject is Food (Technology is its second).
        val parts = listOf(kind(197L), figure(197L, 0), figure(197L, 1), kind(11446L), figure(11446L, 0), figure(11446L, 1), kind(42861L), figure(42861L, 0),
            kind(7802L), figure(7802L, 0), figure(7802L, 1))
        val index = QidIndex.open(gqid(
            "en", listOf(listOf("Airplane", 197L, 86, null), listOf("Ship", 11446L, 120, null), listOf("Kite", 42861L, 60, null), listOf("Bread", 7802L, 140, null)),
            subjects = listOf(listOf(197L, 0, "technology"), listOf(11446L, 0, "technology"), listOf(42861L, 0, "technology"), listOf(7802L, 0, "food"), listOf(7802L, 1, "technology")),
            facets = listOf(listOf(1, 197L), listOf(1, 11446L), listOf(0, 11446L)),
            facetList = """{"technology":[{"id":"weapons","en":"Weapons","es":"Armas","code":0},{"id":"transport","en":"Cars, planes & ships","es":"Autos","code":1}]}""",
            topicParts = parts,
        ), "en")!!
        val all = QidIndexes(listOf(QidIndex.open(gqid("old", top), "old")!!, index))
        assertThat(all.broadIn("technology", minKinds = 8, minFigures = 2, limit = 10).map { it.path }).containsExactly("Ship", "Airplane").inOrder()
        assertThat(all.broadIn("technology", minKinds = 8, minFigures = 1, limit = 2).map { it.path }).containsExactly("Ship", "Airplane").inOrder()
        assertThat(all.broadIn("technology", minKinds = 100, minFigures = 2, limit = 10)).isEmpty()
        assertThat(all.broadIn("food", minKinds = 8, minFigures = 2, limit = 10).map { it.path }).containsExactly("Bread")
        // A topic's room: the first of its subject's, in their order, that holds it.
        assertThat(all.facetOf(197L, "technology")?.id).isEqualTo("transport")
        assertThat(all.facetOf(11446L, "technology")?.id).isEqualTo("weapons")
        assertThat(all.facetOf(42861L, "technology")).isNull()
        // The room's other broad topics, for setting one beside another; and how many articles link to one.
        assertThat(all.broadInFacet("technology.transport", 5).map { it.path }).containsExactly("Ship", "Airplane").inOrder()
        assertThat(all.broadInFacet("technology.weapons", 5).map { it.path }).containsExactly("Ship")
        assertThat(all.broadInFacet("technology.nowhere", 5)).isEmpty()
        assertThat(all.topicParts(197L)!!.inlinks).isEqualTo(86)
        // What leads to an item: the broad topics whose parts hold it. Every topic here has kind 1005 (not installed).
        assertThat(all.holders(1005L, 10).map { it.path }).containsExactly("Bread", "Ship", "Airplane", "Kite").inOrder()
        assertThat(all.holders(1005L, 2).map { it.path }).containsExactly("Bread", "Ship").inOrder()
        assertThat(all.holders(197L, 10)).isEmpty()
        assertThat(all.heldBy(listOf(1005L, 197L), except = 197L)).containsExactly(1005L, 3)
        assertThat(all.heldBy(listOf(1005L), except = null)).containsExactly(1005L, 4)
        assertThat(all.heldBy(emptyList(), except = null)).isEmpty()
        // An item's rooms, in their order.
        assertThat(all.roomsOf(11446L).map { it.id }).containsExactly("weapons", "transport").inOrder()
        assertThat(all.roomsOf(42861L)).isEmpty()
        // The first place an item can be read, without asking the packs after it; counts are made once and kept.
        assertThat(all.firstArticleOf(197L)?.path).isEqualTo("Airplane")
        assertThat(all.firstArticleOf(84L)?.packId).isEqualTo("old")
        assertThat(all.firstArticleOf(999_999L)).isNull()
        assertThat(all.subjectCounts(listOf("technology"))).isEqualTo(all.subjectCounts(listOf("technology")))
        assertThat(all.topIn("technology", 2, mainOnly = true)).isSameInstanceAs(all.topIn("technology", 2, mainOnly = true))
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

    @Test fun subjectsAreCountedOncePerItemAcrossPacksAndListedMostLinkedFirst() {
        // English and Spanish Top share Jane Austen (36322) and London (84); Pride and Prejudice is English only, Bélgica Spanish only.
        val en = QidIndex.open(gqid("en", top, subjects = listOf(
            listOf(36322L, 0, "literature"), listOf(36322L, 1, "history"), listOf(170583L, 0, "literature"), listOf(84L, 0, "places"),
        )), "en")!!
        val es = QidIndex.open(gqid("es", listOf(
            listOf("Jane_Austen", 36322L, 150, "Jane Austen"), listOf("Londres", 84L, 300, "London"), listOf("Bélgica", 31L, 383, "Belgium"),
        ), subjects = listOf(listOf(36322L, 0, "literature"), listOf(84L, 0, "places"), listOf(31L, 0, "places"))), "es")!!
        val old = QidIndex.open(gqid("old", listOf(listOf("Paris", 90L, 400, null))), "old")!!   // built before subjects
        assertThat(old.hasSubjects).isFalse()
        assertThat(old.topIn("places", 5)).isEmpty()
        val all = QidIndexes(listOf(en, es, old))
        assertThat(all.hasSubjects).isTrue()
        assertThat(all.subjectCounts(listOf("literature", "places", "history", "music")))
            .containsExactly("literature", 2, "places", 2, "history", 1, "music", 0)
        assertThat(all.itemCount()).isEqualTo(5)
        assertThat(all.subjectsOf(36322L)).containsExactly("literature", "history").inOrder()
        // Each item once, in the first pack that has it (the library's main language first), most-linked first.
        assertThat(all.topIn("places", 5).map { it.packId to it.path }).containsExactly("es" to "Bélgica", "en" to "London").inOrder()
        assertThat(all.topIn("places", 1, offset = 1).map { it.path }).containsExactly("London")
        assertThat(all.topIn("literature", 5).map { it.path }).containsExactly("Jane_Austen", "Pride_and_Prejudice").inOrder()
        // History is Jane Austen's second subject: she's on History's page, after its own articles, but not its example.
        assertThat(all.topIn("history", 5).map { it.path }).containsExactly("Jane_Austen")
        assertThat(all.topIn("history", 5, mainOnly = true)).isEmpty()
        // London (rank 300) has History only second; Jane Austen (150) has it first, so she leads History's page.
        val mixed = QidIndexes(listOf(QidIndex.open(gqid("mixed", top, subjects = listOf(
            listOf(84L, 0, "places"), listOf(84L, 1, "history"), listOf(36322L, 0, "history"),
        )), "mixed")!!))
        assertThat(mixed.topIn("history", 5).map { it.path to it.mainSubject }).containsExactly("Jane_Austen" to true, "London" to false).inOrder()
        mixed.close()
        all.close()
    }

    @Test fun aSubjectSetHoldsEachItemOnceWithItsMainFlag() {
        val en = QidIndex.open(gqid("en", top, subjects = listOf(listOf(36322L, 0, "literature"), listOf(36322L, 1, "history"), listOf(84L, 1, "history"))), "en")!!
        val es = QidIndex.open(gqid("es", listOf(listOf("Londres", 84L, 300, "London"), listOf("Roma", 220L, 300, "Rome")),
            subjects = listOf(listOf(84L, 1, "history"), listOf(220L, 0, "history"))), "es")!!
        val all = QidIndexes(listOf(en, es))
        val set = all.subjectSet("history")
        assertThat(set.size).isEqualTo(3)
        assertThat(36322L in set && 84L in set && 220L in set).isTrue()
        assertThat(170583L in set).isFalse()
        assertThat(set.isMain(220L)).isTrue()
        assertThat(set.isMain(84L)).isFalse()
        assertThat(all.subjectSet("music").size).isEqualTo(0)
        all.close()
    }

    @Test fun subCategoriesAreListedInOrderCountedOnceAndRankedAcrossPacks() {
        // Two builds with different codes for the same facets: the app asks by key, each index answers with its own code.
        val list = """{"literature":[{"id":"works","en":"Great books","es":"Grandes libros","code":0},{"id":"writers","en":"Writers","es":"Escritores","code":1}]}"""
        val list2 = """{"literature":[{"id":"works","en":"Great books","es":"Grandes libros","code":7},{"id":"writers","en":"Writers","es":"Escritores","code":8}]}"""
        val en = QidIndex.open(gqid("en", top, subjects = listOf(listOf(36322L, 0, "literature"), listOf(170583L, 0, "literature")),
            facets = listOf(listOf(1, 36322L), listOf(0, 170583L)), facetList = list), "en")!!
        val es = QidIndex.open(gqid("es", listOf(listOf("Jane_Austen", 36322L, 150, null), listOf("Cervantes", 5682L, 200, null)),
            subjects = listOf(listOf(36322L, 0, "literature"), listOf(5682L, 0, "literature")),
            facets = listOf(listOf(8, 36322L), listOf(8, 5682L)), facetList = list2), "es")!!
        val old = QidIndex.open(gqid("old", listOf(listOf("Paris", 90L, 400, null)), subjects = listOf(listOf(90L, 0, "places"))), "old")!!
        val all = QidIndexes(listOf(en, es, old))
        assertThat(all.hasFacets).isTrue()
        assertThat(all.facets("literature").map { it.id }).containsExactly("works", "writers").inOrder()
        assertThat(all.facets("literature")[1].name("es")).isEqualTo("Escritores")
        assertThat(all.facets("music")).isEmpty()
        assertThat(all.facetCounts("literature")).containsExactly("works", 1, "writers", 2)
        assertThat(all.topInFacet("literature.writers", 10).map { it.qid }).containsExactly(5682L, 36322L).inOrder()
        val set = all.facetSet("literature.writers")
        assertThat(set.size).isEqualTo(2)
        assertThat(set.isMain(5682L)).isTrue()
        all.close()
    }
}
