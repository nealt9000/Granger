package app.granger.explore

import app.granger.core.library.open
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
        f.delete()    // a test may make more than one model
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
        f.delete()
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

    private val seen = SeenStore.Memory()

    private fun model(
        facet: String? = null, listing: Boolean = facet != null, look: (String, String) -> ArticleLook? = { _, _ -> null },
        extraFacets: String = "", describe: (String, String) -> String? = { _, _ -> null }, picture: (String, String) -> String? = { _, _ -> null },
    ): SubjectViewModel {
        val qids = QidIndexes(listOf(QidIndex.open(gqid(extraFacets), "top")!!))
        val times = TimeIndexes(listOf(TimeIndex.open(gtime(), "top")!!))
        val sources = ExploreSources({ qids }, { times }, picture, flowOf(listOf(pack("c", "History pack") to null)), { true }, look = look, roomDescription = describe, seen = seen)
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

    /** Plan EX round 2, D3: a room's own parts, each from the indexes. */
    @Test fun aRoomCarriesItsDescriptionFindingPlatesThroughTimeOverlapsAndDoors() {
        // Wars now holds Rome too, and Rulers holds all three: the two rooms share three topics. Empires shares one.
        val vm = model(facet = "war", extraFacets = ", (0, 2277), (1, 83224), (1, 2277)", describe = { key, language -> "$key in $language" })
        val ui = vm.until { it.room?.overlaps?.isNotEmpty() == true && !it.loading }
        val room = ui.room!!
        assertThat(room.description).isEqualTo("history.war in ${java.util.Locale.getDefault().language}")
        // Turning points is empty, so it is no room: three rooms, and the ends meet.
        assertThat(room.position to room.total).isEqualTo(1 to 3)
        assertThat(room.before?.id).isEqualTo("states")
        assertThat(room.after?.id).isEqualTo("rulers")
        assertThat(room.finding?.oldest?.path).isEqualTo("Roman_Empire")
        assertThat(room.finding?.dated).isEqualTo(3)
        assertThat(room.finding?.share).isNull()    // three dated topics are too few to speak of a share
        assertThat(room.story.map { it.path }).containsExactly("Roman_Empire", "Battle_of_Hastings", "Napoleon").inOrder()
        assertThat(room.overlaps).containsExactly(
            RoomOverlap(Subject.HISTORY, app.granger.core.library.Facet("history", "rulers", "Rulers", "Gobernantes"), 3, listOf("Napoleon", "Roman Empire", "Battle of Hastings")),
        )
    }

    /** Plan EX round 2, D2: a subject's opening plates, what its dates say, each room's own sentence, and its doors. */
    @Test fun aSubjectCarriesItsOpeningPlatesItsFindingEachRoomsSentenceAndItsDoors() {
        val vm = model(picture = { _, path -> "pic:$path" }, look = { _, path -> ArticleLook("look:$path", null) }, describe = { key, _ -> "About $key" })
        val ui = vm.until { u -> u.facets.size == 3 && u.facets.all { it.told } && u.finding != null }
        // No article of its own here, so its three best known whose main subject it is open the page (London's is Places).
        assertThat(ui.opening.map { it.path }).containsExactly("Napoleon", "Roman_Empire", "Battle_of_Hastings").inOrder()
        assertThat(ui.opening.map { it.picture }).containsExactly("pic:Napoleon", "pic:Roman_Empire", "pic:Battle_of_Hastings").inOrder()
        // Hastings, Napoleon and Rome are dated; London is not.
        assertThat(ui.finding?.dated).isEqualTo(3)
        assertThat(ui.facets.map { it.description }).containsExactly("About history.war", "About history.rulers", "About history.states").inOrder()
        assertThat(ui.facets.map { it.finding?.oldest?.path }).containsExactly("Battle_of_Hastings", "Napoleon", "Roman_Empire").inOrder()
        assertThat(ui.facets.map { it.finding?.dated }).containsExactly(2, 1, 1).inOrder()
        // The library's order, largest first: History (4), then Places and Books & writing (1 each). The ends meet.
        assertThat(ui.doors).isEqualTo(SubjectDoors(Subject.LITERATURE, Subject.PLACES, 1, 3))
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

    @Test fun aToZListsByTitleWithARailOfLettersAndJumps() {
        val vm = model(listing = true)
        vm.until { it.articles.size == 4 && !it.loading }
        vm.sort(SubjectSort.AZ)
        val az = vm.until { it.sort == SubjectSort.AZ && !it.loading && it.letters.isNotEmpty() }
        assertThat(az.articles.map { it.title }).containsExactly("Battle of Hastings", "London", "Napoleon", "Roman Empire").inOrder()
        assertThat(az.letters).containsExactly("B", "L", "N", "R").inOrder()
        assertThat(az.era).isNull()

        vm.jump("N")
        val n = vm.until { it.letter == "N" && !it.loading }
        assertThat(n.articles.map { it.title }).containsExactly("Napoleon", "Roman Empire").inOrder()
        assertThat(n.more).isFalse()

        // An era from A–Z goes back to most linked, the rail gone.
        vm.until { it.eras.isNotEmpty() }
        vm.choose(Era.C19)
        val era = vm.until { it.era == Era.C19 && !it.loading }
        assertThat(era.sort).isEqualTo(SubjectSort.LINKED)
        assertThat(era.letters).isEmpty()
    }

    @Test fun railLettersSetAccentsAndSymbolsAside() {
        assertThat(railLetter("Ángel Falls")).isEqualTo("A")
        assertThat(railLetter("élan")).isEqualTo("E")
        assertThat(railLetter("Œdipus")).isEqualTo("O")
        assertThat(railLetter("1984 (novel)")).isEqualTo("#")
        assertThat(railLetter("\"Weird Al\" Yankovic")).isEqualTo("W")
        val es = alphabetical(listOf("Zaragoza", "Ávila", "Bilbao", "avión").map { ExploreArticle("p", it, it) }, java.util.Locale("es"))
        assertThat(es.map { it.title }).containsExactly("Ávila", "avión", "Bilbao", "Zaragoza").inOrder()
    }

    @Test fun theWholeSubjectsListingHasEveryArticle() {
        val ui = model(listing = true).until { it.articles.size == 4 && !it.loading }
        // A listing has none of the subject page's doors.
        assertThat(ui.doors).isNull()
        assertThat(ui.facet).isNull()
        assertThat(ui.articles.first().title).isEqualTo("Napoleon")
        assertThat(ui.room).isNull()    // the whole subject is no room
    }

    @Test fun openingASubjectOrARoomIsSeeingItButNotTheWholeListingAndThePrefsKeepIt() {
        model().until { !it.roomsPending }
        assertThat(seen.state.value.ids(SeenKind.SUBJECT)).containsExactly("history")
        assertThat(seen.state.value.ids(SeenKind.ROOM)).isEmpty()
        // The whole subject's listing is no room.
        model(listing = true).until { it.articles.isNotEmpty() }
        assertThat(seen.state.value.ids(SeenKind.ROOM)).isEmpty()
        val room = model(facet = "war").until { it.articles.isNotEmpty() }
        assertThat(seen.state.value.ids(SeenKind.ROOM)).containsExactly("history.war")
        assertThat(room.seen).containsExactly("history.war")

        // On the phone it lives in a preferences file: first seen once, read back, cleared without losing the switch.
        val prefs = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("seen-test", 0)
        var now = 100L
        val kept = SeenStore.Prefs(prefs) { now }
        kept.mark(SeenKind.ROOM, "history.war")
        now = 200L
        kept.mark(SeenKind.ROOM, "history.war")
        kept.mark(SeenKind.ERA, "c19")
        val again = SeenStore.Prefs(prefs)
        assertThat(again.state.value.first).containsExactly("room:history.war", 100L, "era:c19", 200L)
        assertThat(again.state.value.ids(SeenKind.ROOM)).containsExactly("history.war")
        again.setOn(false)
        again.mark(SeenKind.ROOM, "history.rulers")
        assertThat(again.state.value.ids(SeenKind.ROOM)).isNull()
        again.clear()
        val last = SeenStore.Prefs(prefs).state.value
        assertThat(last.first).isEmpty()
        assertThat(last.on).isFalse()
    }
}
