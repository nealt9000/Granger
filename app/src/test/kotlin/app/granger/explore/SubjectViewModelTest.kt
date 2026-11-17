package app.granger.explore

import android.database.sqlite.SQLiteDatabase
import app.granger.MainDispatcherRule
import app.granger.core.library.QidIndex
import app.granger.core.library.QidIndexes
import app.granger.core.library.TimeIndex
import app.granger.core.library.TimeIndexes
import app.granger.pack
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubjectViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())

    // History: Hastings (1066, main), Napoleon (1769–1821, main), Rome (-27, main), London (second; undated).
    private val articles = listOf(
        listOf("Battle_of_Hastings", 83224L, 80), listOf("Napoleon", 517L, 300), listOf("Roman_Empire", 2277L, 250), listOf("London", 84L, 400),
        listOf("Pride_and_Prejudice", 170583L, 90),
    )
    private val subjects = listOf(
        listOf(83224L, 0, "history"), listOf(517L, 0, "history"), listOf(2277L, 0, "history"), listOf(84L, 0, "places"), listOf(84L, 1, "history"),
        listOf(170583L, 0, "literature"),
    )

    private fun gqid(extraFacets: String = ""): File = File(tmp.root, "top.gqid").also { f ->
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE articles(path TEXT PRIMARY KEY, qid INTEGER NOT NULL, rank INTEGER NOT NULL, other TEXT) WITHOUT ROWID")
            db.execSQL("CREATE TABLE links(qid INTEGER NOT NULL, prop TEXT NOT NULL, value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE coords(qid INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL)")
            db.execSQL("CREATE TABLE subjects(qid INTEGER NOT NULL, ord INTEGER NOT NULL, subject TEXT NOT NULL, PRIMARY KEY(qid, ord)) WITHOUT ROWID")
            db.execSQL("INSERT INTO meta VALUES ('kind', 'gqid'), ('schemaVersion', '1'), ('sourceSha256', 'x')")
            articles.forEach { db.execSQL("INSERT INTO articles VALUES (?, ?, ?, NULL)", it.toTypedArray()) }
            subjects.forEach { db.execSQL("INSERT INTO subjects VALUES (?, ?, ?)", it.toTypedArray()) }
            // Lenses (many per item): Hastings and Napoleon in Wars, Napoleon in Rulers, Rome in Empires (codes 0-2).
            db.execSQL("CREATE TABLE facets(facet INTEGER NOT NULL, qid INTEGER NOT NULL, PRIMARY KEY(facet, qid)) WITHOUT ROWID")
            db.execSQL("INSERT INTO facets VALUES (0, 83224), (0, 517), (1, 517), (2, 2277)$extraFacets")
            db.execSQL(
                "INSERT INTO meta VALUES ('facets', ?)",
                arrayOf("""{"history":[{"id":"war","en":"Wars & battles","es":"Guerras","code":0},{"id":"rulers","en":"Rulers","es":"Gobernantes","code":1},""" +
                    """{"id":"states","en":"Empires","es":"Imperios","code":2},{"id":"turning","en":"Turning points","es":"Momentos","code":3}]}"""),
            )
        }
    }

    private fun gtime(): File = File(tmp.root, "top.gtime").also { f ->
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL(
                "CREATE TABLE marks(qid INTEGER NOT NULL, path TEXT NOT NULL, title TEXT NOT NULL, kind INTEGER NOT NULL, y0 INTEGER NOT NULL, " +
                    "md0 INTEGER NOT NULL, y1 INTEGER, md1 INTEGER, prec INTEGER NOT NULL, lanes INTEGER NOT NULL, rank INTEGER NOT NULL, lat REAL, lon REAL)",
            )
            db.execSQL("INSERT INTO meta VALUES ('format', '1'), ('lanes', 'wars')")
            for ((q, path, kind, y0, y1, rank) in listOf(
                listOf(83224L, "Battle_of_Hastings", 0, 1066, null, 80), listOf(517L, "Napoleon", 2, 1769, 1821, 300),
                listOf(2277L, "Roman_Empire", 1, -27, 476, 250), listOf(170583L, "Pride_and_Prejudice", 0, 1813, null, 90),
            )) db.execSQL("INSERT INTO marks VALUES (?, ?, ?, ?, ?, 0, ?, NULL, 9, 0, ?, NULL, NULL)", arrayOf<Any?>(q, path, (path as String).replace('_', ' '), kind, y0, y1, rank))
        }
    }

    private operator fun <T> List<T>.component6() = this[5]

    private fun model(
        facet: String? = null, listing: Boolean = facet != null, look: (String, String) -> ArticleLook? = { _, _ -> null },
        extraFacets: String = "",
    ): SubjectViewModel {
        val qids = QidIndexes(listOf(QidIndex.open(gqid(extraFacets), "top")!!))
        val times = TimeIndexes(listOf(TimeIndex.open(gtime(), "top")!!))
        val sources = ExploreSources({ qids }, { times }, { _, _ -> null }, flowOf(listOf(pack("c", "History pack") to null)), { true }, look = look)
        return SubjectViewModel(Subject.HISTORY, sources, books = {
            listOf(ExploreBook("gc-pp", "Pride and Prejudice", null, 170583L), ExploreBook("gc-g", "Decline and Fall", null, 2277L))
        }, facetId = facet, listing = listing)
    }

    private fun SubjectViewModel.until(test: (SubjectUi) -> Boolean): SubjectUi = runBlocking {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            ui.value?.takeIf(test)?.let { return@runBlocking it }
            Thread.sleep(10)
        }
        error("never: ${ui.value}")
    }

    @Test fun aSubjectCountsItsErasAndListsAnEraOrOldestFirst() {
        val vm = model()
        // Most linked overall: the main-subject articles first, then London, whose History is second.
        val first = vm.until { it.eras.isNotEmpty() && it.books.isNotEmpty() }
        assertThat(first.count).isEqualTo(4)
        assertThat(first.articles.map { it.path }).containsExactly("Napoleon", "Roman_Empire", "Battle_of_Hastings", "London").inOrder()
        assertThat(first.dated).isEqualTo(3)
        // Napoleon counts at forty (1809), in the 1800s.
        assertThat(first.eras).containsExactly(Era.ANCIENT to 1, Era.MIDDLE_AGES to 1, Era.C19 to 1).inOrder()
        assertThat(first.canQuiz).isFalse()   // 3 dated is too few for the games
        // In your books: a book whose work is History (Decline and Fall here); Pride and Prejudice is Literature.
        assertThat(first.books.map { it.bookId }).containsExactly("gc-g")

        vm.sort(SubjectSort.OLDEST)
        val oldest = vm.until { it.sort == SubjectSort.OLDEST && !it.loading }
        assertThat(oldest.articles.map { it.path }).containsExactly("Roman_Empire", "Battle_of_Hastings", "Napoleon").inOrder()
        assertThat(oldest.articles.first().mark?.y0).isEqualTo(-27)

        vm.choose(Era.C19)
        val era = vm.until { it.era == Era.C19 && !it.loading }
        assertThat(era.articles.map { it.path }).containsExactly("Napoleon")
        assertThat(era.more).isFalse()

        // The chosen era again: every era.
        vm.choose(Era.C19)
        vm.sort(SubjectSort.LINKED)
        val back = vm.until { it.era == null && it.sort == SubjectSort.LINKED && !it.loading }
        assertThat(back.articles).hasSize(4)
    }

    @Test fun wanderStartsAtAWellKnownMainSubjectArticle() = runBlocking {
        val vm = model()
        vm.until { true }
        repeat(10) { assertThat(vm.wanderStart()?.path).isIn(listOf("Napoleon", "Roman_Empire", "Battle_of_Hastings")) }
    }

    @Test fun thePageCarriesItsSubCategoriesWithCountsInOrderLeavingEmptyOnesOut() {
        val ui = model().until { it.facets.isNotEmpty() }
        assertThat(ui.facets.map { it.facet.id }).containsExactly("war", "rulers", "states").inOrder()
        assertThat(ui.facets.map { it.count }).containsExactly(2, 1, 1)
        assertThat(ui.facets[0].examples.map { it.title }).containsExactly("Napoleon", "Battle of Hastings").inOrder()
        assertThat(ui.facets[1].examples.map { it.title }).containsExactly("Napoleon")
    }

    @Test fun overlappingLensesTakeTheirPicturesFromDifferentArticlesWhenTheyCan() {
        // Napoleon leads Wars and Rulers (Rome in Rulers too here): Wars shows Napoleon, so Rulers shows Rome; Empires has
        // only Rome, already used, so it shows Rome anyway rather than nothing.
        val ui = model(look = { _, path -> ArticleLook("pic:$path", null) }, extraFacets = ", (1, 2277)")
            .until { f -> f.facets.size == 3 && f.facets.all { it.picture != null } }
        assertThat(ui.facets.map { it.picture }).containsExactly("pic:Napoleon", "pic:Roman_Empire", "pic:Roman_Empire").inOrder()
    }

    @Test fun aSubCategoryListsOnlyItsArticlesWithItsOwnEras() {
        val ui = model("rulers").until { it.articles.isNotEmpty() && it.eras.isNotEmpty() && !it.loading }
        assertThat(ui.facet?.en).isEqualTo("Rulers")
        assertThat(ui.count).isEqualTo(1)
        assertThat(ui.articles.map { it.title }).containsExactly("Napoleon")
        assertThat(ui.eras.map { it.first }).containsExactly(Era.C19)   // a life at about forty: 1809
        assertThat(ui.books).isEmpty()
        assertThat(ui.more).isFalse()
    }

    @Test fun theWholeSubjectsListingHasEveryArticle() {
        val ui = model(listing = true).until { it.articles.size == 4 && !it.loading }
        assertThat(ui.facet).isNull()
        assertThat(ui.articles.first().title).isEqualTo("Napoleon")
    }
}
