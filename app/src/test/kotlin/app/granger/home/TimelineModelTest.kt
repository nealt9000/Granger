package app.granger.home

import android.database.sqlite.SQLiteDatabase
import app.granger.core.library.Lane
import app.granger.core.library.TimeIndex
import app.granger.core.library.TimeIndexes
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Writes a `.gtime` with the build tool's schema: rows of (qid, path, kind, y0, y1, lanes, rank). */
internal fun gtimeFile(dir: File, name: String, rows: List<List<Any?>>): File {
    val f = File(dir, "$name.gtime")
    SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE marks(qid INTEGER NOT NULL, path TEXT NOT NULL, title TEXT NOT NULL, kind INTEGER NOT NULL, y0 INTEGER NOT NULL, " +
                "md0 INTEGER NOT NULL, y1 INTEGER, md1 INTEGER, prec INTEGER NOT NULL, lanes INTEGER NOT NULL, rank INTEGER NOT NULL, lat REAL, lon REAL)",
        )
        db.execSQL("INSERT INTO meta VALUES ('format', '1'), ('lanes', '${Lane.entries.joinToString(",") { it.id }}')")
        for (r in rows) {
            db.execSQL(
                "INSERT INTO marks VALUES (?, ?, ?, ?, ?, 0, ?, NULL, 9, ?, ?, NULL, NULL)",
                arrayOf(r[0], r[1], (r[1] as String).replace('_', ' '), r[2], r[3], r[4], r[5], r[6]),
            )
        }
    }
    return f
}

internal fun laneBits(vararg lanes: Lane): Long = lanes.fold(0L) { m, l -> m or (1L shl l.ordinal) }

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimelineModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val rows = listOf(
        listOf(1L, "Plato", 2, -427, -347, laneBits(Lane.PEOPLE, Lane.PHILOSOPHY_RELIGION), 200),
        listOf(2L, "Roman_Empire", 1, -26, 476, laneBits(Lane.CIVILISATIONS), 250),
        listOf(3L, "Battle_of_Hastings", 0, 1066, null, laneBits(Lane.WARS), 80),
        listOf(4L, "Domesday_Book", 0, 1086, null, laneBits(Lane.EVENTS_POLITICS), 40),
        listOf(5L, "Trinity_test", 0, 1945, null, laneBits(Lane.SCIENCE), 60),
        listOf(6L, "Yalta_Conference", 0, 1945, null, laneBits(Lane.EVENTS_POLITICS), 70),
        listOf(7L, "United_Nations", 0, 1945, null, laneBits(Lane.EVENTS_POLITICS), 150),
        listOf(8L, "Brief_Encounter", 0, 1945, null, laneBits(Lane.FILM_MUSIC), 30),
        listOf(9L, "Marshall_Plan", 0, 1948, null, laneBits(Lane.EVENTS_POLITICS), 90),
    )

    private fun TestScope.model(file: File? = gtimeFile(tmp.root, "top", rows)): TimelineModel {
        val io = StandardTestDispatcher(testScheduler)
        return TimelineModel(
            scope = this,
            indexes = { TimeIndexes(listOfNotNull(file?.let { TimeIndex.open(it, "top") })) },
            picture = { _, path -> if (path == "United_Nations") "/thumbs/un.jpg" else null },
            io = io, random = Random(2), now = 2026, debounceMillis = 10,
        )
    }

    @Test fun startsWithTheLanesThisPhoneHasAndAMarkSelected() = runTest {
        val m = model()
        m.ensureStarted(); advanceUntilIdle()
        val ui = m.ui.value
        assertThat(ui.available).isTrue()
        assertThat(ui.lanes).containsExactly(Lane.PHILOSOPHY_RELIGION, Lane.CIVILISATIONS, Lane.FILM_MUSIC, Lane.SCIENCE, Lane.WARS, Lane.EVENTS_POLITICS, Lane.PEOPLE).inOrder()
        assertThat(ui.marks).hasSize(rows.size)
        assertThat(ui.selected).isNotNull()
    }

    @Test fun itCanOpenOnAGivenArticle() = runTest {
        val f = gtimeFile(tmp.root, "top", rows)
        val m = TimelineModel(
            scope = this, indexes = { TimeIndexes(listOf(TimeIndex.open(f, "top")!!)) }, picture = { _, _ -> null },
            io = StandardTestDispatcher(testScheduler), now = 2026, startAt = "top" to "Battle_of_Hastings",
        )
        m.ensureStarted(); advanceUntilIdle()
        assertThat(m.ui.value.selected?.path).isEqualTo("Battle_of_Hastings")
    }

    @Test fun searchLightsUpMatchesAndStepsThroughThemInTimeOrder() = runTest {
        val m = model()
        m.ensureStarted(); advanceUntilIdle()
        m.search("conference plan"); advanceUntilIdle()
        assertThat(m.ui.value.matches).isEmpty()
        m.search("1945"); advanceUntilIdle()
        m.search("Yalta"); advanceUntilIdle()
        assertThat(m.ui.value.matches.map { it.path }).containsExactly("Yalta_Conference")
        assertThat(m.ui.value.selected?.path).isEqualTo("Yalta_Conference")
        m.search("a"); advanceUntilIdle()
        val inOrder = m.ui.value.matches.sortedWith(TimelineModel.ORDER).map { it.path }
        m.select(m.ui.value.matches.first { it.path == inOrder.first() }); advanceUntilIdle()
        m.step(1); advanceUntilIdle()
        assertThat(m.ui.value.selected?.path).isEqualTo(inOrder[1])
        m.search(""); advanceUntilIdle()
        assertThat(m.ui.value.matches).isEmpty()
    }

    @Test fun withoutAnyIndexTheTimelineIsUnavailable() = runTest {
        val m = model(file = null)
        m.ensureStarted(); advanceUntilIdle()
        assertThat(m.ui.value.available).isFalse()
    }

    @Test fun lanesFilterTheMarks() = runTest {
        val m = model()
        m.ensureStarted(); advanceUntilIdle()
        m.toggle(Lane.EVENTS_POLITICS); advanceUntilIdle()
        assertThat(m.ui.value.marks.map { it.path }).containsExactly("Domesday_Book", "Yalta_Conference", "United_Nations", "Marshall_Plan")
        m.toggle(Lane.WARS); advanceUntilIdle()
        assertThat(m.ui.value.marks.map { it.path }).contains("Battle_of_Hastings")
        m.all(); advanceUntilIdle()
        assertThat(m.ui.value.marks).hasSize(rows.size)
    }

    @Test fun theLensSettlesOnTheNearestBestKnownMarkWithItsPictureAndNearby() = runTest {
        val m = model()
        m.ensureStarted(); advanceUntilIdle()
        m.lensAt(1944.6); advanceUntilIdle()
        val ui = m.ui.value
        assertThat(ui.selected?.path).isEqualTo("United_Nations")
        assertThat(ui.picture).isEqualTo("/thumbs/un.jpg")
        assertThat(ui.nearby.map { it.path }.take(3)).containsExactly("Yalta_Conference", "Trinity_test", "Brief_Encounter").inOrder()
        assertThat(ui.nearby.map { it.path }).doesNotContain("United_Nations")
    }

    @Test fun stepWalksACrowdedYearOneMarkAtATimeThenOnward() = runTest {
        val m = model()
        m.ensureStarted(); advanceUntilIdle()
        m.lensAt(1945.0); advanceUntilIdle()
        val seen = mutableListOf(m.ui.value.selected!!.path)
        repeat(4) { m.step(1); advanceUntilIdle(); seen += m.ui.value.selected!!.path }
        assertThat(seen).containsExactly("United_Nations", "Yalta_Conference", "Trinity_test", "Brief_Encounter", "Marshall_Plan").inOrder()
        m.step(-1); advanceUntilIdle()
        assertThat(m.ui.value.selected?.path).isEqualTo("Brief_Encounter")
    }

    @Test fun steppingOutsideTheWindowMovesTheWindowToFollow() = runTest {
        val m = model()
        m.ensureStarted(); advanceUntilIdle()
        m.show(TimeScale.Window(TimeScale.toAxis(1940.0), TimeScale.toAxis(1950.0))); advanceUntilIdle()
        m.lensAt(1945.0); advanceUntilIdle()
        repeat(4) { m.step(1); advanceUntilIdle() }
        assertThat(m.ui.value.selected?.path).isEqualTo("Marshall_Plan")
        repeat(1) { m.step(-1); advanceUntilIdle() }
        m.select(m.ui.value.marks.first { it.path == "Battle_of_Hastings" }); advanceUntilIdle()
        m.step(1); advanceUntilIdle()
        assertThat(m.ui.value.selected?.path).isEqualTo("Domesday_Book")
        val (a, b) = m.ui.value.window.years()
        assertThat(1086).isIn(a..b)
    }
}
