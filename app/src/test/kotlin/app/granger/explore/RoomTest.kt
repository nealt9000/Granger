package app.granger.explore

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import app.granger.core.library.Facet
import app.granger.core.library.TimeKind
import app.granger.core.library.TimeMark
import app.granger.theme.GrangerTheme
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan EX round 2, D3: a room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h2400dp")
class RoomTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()

    @Test fun aShareIsSaidInTheNearestPlainWords() {
        assertThat(shareOf(64, 66)).isEqualTo(Share.NEARLY_ALL)
        assertThat(shareOf(3, 4)).isEqualTo(Share.THREE_IN_FOUR)
        assertThat(shareOf(2, 3)).isEqualTo(Share.TWO_IN_THREE)
        assertThat(shareOf(1, 2)).isEqualTo(Share.HALF)
        assertThat(shareOf(179, 882)).isEqualTo(Share.ONE_IN_FIVE)
        assertThat(shareOf(1, 10)).isEqualTo(Share.ONE_IN_TEN)
        assertThat(shareOf(4, 9)).isEqualTo(Share.HALF)    // nearer a half than a third
        assertThat(shareOf(54, 882)).isNull()
        assertThat(shareOf(0, 10)).isNull()
        assertThat(shareOf(3, 0)).isNull()
    }

    @Test fun theBusiestCenturyIsCountedByAstronomicalYearAndALaterOneWinsATie() {
        assertThat(busiestCentury(longArrayOf(1903, 1969, 1999, 1835, 2001))).isEqualTo(1900L to 3)
        assertThat(busiestCentury(longArrayOf(1066, 1809))).isEqualTo(1800L to 1)
        assertThat(busiestCentury(longArrayOf(-27, -1, -99))).isEqualTo(-100L to 3)
        assertThat(busiestCentury(longArrayOf())).isNull()
        assertThat(romanCentury(1900)).isEqualTo("XX")
        assertThat(romanCentury(1400)).isEqualTo("XV")
        assertThat(romanCentury(800)).isEqualTo("IX")
        assertThat(romanCentury(-100)).isEmpty()
        // A share is spoken of only with enough dated topics, and a century of our era.
        assertThat(RoomFinding(null, 66, 1900, 64).share).isEqualTo(Share.NEARLY_ALL)
        assertThat(RoomFinding(null, 7, 1900, 7).share).isNull()
        assertThat(RoomFinding(null, 40, -400, 30).share).isNull()
    }

    @Test fun theDoorsMeetAtTheEnds() {
        val rooms = listOf("a", "b", "c")
        assertThat(neighbours(rooms) { it == "a" }).isEqualTo(Triple("c", 1, "b"))
        assertThat(neighbours(rooms) { it == "c" }).isEqualTo(Triple("b", 3, "a"))
        assertThat(neighbours(listOf("a")) { it == "a" }).isEqualTo(Triple(null, 1, null))
        assertThat(neighbours(rooms) { it == "z" }).isEqualTo(Triple(null, 0, null))
    }

    /** The app's list is lenses.json's: every room described, in both languages, word for word. */
    @Test fun everyRoomHasItsDescriptionFromTheLensesFile() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "tools/subjects/lenses.json").isFile }
        val lenses = org.json.JSONObject(File(root, "tools/subjects/lenses.json").readText()).getJSONObject("subjects")
        val rooms = RoomDescriptions { File(root, "app/src/main/assets/${RoomDescriptions.ASSET}").readText() }
        var n = 0
        for (subject in lenses.keys()) {
            val list = lenses.getJSONArray(subject)
            for (i in 0 until list.length()) {
                val lens = list.getJSONObject(i)
                val key = subject + "." + lens.getString("id")
                assertThat(rooms.of(key, "en")).isEqualTo(lens.getString("desc_en"))
                assertThat(rooms.of(key, "es")).isEqualTo(lens.getString("desc_es"))
                n++
            }
        }
        assertThat(n).isEqualTo(108)
        assertThat(rooms.of("history.none", "en")).isNull()
        assertThat(Subject.entries.map { it.id }).containsAtLeastElementsIn(lenses.keys().asSequence().toList())
    }

    private fun mark(title: String, year: Long) = TimeMark(1, "top", title, title, TimeKind.entries.first(), year, 0, null, null, 9, emptySet(), 1)
    private fun a(title: String, year: Long? = null) = ExploreArticle("top", title.replace(' ', '_'), title, mark = year?.let { mark(title, it) })
    private fun facet(id: String, en: String) = Facet("technology", id, en, "")

    @Test fun aRoomTeachesBeforeATapAndEveryWayOnWorks() {
        val ui = SubjectUi(
            Subject.TECHNOLOGY, 882, (1..24).map { a("Topic $it") }, emptyList(), more = true, eras = listOf(Era.C19 to 90, Era.RECENT to 400), dated = 228,
            facet = facet("vehicles", "Cars, planes & ships"),
            room = RoomUi(
                "Everything people have built to carry themselves.", RoomFinding(a("Boat", -900_000), 228, 1900, 171),
                story = listOf(a("Boat", -900_000), a("Trireme", -700), a("Titanic", 1909)),
                overlaps = listOf(
                    RoomOverlap(Subject.TECHNOLOGY, facet("weapons", "Weapons & warfare"), 179, listOf("Aircraft carrier", "F-16", "MiG-21")),
                    RoomOverlap(Subject.PLACES, Facet("places", "landmarks", "Buildings & landmarks", ""), 54, listOf("Titanic")),
                ),
                before = facet("internet", "The internet"), after = facet("space", "Spaceflight"), position = 3, total = 8,
            ),
        )
        val actions = ExploreActions(onOpenArticle = { p, path, _ -> calls += "article:$p/$path" })
        compose.setContent {
            GrangerTheme(night = false) {
                RoomContent(
                    ui, actions,
                    SubjectActions(
                        onMore = { calls += "more" }, onSort = { calls += "sort:${it.name}" }, onEra = { calls += "era:${it?.name}" }, onWander = { calls += "wander" },
                        onOpenExplore = { calls += "explore" }, onOpenSubject = { calls += "subject" },
                        onOpenRoom = { s, f, through -> calls += "room:$s.$f:$through" },
                    ),
                )
            }
        }
        compose.onNodeWithContentDescription("You are here: Cars, planes & ships").assertExists()
        compose.onNodeWithContentDescription("A room in Technology").assertExists()
        compose.onNodeWithText(
            "Everything people have built to carry themselves. 882 topics. The oldest dated here is Boat (", substring = true,
        ).assertExists()
        compose.onNodeWithText("About three in four dated topics are from the 1900s.", substring = true).assertExists()
        compose.onNodeWithContentDescription("The room in 3 plates").assertExists()
        compose.onNodeWithTag(RoomTags.story("Trireme")).assertHasClickAction().performClick()
        compose.onNodeWithTag(RoomTags.view(SubjectSort.LINKED)).assertIsSelected()
        compose.onNodeWithTag(RoomTags.view(SubjectSort.OLDEST)).performClick()
        compose.onNodeWithTag(RoomTags.view(SubjectSort.AZ)).performClick()
        compose.onNodeWithText("Best known first · All eras").assertExists()
        compose.onNodeWithTag(SubjectTags.era(Era.C19)).assertDoesNotExist()
        compose.onNodeWithTag(RoomTags.NARROW).performClick()
        compose.onNodeWithTag(SubjectTags.era(Era.C19)).performClick()
        compose.onNodeWithContentDescription("Topic 1").performClick()
        val list = compose.onNodeWithTag(RoomTags.LIST)
        list.performScrollToNode(hasTestTag(RoomTags.MORE))
        compose.onNodeWithText("Show the next 24 of 882").performClick()
        list.performScrollToNode(hasTestTag(RoomTags.also("places.landmarks")))
        compose.onNodeWithText("About one in five also hang in Weapons & warfare").assertExists()
        compose.onNodeWithText("Technology · Aircraft carrier, F-16, MiG-21", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(RoomTags.also("places.landmarks")).performClick()
        list.performScrollToNode(hasTestTag(SpreadTags.way(1)))
        compose.onNodeWithTag(SpreadTags.way(0)).performClick()
        compose.onNodeWithTag(SpreadTags.way(1)).performClick()
        compose.onNodeWithContentDescription("The room before, The internet").performClick()
        compose.onNodeWithContentDescription("The room after, Spaceflight").performClick()
        compose.onNodeWithText("Explore").performClick()
        assertThat(calls).containsExactly(
            "article:top/Trireme", "sort:OLDEST", "sort:AZ", "era:C19", "article:top/Topic_1", "more", "room:places.landmarks:false", "wander", "subject",
            "room:technology.internet:true", "room:technology.space:true", "explore",
        ).inOrder()
    }

    @Test fun aSmallRoomAloneSaysSoAndHasNoDoors() {
        val ui = SubjectUi(Subject.LANGUAGES, 17, listOf(a("Noun"), a("Adjective")), emptyList(), more = false, facet = Facet("languages", "grammar", "Words & grammar", ""), room = RoomUi(total = 1, position = 1))
        compose.setContent { GrangerTheme(night = false) { RoomContent(ui, ExploreActions()) } }
        compose.onNodeWithContentDescription("A small room in Languages").assertExists()
        compose.onNodeWithText("17 topics.").assertExists()
        compose.onNodeWithTag(SpreadTags.DOOR_AFTER).assertDoesNotExist()
        compose.onNodeWithTag(RoomTags.view(SubjectSort.OLDEST)).assertDoesNotExist()
    }
}
