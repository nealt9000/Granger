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
class TimeIndexTest {
    @get:Rule val tmp = TemporaryFolder()

    private val laneIds = "philosophy_religion,civilisations,languages,film_music,art_literature,science,wars,events_politics,people"
    private fun bit(lane: Lane) = 1L shl laneIds.split(',').indexOf(lane.id)

    /** (qid, path, kind, y0, y1, lanes, rank) rows in a .gtime with the build tool's schema. */
    private fun gtime(name: String, rows: List<List<Any?>>, format: Int = 1, lanes: String = laneIds): File {
        val f = File(tmp.root, "$name.gtime")
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL(
                "CREATE TABLE marks(qid INTEGER NOT NULL, path TEXT NOT NULL, title TEXT NOT NULL, kind INTEGER NOT NULL, y0 INTEGER NOT NULL, " +
                    "md0 INTEGER NOT NULL, y1 INTEGER, md1 INTEGER, prec INTEGER NOT NULL, lanes INTEGER NOT NULL, rank INTEGER NOT NULL, lat REAL, lon REAL)",
            )
            db.execSQL("INSERT INTO meta VALUES ('format', '$format'), ('lanes', '$lanes')")
            for (r in rows) {
                db.execSQL(
                    "INSERT INTO marks VALUES (?, ?, ?, ?, ?, 0, ?, NULL, 9, ?, ?, NULL, NULL)",
                    arrayOf(r[0], r[1], (r[1] as String).replace('_', ' '), r[2], r[3], r[4], r[5], r[6]),
                )
            }
        }
        return f
    }

    private val top = listOf(
        listOf(2277L, "Roman_Empire", 1, -25, 476, bit(Lane.CIVILISATIONS), 250),
        listOf(83224L, "Battle_of_Hastings", 0, 1066, null, bit(Lane.WARS), 80),
        listOf(7259L, "Ada_Lovelace", 2, 1815, 1852, bit(Lane.PEOPLE) or bit(Lane.SCIENCE), 120),
        listOf(1L, "Living_Person", 2, 1961, null, bit(Lane.PEOPLE), 90),
        listOf(868L, "Aristotle", 2, -383, -321, bit(Lane.PEOPLE) or bit(Lane.PHILOSOPHY_RELIGION), 300),
    )

    @Test fun marksOverlapTheWindowAndFilterByLane() {
        val idx = TimeIndex.open(gtime("top", top), "top")!!
        assertThat(idx.marks(1000, 1100, emptySet(), 10, now = 2026).map { it.path }).containsExactly("Battle_of_Hastings")
        assertThat(idx.marks(300, 400, emptySet(), 10, now = 2026).map { it.path }).containsExactly("Roman_Empire")
        // A living person runs to now; a point covers only its year.
        assertThat(idx.marks(2020, 2026, emptySet(), 10, now = 2026).map { it.path }).containsExactly("Living_Person")
        assertThat(idx.marks(-500, 2026, setOf(Lane.SCIENCE), 10, now = 2026).map { it.path }).containsExactly("Ada_Lovelace")
        val ada = idx.marks(-500, 2026, setOf(Lane.SCIENCE), 10, now = 2026).single()
        assertThat(ada.lanes).containsExactly(Lane.PEOPLE, Lane.SCIENCE)
        assertThat(ada.kind).isEqualTo(TimeKind.LIFE)
    }

    @Test fun theLimitKeepsTheHighestRanked() {
        val idx = TimeIndex.open(gtime("top", top), "top")!!
        assertThat(idx.marks(-1000, 2026, emptySet(), 2, now = 2026).map { it.path }).containsExactly("Aristotle", "Roman_Empire").inOrder()
    }

    @Test fun aroundGivesNeighboursOnBothSidesInTimeOrder() {
        val idx = TimeIndex.open(gtime("top", top), "top")!!
        assertThat(idx.around(1066, emptySet(), 2).map { it.path }).containsExactly("Aristotle", "Roman_Empire", "Battle_of_Hastings", "Ada_Lovelace").inOrder()
    }

    @Test fun anArticleInTwoPacksAppearsOnceFromThePreferredPack() {
        val history = listOf(listOf(83224L, "Battle_of_Hastings", 0, 1066, null, bit(Lane.WARS), 80), listOf(42L, "Domesday_Book", 0, 1086, null, 0L, 40))
        val all = TimeIndexes(listOf(TimeIndex.open(gtime("top", top), "top")!!, TimeIndex.open(gtime("history", history), "history")!!))
        val marks = all.marks(1000, 1100, emptySet(), 10, now = 2026)
        assertThat(marks.map { it.packId to it.path }).containsExactly("top" to "Battle_of_Hastings", "history" to "Domesday_Book").inOrder()
        assertThat(all.around(1070, emptySet(), 1).map { it.path }).containsExactly("Battle_of_Hastings", "Domesday_Book").inOrder()
    }

    @Test fun lanesAreReadByNameSoAFileWithOtherLanesStillReads() {
        // A file built with only two lanes, in another order.
        val f = gtime("old", listOf(listOf(5L, "Plato", 2, -427, -347, 1L, 200)), lanes = "people,philosophy_religion")
        val idx = TimeIndex.open(f, "old")!!
        assertThat(idx.marks(-500, 0, setOf(Lane.PEOPLE), 10, now = 2026).single().lanes).containsExactly(Lane.PEOPLE)
        assertThat(idx.marks(-500, 0, setOf(Lane.WARS), 10, now = 2026)).isEmpty()
        assertThat(idx.laneCounts()[Lane.PEOPLE]).isEqualTo(1)
        assertThat(idx.laneCounts()[Lane.WARS]).isEqualTo(0)
    }

    @Test fun spreadTakesTheBestKnownFromEachSliceSoQuietCenturiesAreNotBlank() {
        val idx = TimeIndexes(listOf(TimeIndex.open(gtime("top", top), "top")!!))
        // One mark per slice: antiquity, the middle ages and the modern era each keep their best known.
        val marks = idx.spread(listOf(-500, 0, 1500, 2026), emptySet(), perSlice = 1, spans = 0, now = 2026)
        assertThat(marks.map { it.path }).containsExactly("Aristotle", "Battle_of_Hastings", "Ada_Lovelace")
        assertThat(idx.latestYear()).isEqualTo(1961)
    }

    @Test fun findLooksUpAnArticleByPathInItsOwnPackFirst() {
        val history = listOf(listOf(83224L, "Battle_of_Hastings", 0, 1066, null, bit(Lane.WARS), 80))
        val all = TimeIndexes(listOf(TimeIndex.open(gtime("top", top), "top")!!, TimeIndex.open(gtime("history", history), "history")!!))
        assertThat(all.find("history", "Battle_of_Hastings")?.packId).isEqualTo("history")
        assertThat(all.find("medicine", "Ada_Lovelace")?.packId).isEqualTo("top")
        assertThat(all.find("top", "O'Brien's_\"Nowhere\"")).isNull()
    }

    @Test fun searchMatchesEveryWordInTitlesBestKnownFirst() {
        val idx = TimeIndexes(listOf(TimeIndex.open(gtime("top", top), "top")!!))
        assertThat(idx.search("empire", emptySet(), 10).map { it.path }).containsExactly("Roman_Empire")
        assertThat(idx.search("battle hastings", emptySet(), 10).map { it.path }).containsExactly("Battle_of_Hastings")
        assertThat(idx.search("a", emptySet(), 2).map { it.path }).containsExactly("Aristotle", "Roman_Empire").inOrder()
        assertThat(idx.search("100%_x", emptySet(), 10)).isEmpty()
        assertThat(idx.search("lovelace", setOf(Lane.WARS), 10)).isEmpty()
    }

    @Test fun theHistogramCountsByYearAndLane() {
        val h = TimeIndexes(listOf(TimeIndex.open(gtime("top", top), "top")!!)).histogram()
        assertThat(h.count(-1000, 2026, emptySet())).isEqualTo(5)
        assertThat(h.count(1000, 1900, emptySet())).isEqualTo(2)
        assertThat(h.count(-1000, 2026, setOf(Lane.PEOPLE))).isEqualTo(3)
        assertThat(h.count(-1000, 2026, setOf(Lane.LANGUAGES))).isEqualTo(0)
        assertThat(h.laneCounts()[Lane.PEOPLE]).isEqualTo(3)
    }

    @Test fun thePushedDirectoryListsFilesThatExistByZimName() {
        val dir = tmp.newFolder("timeindex")
        File(dir, "top.gtime").writeText("x")
        File(dir, "index.tsv").writeText("top.gtime\twikipedia_en_top\ngone.gtime\twikipedia_en_history\nbad line\n")
        assertThat(TimeIndexDirectory.listed(dir)).containsExactly("wikipedia_en_top", File(dir, "top.gtime"))
        assertThat(TimeIndexDirectory.listed(File(tmp.root, "none"))).isEmpty()
    }

    @Test fun aMissingOrNewerFormatFileIsIgnored() {
        assertThat(TimeIndex.open(File(tmp.root, "nope.gtime"), "x")).isNull()
        assertThat(TimeIndex.open(gtime("future", top, format = 2), "x")).isNull()
        val junk = File(tmp.root, "junk.gtime").apply { writeText("not sqlite") }
        assertThat(TimeIndex.open(junk, "x")).isNull()
    }
}
