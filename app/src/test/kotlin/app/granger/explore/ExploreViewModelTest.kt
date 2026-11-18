package app.granger.explore

import android.database.sqlite.SQLiteDatabase
import app.granger.MainDispatcherRule
import app.granger.core.library.QidIndex
import app.granger.core.library.QidIndexes
import app.granger.core.library.TimeIndexes
import app.granger.pack
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExploreViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())

    // Technology: Airplane (broad: kinds, a person, four figures) and Kite; Science: Atom, with no broad topic.
    private fun gqid(): File = File(tmp.root, "top.gqid").also { f ->
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE articles(path TEXT PRIMARY KEY, qid INTEGER NOT NULL, rank INTEGER NOT NULL, other TEXT) WITHOUT ROWID")
            db.execSQL("CREATE TABLE links(qid INTEGER NOT NULL, prop TEXT NOT NULL, value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE coords(qid INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL)")
            db.execSQL("CREATE TABLE subjects(qid INTEGER NOT NULL, ord INTEGER NOT NULL, subject TEXT NOT NULL, PRIMARY KEY(qid, ord)) WITHOUT ROWID")
            db.execSQL("INSERT INTO meta VALUES ('kind', 'gqid'), ('schemaVersion', '1'), ('sourceSha256', 'x')")
            db.execSQL("INSERT INTO articles VALUES ('Airplane', 197, 86, NULL), ('Kite', 42861, 60, NULL), ('Jet_aircraft', 1005, 40, NULL), ('Amelia_Earhart', 3355, 70, NULL), ('Atom', 9121, 90, NULL)")
            db.execSQL("INSERT INTO subjects VALUES (197, 0, 'technology'), (42861, 0, 'technology'), (1005, 0, 'technology'), (3355, 0, 'technology'), (9121, 0, 'science')")
            db.execSQL("CREATE TABLE facets(facet INTEGER NOT NULL, qid INTEGER NOT NULL, PRIMARY KEY(facet, qid)) WITHOUT ROWID")
            db.execSQL("INSERT INTO facets VALUES (1, 197)")
            db.execSQL("INSERT INTO meta VALUES ('facets', ?)", arrayOf("""{"technology":[{"id":"weapons","en":"Weapons","es":"Armas","code":0},{"id":"transport","en":"Cars, planes & ships","es":"Autos","code":1}]}"""))
            db.execSQL("CREATE TABLE topics(qid INTEGER PRIMARY KEY, under INTEGER NOT NULL, inlinks INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE topic_parts(topic INTEGER NOT NULL, part INTEGER NOT NULL, ord INTEGER NOT NULL, item INTEGER, grp INTEGER, text TEXT, note TEXT, PRIMARY KEY(topic, part, ord)) WITHOUT ROWID")
            db.execSQL("INSERT INTO topics VALUES (197, 72, 86)")
            db.execSQL("INSERT INTO topic_parts VALUES (197, 2, 0, 1005, -1, NULL, NULL), (197, 6, 0, 3355, NULL, NULL, NULL), (197, 3, 0, NULL, NULL, 'History', 'History')")
            for (i in 0..3) db.execSQL("INSERT INTO topic_parts VALUES (197, 5, $i, NULL, 0, 'Figure $i', 'img/$i.jpg')")
        }
    }

    private val qids by lazy { QidIndexes(listOf(QidIndex.open(gqid(), "top")!!)) }

    private fun model(day: Long) = ExploreViewModel(
        ExploreSources(
            qids = { qids }, times = { TimeIndexes(emptyList()) },
            picture = { _, _ -> null }, packs = flowOf(listOf(pack("top", "Best of Wikipedia") to null)), isWikipedia = { true },
            look = { _, path -> ArticleLook("/look/$path.jpg", "$path opens. And goes on.") },
            // The second figure isn't in the pack: the next stands in.
            figure = { _, image -> image.takeIf { it != "img/2.jpg" }?.let { "/fig/$it" } },
        ),
        today = { day },
    )

    private fun ExploreViewModel.until(test: (ExploreUi) -> Boolean): ExploreUi {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            ui.value?.takeIf(test)?.let { return it }
            Thread.sleep(10)
        }
        error("never: ${ui.value}")
    }

    @Test fun todaysSpreadIsABroadTopicOfTheDaysSubjectWithTwoFiguresItsRoomAndWhatItsPageHolds() {
        // Technology is the larger door, so day 0 is its day; its one broad topic with enough figures is Airplane.
        val vm = model(day = 0)
        val f = vm.until { it.featured?.kinds == 72 }.featured!!
        assertThat(f.subject).isEqualTo(Subject.TECHNOLOGY)
        assertThat(f.article.path).isEqualTo("Airplane")
        assertThat(f.article.picture).isEqualTo("/look/Airplane.jpg")
        assertThat(f.sentence).isEqualTo("Airplane opens.")
        // Figures a third and two thirds through (1 and 2); 2 is missing, so the next in order (0) stands in.
        assertThat(f.figures).containsExactly(SpreadFigure("/fig/img/1.jpg", "Figure 1"), SpreadFigure("/fig/img/0.jpg", "Figure 0")).inOrder()
        assertThat(f.room?.id).isEqualTo("transport")
        assertThat(f.people).isEqualTo(1)
        assertThat(f.chapters).isEqualTo(0)
        assertThat(vm.ui.value!!.rooms).isEqualTo(2)
        assertThat(vm.ui.value!!.skipped).isEqualTo(0)

        // Another: the next day's spread. Science has no broad topic, so its best known stands alone.
        vm.another()
        val next = vm.until { it.skipped == 1 && it.featured?.article?.picture != null }
        assertThat(next.featured!!.subject).isEqualTo(Subject.SCIENCE)
        assertThat(next.featured!!.article.path).isEqualTo("Atom")
        assertThat(next.featured!!.figures).isEmpty()
        assertThat(next.featured!!.kinds).isEqualTo(0)
        // The doors keep their pictures.
        assertThat(next.doors.map { it.subject }).containsExactly(Subject.TECHNOLOGY, Subject.SCIENCE).inOrder()
    }
}
