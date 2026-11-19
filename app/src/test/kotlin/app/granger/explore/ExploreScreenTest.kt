package app.granger.explore

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.granger.pack
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1600dp")
class ExploreScreenTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()

    private val actions = ExploreActions(
        onOpenSubject = { calls += "subject:${it.id}" },
        onOpenArticle = { p, path, _ -> calls += "article:$p/$path" },
        onOpenTimeline = { p, path -> calls += "timeline:$p/$path" },
        onOpenSources = { calls += "sources" },
        onOpenPack = { calls += "pack:$it" },
        onGetMore = { calls += "get" },
    )

    private fun a(path: String) = ExploreArticle("top", path, titleOf(path))

    @Test fun theDoorsAreBySizeAndAPackOnlySubjectNamesItsPacks() {
        val cooking = pack("c", "based.cooking")
        val doors = subjectDoors(
            mapOf("history" to 3_531, "science" to 5_233, "games" to 0),
            { s -> listOf(a(if (s == Subject.SCIENCE) "Physics" else "World_War_II")) },
            listOf(cooking to "food", pack("r", "Wiktionary") to "reference", pack("t", "Top") to null),
        )
        assertThat(doors.map { it.subject }).containsExactly(Subject.SCIENCE, Subject.HISTORY, Subject.FOOD).inOrder()
        assertThat(doors.last().packs).containsExactly(cooking)
        assertThat(doors.last().examples).isEmpty()
        assertThat(Subject.ofDomain("thought")).isEqualTo(Subject.RELIGION)
        assertThat(Subject.ofDomain("reference")).isNull()
    }

    @Test fun bookCollectionsGroupByCollectionUnderEachSubjectTheirBooksCarry() {
        val members = listOf(
            CollectionMember("c:granger-classics", "Granger Classics", listOf("literature", "history")),
            CollectionMember("c:granger-classics", "Granger Classics", listOf("literature")),
            CollectionMember("c:asb-en", "African Storybook · English", listOf("literature", "languages")),
            CollectionMember("c:gutenberg-poetry", "Gutenberg · Poetry", listOf("literature", "literature"), pack = true),
        )
        val by = collectionsBySubject(members)
        assertThat(by.getValue("literature")).containsExactly(
            ExploreCollection("c:granger-classics", "Granger Classics", 2),
            ExploreCollection("c:asb-en", "African Storybook · English", 1),
            ExploreCollection("c:gutenberg-poetry", "Gutenberg · Poetry", 0),
        ).inOrder()
        assertThat(by.getValue("history")).containsExactly(ExploreCollection("c:granger-classics", "Granger Classics", 1))
        // A subject with only a book collection still gets a door, after those with articles.
        val doors = subjectDoors(mapOf("history" to 10), { emptyList() }, emptyList(), by)
        assertThat(doors.map { it.subject }).containsExactly(Subject.HISTORY, Subject.LITERATURE, Subject.LANGUAGES).inOrder()
        assertThat(doors[1].collections.map { it.id }).containsExactly("c:granger-classics", "c:asb-en", "c:gutenberg-poetry").inOrder()
    }

    @Test fun aSubjectPageListsItsBookCollectionsAndOpensThem() {
        val ui = SubjectUi(
            Subject.LITERATURE, 0, emptyList(), emptyList(), more = false,
            collections = listOf(ExploreCollection("c:granger-classics", "Granger Classics", 120), ExploreCollection("c:gp", "Gutenberg · Poetry")),
        )
        val seen = mutableListOf<String>()
        compose.setContent { SubjectPageContent(ui, actions, SubjectActions(onOpenCollection = { seen += it })) }
        compose.onNodeWithContentDescription("Book collections").assertExists()
        compose.onNodeWithText("120 books").assertExists()
        compose.onNodeWithText("Nothing on this subject yet").assertDoesNotExist()
        compose.onNodeWithTag(SubjectTags.collection("c:gp")).performClick()
        assertThat(seen).containsExactly("c:gp")
    }

    @Test fun theLandingIsTheSubjectsAsAMosaicThroughTimeAsBarsAndThreeWaysOn() {
        val doors = listOf(
            SubjectDoor(Subject.SOCIETY, 6_300, emptyList(), emptyList()), SubjectDoor(Subject.PLACES, 5_900, emptyList(), emptyList()),
            SubjectDoor(Subject.SCIENCE, 5_233, emptyList(), emptyList()), SubjectDoor(Subject.ART, 478, emptyList(), emptyList()),
            SubjectDoor(Subject.FOOD, 0, emptyList(), listOf(pack("c", "based.cooking"))),
        )
        val ui = ExploreUi(
            articles = 77_019, doors = doors,
            eras = listOf(EraRow(Era.MIDDLE_AGES, 1_159, listOf(a("Islam"), a("Dante_Alighieri"))), EraRow(Era.RECENT, 14_970, listOf(a("The_Beatles")))),
            sources = listOf("based.cooking", "Best of Wikipedia"), rooms = 31,
        )
        val more = mutableListOf<String>()
        compose.setContent { ExploreContent(ui, ExploreActions(
            onOpenSubject = actions.onOpenSubject, onOpenTimeline = actions.onOpenTimeline, onOpenSources = actions.onOpenSources, onGetMore = actions.onGetMore,
            onOpenGames = { calls += "games" },
        ), onWander = { calls += "wander" }, day = 0) }
        // The head says what the counts say: the three largest, and the smallest beside the largest.
        compose.onNodeWithContentDescription("The library · 5 subjects, 31 rooms").assertExists()
        compose.onNodeWithText("Society & politics, Places and Science & maths are your largest subjects").assertExists()
        compose.onNodeWithText("Art & architecture is the smallest, with 478 topics; Society & politics has 13 times as many. Each picture opens its subject.").assertExists()
        // Each subject is one plate, read with what it holds; a subject with only a pack names it.
        compose.onNodeWithContentDescription("Science & maths, 5,233 topics").assertHasClickAction()
        compose.onNodeWithContentDescription("Food & farming, based.cooking").assertExists()
        compose.onNodeWithTag(ExploreTags.door(Subject.SCIENCE)).performClick()
        // Through time: the fullest era's share heads it; a line opens its era.
        compose.onNodeWithText("Nearly all the dated topics are from 1946 or later").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Middle Ages, 500 – 1500, 1,159 topics").assertExists()
        compose.onNodeWithTag(ExploreTags.era(Era.MIDDLE_AGES)).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag(ExploreTags.TIMELINE).performScrollTo().performClick()
        // Three ways on: Wander, the games, Your sources with what the phone holds.
        compose.onNodeWithText("Wander").performScrollTo().performClick()
        compose.onNodeWithContentDescription("6 games with dates").assertExists()
        compose.onNodeWithText("Games").performScrollTo().performClick()
        compose.onNodeWithContentDescription("77,019 articles · 2 packs").assertExists()
        compose.onNodeWithText("Your sources").performScrollTo().performClick()
        compose.onNodeWithTag(ExploreTags.GET_MORE).performClick()
        compose.onNodeWithTag(ExploreTags.NO_SUBJECTS).assertDoesNotExist()
        compose.onNodeWithTag(ExploreTags.OPEN_SPREAD).assertDoesNotExist()
        assertThat(calls).containsExactly("subject:science", "timeline:top/Islam", "timeline:null/null", "wander", "games", "sources", "get").inOrder()
    }

    @Test fun withoutSubjectsItSaysHowToGetThemAndStillLeadsToTheSources() {
        compose.setContent { ExploreContent(ExploreUi(0, emptyList(), emptyList(), listOf("based.cooking")), actions) }
        compose.onNodeWithTag(ExploreTags.NO_SUBJECTS).assertExists()
        compose.onNodeWithText("Get a Wikipedia").performClick()
        compose.onNodeWithContentDescription("1 pack").assertExists()
        compose.onNodeWithText("Wander").assertDoesNotExist()
        compose.onNodeWithText("Your sources").performClick()
        assertThat(calls).containsExactly("get", "sources").inOrder()
    }

    @Test fun outfitForOfflinesDownloadsShowAsOneLineWithSeeAndDismiss() {
        // Plan OB call 3: Start lands on Explore; the packs' progress is one line, See opens Outfitting.
        val progress = OutfitProgress(26_500_000_000, 1_000_000_000, onSee = { calls += "see" }, onDismiss = { calls += "dismiss" })
        compose.setContent { ExploreContent(ExploreUi(0, emptyList(), emptyList(), emptyList()), actions, outfitting = progress) }
        compose.onNodeWithText("Downloading ${app.granger.formatBytes(26_500_000_000)} for offline").assertIsDisplayed()
        compose.onNodeWithText("See progress").performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        assertThat(calls).containsExactly("see", "dismiss").inOrder()
    }

    @Test fun roomsSeenAreCountedOnTheLandingAndInASubjectAndNotShownWhenTheyArentKept() {
        val doors = listOf(SubjectDoor(Subject.HISTORY, 3_503, emptyList(), emptyList()))
        fun facet(id: String) = app.granger.core.library.Facet("history", id, id, id)
        var landing by androidx.compose.runtime.mutableStateOf(ExploreUi(77_019, doors, emptyList(), emptyList(), rooms = 3,
            roomKeys = setOf("history.war", "history.rulers", "history.states"), seen = setOf("history.war", "history.gone", "art.painting")))
        var subject by androidx.compose.runtime.mutableStateOf(SubjectUi(Subject.HISTORY, 3_503, emptyList(), emptyList(), more = false,
            facets = listOf(FacetCard(facet("war"), 10, emptyList()), FacetCard(facet("rulers"), 8, emptyList())), seen = setOf("history.war", "history.rulers", "art.painting")))
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) { ExploreContent(landing, actions, onEra = { calls += "era:${it.name}" }, day = 0) }
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) { SubjectPageContent(subject, actions) }
            }
        }
        // Only rooms that are there count: one of the library's three; both of the subject's two.
        compose.onNodeWithContentDescription("Rooms you have seen, 1 of 3").assertExists()
        compose.onNodeWithContentDescription("Rooms seen in History, 2 of 2").assertExists()
        // Not kept: no bars.
        landing = landing.copy(seen = null)
        subject = subject.copy(seen = null)
        compose.onNodeWithContentDescription("Rooms you have seen, 1 of 3").assertDoesNotExist()
        compose.onNodeWithContentDescription("Rooms seen in History, 2 of 2").assertDoesNotExist()
    }

    @Test fun theSubjectMosaicIsCutByRuleFromHowManyThereAre() {
        fun sizes(n: Int) = doorRows(n).map { it.name }
        // Seventeen: wide and narrow, three, narrow and wide, three, round once more, and the last two side by side.
        assertThat(sizes(17)).containsExactly("WIDE_FIRST", "THREE", "WIDE_LAST", "THREE", "WIDE_FIRST", "THREE", "PAIR").inOrder()
        assertThat(sizes(4)).containsExactly("WIDE_FIRST", "PAIR").inOrder()
        assertThat(sizes(3)).containsExactly("WIDE_FIRST", "SINGLE").inOrder()
        assertThat(sizes(1)).containsExactly("SINGLE")
        assertThat(sizes(0)).isEmpty()
        for (n in 0..40) assertThat(doorRows(n).sumOf { it.size }).isEqualTo(n)
        // A spread's two figures come from different parts of the article; the rest stand by in order.
        assertThat(figureOrder(12).take(4)).containsExactly(4, 8, 0, 1).inOrder()
        assertThat(figureOrder(2)).containsExactly(0, 1).inOrder()
        assertThat(figureOrder(0)).isEmpty()
        // A caption is one sentence, without the article's footnote marks; a figure that is the lead picture again is left out.
        assertThat(captionOf("Total confirmed cases of COVID-19 per million people[62]")).isEqualTo("Total confirmed cases of COVID-19 per million people")
        assertThat(captionOf("Two-dimensional space depicted in spacetime. The past and future light cones are absolute.")).isEqualTo("Two-dimensional space depicted in spacetime")
        assertThat(samePicture("/c/abc.w.jpg", "/c/abc.jpg")).isTrue()
        assertThat(samePicture("/c/abc.jpg", "/c/abd.jpg")).isFalse()
        assertThat(samePicture(null, "/c/abd.jpg")).isFalse()
        // The fullest era's share, when enough is dated to say one.
        val eras = listOf(EraRow(Era.C19, 30, emptyList()), EraRow(Era.RECENT, 70, emptyList()))
        assertThat(busiestEra(eras)).isEqualTo(eras[1] to Share.TWO_IN_THREE)
        assertThat(busiestEra(listOf(EraRow(Era.C19, 3, emptyList())))).isNull()
        assertThat(busiestEra(emptyList())).isNull()
    }

    @Test fun aSubjectPageWithoutSubCategoriesListsItsBestKnownAndItsPacksAndSeesAll() {
        val seen = mutableListOf<String?>()
        val ui = SubjectUi(Subject.FOOD, 658, listOf(a("Pizza"), a("Coffee")), listOf(pack("c", "based.cooking")), more = true)
        compose.setContent { SubjectPageContent(ui, actions, SubjectActions(onOpenFacet = { seen += it })) }
        compose.onNodeWithContentDescription("A subject").assertExists()
        compose.onNodeWithText("Coffee").performClick()
        compose.onNodeWithText("See all 658 topics").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(SubjectTags.SEE_ALL).performScrollTo().performClick()
        compose.onNodeWithText("based.cooking").performScrollTo().performClick()
        assertThat(calls).containsExactly("article:top/Coffee", "pack:c").inOrder()
        assertThat(seen).containsExactly(null)
    }

    @Test fun aSubjectIsItsOpeningPlatesItsRoomsInPairsEachWithASentenceItsBooksAndItsDoors() {
        fun facet(id: String, en: String) = app.granger.core.library.Facet("history", id, en, en)
        val boat = app.granger.core.library.TimeMark(35872L, "top", "Boat", "Boat", app.granger.core.library.TimeKind.POINT, -3000, 0, null, null, 9, emptySet(), 300)
        val cards = listOf(
            // Dated: the room's own finding. Nothing dated: what the room is. Dates not read yet: nothing said.
            FacetCard(facet("war", "Wars & battles"), 954, listOf(a("World_War_II")), finding = RoomFinding(boat.toExplore(), 400, 1900, 300), told = true),
            FacetCard(facet("rulers", "Rulers & royalty"), 636, listOf(a("Augustus")), description = "Kings, queens, emperors and the houses they belonged to.", told = true),
            FacetCard(facet("states", "Empires & kingdoms"), 416, listOf(a("Roman_Empire")), description = "States that rose and fell."),
            FacetCard(facet("turning", "Periods & turning points"), 542, listOf(a("Cold_War")), told = true),
            FacetCard(facet("revolutions", "Revolutions & uprisings"), 64, listOf(a("French_Revolution")), told = true),
        )
        val ui = SubjectUi(
            Subject.HISTORY, 3_531, emptyList(), emptyList(), more = true, dated = 2_900, datedMain = 2_000, facets = cards,
            hero = ArticleLook(null, "History is the systematic study of the past."),
            books = listOf(ExploreBook("gc-g", "Decline and Fall", null, 2277L)),
            opening = listOf(a("History"), a("World_War_II"), a("Roman_Empire")),
            finding = RoomFinding(null, 2_900, 1900, 1_500),
            doors = SubjectDoors(Subject.PLACES, Subject.SCIENCE, 3, 17),
        )
        val seen = mutableListOf<String>()
        compose.setContent {
            app.granger.theme.GrangerTheme(night = false) {
                SubjectPageContent(ui, actions, SubjectActions(onWander = { seen += "wander" }, onQuiz = { seen += "quiz" },
                    onOpenFacet = { seen += "facet:$it" }, onOpenBook = { seen += "book:$it" }, onOpenExplore = { seen += "explore" }, onOpenDoor = { seen += "door:$it" }))
            }
        }
        // The head: where the reader is, what this is, its own article's sentence, and the plates' captions.
        compose.onNodeWithContentDescription("You are here: History").assertExists()
        compose.onNodeWithContentDescription("A subject · 5 rooms").assertExists()
        compose.onNodeWithText("History is the systematic study of the past.").assertExists()
        // With no picture a numbered plate is its word, and the caption under the group names it again.
        compose.onAllNodesWithText("Roman Empire", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithTag(SubjectTags.opening("World_War_II")).assertHasClickAction().performClick()
        // The heading over the rooms states what the subject's dates say; each room, what its own say.
        compose.onNodeWithText("5 rooms. About half the dated topics are from the 1900s.").performScrollTo().assertExists()
        compose.onNodeWithText("Oldest dated: Boat", substring = true).assertExists()
        compose.onNodeWithText("About three in four the dated topics", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Kings, queens, emperors and the houses they belonged to.").assertExists()
        compose.onNodeWithText("States that rose and fell.").assertDoesNotExist()
        // A room's plate is one button: its name and how much it holds.
        compose.onNodeWithContentDescription("Wars & battles, 954 topics").assertHasClickAction()
        compose.onNodeWithTag(SubjectTags.WANDER).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag(SubjectTags.QUIZ).performClick()
        compose.onNodeWithTag(SubjectTags.facet("war")).performClick()
        compose.onNodeWithTag(SubjectTags.facet("states")).performScrollTo().performClick()
        compose.onNodeWithTag(SubjectTags.facet("revolutions")).performScrollTo().performClick()
        compose.onNodeWithContentDescription("In your books (1)").performScrollTo().assertExists()
        compose.onNodeWithTag(SubjectTags.book("gc-g")).performScrollTo().performClick()
        // The trail leads back, and the doors lead to the subjects either side.
        compose.onNodeWithText("Explore").performClick()
        compose.onNodeWithContentDescription("The subject before, Places").performClick()
        compose.onNodeWithContentDescription("The subject after, Science & maths").performClick()
        compose.onNodeWithContentDescription("Subject 3\u00A0of\u00A017").assertExists()
        assertThat(seen).containsExactly("wander", "quiz", "facet:war", "facet:states", "facet:revolutions", "book:gc-g", "explore", "door:places", "door:science").inOrder()
        assertThat(calls).containsExactly("article:top/World_War_II")
    }

    @Test fun theWholeSubjectsListingIsARoomsSpreadWithoutARoomsOwnParts() {
        val ui = SubjectUi(Subject.HISTORY, 3_531, listOf(a("Napoleon"), a("Augustus"), a("Cold_War")), emptyList(), more = true, eras = listOf(Era.ANCIENT to 12, Era.C19 to 340), dated = 352)
        val seen = mutableListOf<String>()
        compose.setContent {
            app.granger.theme.GrangerTheme(night = false) {
                RoomContent(ui, actions, SubjectActions(onMore = { seen += "more" }, onSort = { seen += "sort:${it.name}" }, onOpenSubject = { seen += "subject" }))
            }
        }
        compose.onNodeWithContentDescription("You are here: All of History").assertExists()
        compose.onNodeWithText("3,531 topics.").assertExists()
        compose.onNodeWithTag(SpreadTags.DOOR_BEFORE).assertDoesNotExist()
        compose.onNodeWithTag(RoomTags.view(SubjectSort.AZ)).performClick()
        compose.onNodeWithContentDescription("Augustus").performClick()
        compose.onNodeWithTag(RoomTags.MORE).performScrollTo().performClick()
        // The way on is back to the subject's page, where its rooms are.
        compose.onNodeWithText("Its rooms".uppercase(), useUnmergedTree = true).performScrollTo().assertExists()
        compose.onNodeWithTag(SpreadTags.way(1)).performClick()
        assertThat(seen).containsExactly("sort:AZ", "more", "subject").inOrder()
        assertThat(calls).containsExactly("article:top/Augustus")
    }

    @Test fun todaysTopicStepsThroughItsSubjectsBestKnownSoDaysDontRepeat() {
        val pool = (1..60).map { a("T$it") }
        // Eight doors: day 0 and day 8 are the same subject, a different topic.
        assertThat(featuredCandidates(0, 8, pool).first().title).isEqualTo("T1")
        assertThat(featuredCandidates(8, 8, pool).first().title).isEqualTo("T2")
        assertThat(featuredCandidates(8, 8, pool).map { it.title }.take(3)).containsExactly("T2", "T3", "T4").inOrder()
        // A full turn of the pool comes back only after doors × pool days.
        assertThat(featuredCandidates(8L * 60, 8, pool).first().title).isEqualTo("T1")
        assertThat(featuredCandidates(0, 0, pool)).isEmpty()
    }

    @Test fun theLandingLeadsWithTodaysSpreadItsPlatesCaptionsAndWhatItsPageHolds() {
        val technology = SubjectDoor(Subject.TECHNOLOGY, 4_611, emptyList(), emptyList())
        val airplane = a("Airplane").copy(picture = "/none/airplane.jpg")
        val topic = FeaturedTopic(
            Subject.TECHNOLOGY, 4_611, airplane, listOf(airplane), "An airplane is a fixed-wing aircraft.",
            room = app.granger.core.library.Facet("technology", "transport", "Cars, planes & ships", "Autos, aviones y barcos"),
            figures = listOf(SpreadFigure("/none/flyer.jpg", "The Wright Flyer's first flight on 17 December 1903"), SpreadFigure("/none/concorde.jpg", "The Concorde supersonic transport aircraft")),
            kinds = 72, people = 12, chapters = 5,
        )
        var another = 0
        var ui by androidx.compose.runtime.mutableStateOf(ExploreUi(articles = 77_019, doors = listOf(technology), eras = emptyList(), sources = emptyList(), featured = topic))
        // 1970-01-04 was a Sunday.
        compose.setContent { ExploreContent(ui, actions, onAnother = { another++ }, day = 3) }
        compose.onNodeWithContentDescription("Sunday, January 4 · today's spread").assertExists()
        compose.onNodeWithContentDescription("Technology · Cars, planes & ships").assertExists()
        compose.onNodeWithText("An airplane is a fixed-wing aircraft.").assertExists()
        // Three numbered plates; the captions under them carry their words, and each plate opens the spread.
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag(PlateTags.NUMBER), useUnmergedTree = true).assertCountEquals(3)
        compose.onNodeWithText("The Concorde supersonic transport aircraft").assertExists()
        compose.onNodeWithText("On this spread: 72 kinds, 12 people, 5 chapters.").assertExists()
        compose.onNodeWithTag(ExploreTags.spreadPlate(1)).assertHasClickAction().performClick()
        compose.onNodeWithTag(ExploreTags.OPEN_SPREAD).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag(ExploreTags.ANOTHER).assertHeightIsAtLeast(48.dp).performClick()
        assertThat(calls).containsExactly("article:top/Airplane", "article:top/Airplane").inOrder()
        assertThat(another).isEqualTo(1)
        // Another spread is no longer today's; a topic that isn't broad stands alone, with no captions or counts.
        val hole = a("Black_hole")
        ui = ui.copy(featured = FeaturedTopic(Subject.SCIENCE, 5_233, hole, listOf(hole), "A black hole is a region of spacetime."), skipped = 1)
        compose.onNodeWithContentDescription("Another spread").assertExists()
        compose.onNodeWithContentDescription("Science & maths").assertExists()
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag(PlateTags.NUMBER), useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("On this spread", substring = true).assertDoesNotExist()
        compose.onNodeWithTag(ExploreTags.spreadPlate(0)).performClick()
        assertThat(calls.last()).isEqualTo("article:top/Black_hole")
    }

    @Test fun anErasExamplesLeaveOutPlacesAndCreatures() {
        fun m(qid: Long, title: String) = app.granger.core.library.TimeMark(qid, "top", title.replace(' ', '_'), title, app.granger.core.library.TimeKind.POINT, 1000, 0, null, null, 9, emptySet(), 100)
        val subjects = mapOf(142L to listOf("places", "history"), 144L to listOf("nature", "food"), 1L to listOf("history"), 2L to listOf("religion"))
        val picked = eraExamples(listOf(m(142, "France"), m(1, "Battle of Hastings"), m(144, "Dog"), m(2, "Islam"), m(3, "Undated thing")), { subjects[it].orEmpty() })
        assertThat(picked.map { it.title }).containsExactly("Battle of Hastings", "Islam", "Undated thing").inOrder()
    }

    @Test fun theFeatureTakesAnotherOfTheBiggestDoorsEachDayAndItsOpeningSentence() {
        assertThat((0L..7L).map { featuredIndex(it, 17) }).containsExactly(0, 1, 2, 3, 4, 5, 6, 7).inOrder()
        assertThat(featuredIndex(8, 17)).isEqualTo(0)
        assertThat(featuredIndex(5, 3)).isEqualTo(2)
        assertThat(featuredIndex(5, 0)).isEqualTo(-1)
        assertThat(firstSentence("History is the systematic study of the past. It focuses on people.")).isEqualTo("History is the systematic study of the past.")
        assertThat(firstSentence("Founded c. 500 by the Franks. Later it grew.")).isEqualTo("Founded c. 500 by the Franks.")
        assertThat(firstSentence("word ".repeat(60), max = 40)).endsWith("…")
    }

    @Test fun framingCentresAFaceAsFarAsTheCropAllows() {
        // A tall portrait in a wide box: the crop can only move up and down; a face near the top stays near the top.
        val a = Framing.alignment(0.5f, 0.2f, 200, 400, 400f, 200f)
        assertThat(a.horizontalBias).isEqualTo(0f)
        assertThat(a.verticalBias).isWithin(0.01f).of(-0.8f)
        assertThat(Framing.alignment(0.5f, 0.5f, 200, 400, 400f, 200f).verticalBias).isWithin(0.01f).of(0f)
        assertThat(Framing.alignment(0.5f, 0.0f, 200, 400, 400f, 200f).verticalBias).isEqualTo(-1f)
    }
}

