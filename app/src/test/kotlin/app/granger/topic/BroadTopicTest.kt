package app.granger.topic

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import app.granger.core.library.Lane
import app.granger.core.library.TimeKind
import app.granger.core.library.TimeMark
import app.granger.core.library.TopicParts
import app.granger.theme.GrangerTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BroadTopicTest {
    @get:Rule val compose = createComposeRule()

    private fun mark(qid: Long, path: String, y0: Long, kind: TimeKind = TimeKind.POINT, y1: Long? = null) =
        TimeMark(qid, "w", path, path.replace('_', ' '), kind, y0, 0, y1, null, 9, setOf(Lane.EVENTS_POLITICS), 50)

    // Airplane: two kinds in a group, a History section with two dated figures and two dated links, a Propulsion section
    // with three links and a figure, and a pilot. Items: 1xx kinds, 2xx links, 3xx people; 999 isn't installed.
    private val parts = TopicParts(
        197, "w", 72,
        groups = listOf(TopicParts.Group(127771, "Fighter aircraft", 2)),
        kinds = listOf(TopicParts.Kind(101, 0), TopicParts.Kind(102, 0), TopicParts.Kind(999, -1), TopicParts.Kind(103, -1)),
        sections = listOf(TopicParts.Section("History", "History"), TopicParts.Section("Propulsion", "Propulsion")),
        links = listOf(
            TopicParts.Link(201, 0), TopicParts.Link(202, 0), TopicParts.Link(203, 0),
            TopicParts.Link(211, 1), TopicParts.Link(212, 1), TopicParts.Link(213, 1),
        ),
        figures = listOf(
            TopicParts.Figure("a/Lilienthal.jpg", "Otto Lilienthal in mid-flight, Berlin, c. 1895", 0),
            TopicParts.Figure("a/Flyer.jpg", "The Wright Flyer's first flight on 17 December 1903", 0),
            TopicParts.Figure("a/Undated.jpg", "A glider", 0),
            TopicParts.Figure("a/Engine.jpg", "A jet engine", 1),
            TopicParts.Figure("a/Parts.png", "Major components of an airplane. The wing, the fuselage and the tail.[4]", 1),
        ),
        people = listOf(301),
        inlinks = 86,
    )
    private val paths = mapOf(
        101L to "F-16", 102L to "MiG-21", 103L to "Boeing_747", 201L to "World_War_I", 202L to "Boeing_747_launch", 203L to "Daedalus",
        211L to "Engine", 212L to "Propeller", 213L to "Jet_engine", 301L to "Charles_Lindbergh",
    )
    private val marks = mapOf(
        "World_War_I" to mark(201, "World_War_I", 1914, TimeKind.SPAN, 1918), "Boeing_747_launch" to mark(202, "Boeing_747_launch", 1970),
        "Charles_Lindbergh" to mark(301, "Charles_Lindbergh", 1902, TimeKind.LIFE, 1974),
    )

    private fun build(pictures: Boolean = true) = runBlocking {
        BroadTopics.build(
            parts,
            resolve = { q -> paths[q]?.let { "w" to it } },
            card = { p, path -> TopicArticle(p, path, path.replace('_', ' '), "", "pic:$path") },
            figure = { image -> if (image == "a/Undated.jpg") null else "fig:$image" },
            markOf = { _, path -> marks[path] },
            now = 2026,
            pictures = pictures,
        )!!
    }

    @Test fun theStoryIsTheMostDatedSectionInYearOrderAndTheRestAreChapters() {
        val b = build()
        assertThat(b.under).isEqualTo(72)
        assertThat(b.inlinks).isEqualTo(86)
        assertThat(b.groups).containsExactly(BroadGroup("Fighter aircraft", 2))
        // The kinds by family: the named group with its plates, then the kinds outside it; the uninstalled kind is left out.
        assertThat(b.families.map { it.name }).containsExactly("Fighter aircraft", null).inOrder()
        assertThat(b.families[0].all.map { it.path }).containsExactly("F-16", "MiG-21").inOrder()
        assertThat(b.families[0].plates.map { it.picture }).containsExactly("pic:F-16", "pic:MiG-21").inOrder()
        assertThat(b.families[1].all.map { it.path }).containsExactly("Boeing_747")
        assertThat(b.hasKinds).isTrue()
        val story = b.story!!
        assertThat(story.title).isEqualTo("History")
        assertThat(story.entries.map { it.year }).containsExactly(1895L, 1903L, 1914L, 1970L).inOrder()
        assertThat(story.entries[0].picture).isEqualTo("fig:a/Lilienthal.jpg")
        assertThat(story.entries[2].opens?.path).isEqualTo("World_War_I")
        assertThat(story.entries[2].picture).isEqualTo("pic:World_War_I")   // a linked step takes its article's picture
        assertThat(b.chapters.map { it.title }).containsExactly("Propulsion")
        assertThat(b.chapters[0].links.map { it.path }).containsExactly("Engine", "Propeller", "Jet_engine").inOrder()
        assertThat(b.chapters[0].picture).isEqualTo("fig:a/Engine.jpg")
        assertThat(b.people.single().mark?.y0).isEqualTo(1902L)
        // The diagram is the first drawing outside the story; the opening's figures come from through the article, the
        // diagram and the unreadable one left out, each with its chapter and a caption of one sentence.
        assertThat(b.diagram).isEqualTo(BroadFigure("fig:a/Parts.png", "Major components of an airplane", "Propulsion"))
        assertThat(b.figures.map { it.picture }).containsExactly("fig:a/Flyer.jpg", "fig:a/Engine.jpg", "fig:a/Lilienthal.jpg").inOrder()
        assertThat(b.figures.first().chapter).isEqualTo("History")
    }

    @Test fun countedWithoutPicturesAPageHoldsTheSame() {
        val shown = build()
        val counted = build(pictures = false)
        assertThat(counted.families.map { it.all }).isEqualTo(shown.families.map { it.all })
        assertThat(counted.families.flatMap { it.plates }).isEmpty()
        assertThat(counted.story!!.entries.map { it.year }).isEqualTo(shown.story!!.entries.map { it.year })
        assertThat(counted.chapters.map { it.title }).isEqualTo(shown.chapters.map { it.title })
        assertThat(counted.people.map { it.article.path }).isEqualTo(shown.people.map { it.article.path })
        assertThat(counted.figures).isEmpty()
        assertThat(counted.diagram).isNull()
    }

    @Test fun yearsComeFromCaptionsAndALongStoryIsSpreadOverItsSpan() {
        assertThat(BroadTopics.yearIn("Otto Lilienthal in mid-flight, Berlin, c. 1895", 2026)).isEqualTo(1895L)
        assertThat(BroadTopics.yearIn("An Airbus A321 on final assembly line 3", 2026)).isNull()
        assertThat(BroadTopics.yearIn("Concept for 2099", 2026)).isNull()
        val s = BroadTopics.spread((1..20).toList(), 8)
        assertThat(s.first()).isEqualTo(1)
        assertThat(s.last()).isEqualTo(20)
        assertThat(s).hasSize(8)
        assertThat(BroadTopics.isDrawing("a/Parts.svg.png")).isTrue()
        assertThat(BroadTopics.isDrawing("a/Flyer.JPG.webp")).isFalse()
    }

    @Test fun aBroadTopicIsASpreadItsPartsUnderHeadingsFromTheIndexItsPlaceInTheTrailAndTheDoors() {
        val ship = TopicArticle("w", "Ship", "Ship", "", null)
        val b = build().copy(
            place = BroadPlace(
                "technology", app.granger.core.library.Facet("technology", "transport", "Cars, planes & ships", "Autos"),
                before = TopicArticle("w", "Bicycle", "Bicycle", "", null), after = ship, position = 5, total = 882,
            ),
            beside = listOf(
                BroadColumn(TopicArticle("w", "Airplane", "Airplane", "", null), 72, listOf("Fighter aircraft"), listOf("Charles Lindbergh"), 2, 86),
                BroadColumn(ship, 217, listOf("Cruisers", "Battleships"), listOf("Vasco da Gama"), 8, 74),
                BroadColumn(TopicArticle("w", "Car", "Car", "", null), 201, emptyList(), emptyList(), 11, 366),
            ),
        )
        val events = mutableListOf<String>()
        val topic = Topic("w", "Airplane", header = TopicHeader("Airplane", "A fixed-wing aircraft. It flies.", "pic:lead"), broad = b,
            related = listOf(TopicArticle("w", "Helicopter", "Helicopter", "", null)), relatedBooks = emptyList(), done = true)
        compose.setContent {
            GrangerTheme {
                TopicPage(topic, "Wikipedia", {}, { events += "full" }, {}, { _, path, _ -> events += path }, { _, _ -> }, { _, _ -> }, {}, null,
                    onWander = { events += "wander" }, onOpenPlace = { s, r -> events += "place:$s/$r" })
            }
        }
        fun scrollTo(tag: String) = compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasTestTag(tag))
        // The trail folds past three places: Explore, the whole trail, the room, here.
        compose.onNodeWithText("Cars, planes & ships").performClick()
        compose.onNodeWithText("Explore").performClick()
        // Three numbered plates and their captions; the opening sentence alone.
        compose.onAllNodes(hasTestTag(app.granger.explore.PlateTags.NUMBER), useUnmergedTree = true).assertCountEquals(3)
        compose.onNodeWithText("A fixed-wing aircraft.").assertExists()
        compose.onNodeWithText("The Wright Flyer's first flight on 17 December 1903").assertExists()
        scrollTo(TopicPageTags.READ_FULL)
        compose.onNodeWithTag(TopicPageTags.READ_FULL).performClick()
        // The diagram is plate 4, from its chapter.
        scrollTo(BroadTags.DIAGRAM)
        compose.onNodeWithContentDescription("Plate 4 · from Propulsion").assertExists()
        // Kinds: how many the library holds, the families named; a family opens in full as a list.
        scrollTo(BroadTags.KINDS)
        compose.onNodeWithContentDescription("Kinds · 72 in your library").assertExists()
        compose.onNodeWithText("1 family: Fighter aircraft").assertExists()
        scrollTo(BroadTags.STORY)
        compose.onNodeWithContentDescription("Chapter · History").assertExists()
        compose.onNodeWithText("From 1895 to 1970").assertExists()
        compose.onNodeWithText("World War I").performClick()
        scrollTo(BroadTags.PEOPLE)
        compose.onNodeWithText("1 person stands near it").assertExists()
        compose.onNodeWithContentDescription("Charles Lindbergh, 1902 – 1974").assertHasClickAction()
        // Side by side: the one with the most kinds heads it; a column opens its topic.
        scrollTo(BroadTags.BESIDE)
        compose.onNodeWithText("Ship has the most kinds: 217").assertExists()
        compose.onNodeWithText("Cruisers and Battleships").assertExists()
        compose.onNodeWithTag(BroadTags.column(1)).performClick()
        scrollTo(BroadTags.CHAPTERS)
        compose.onNodeWithTag(BroadTags.link("Propeller")).assertHeightIsAtLeast(48.dp).performClick()
        // Ways on, and the doors to its neighbours in the room.
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasTestTag(app.granger.explore.SpreadTags.way(2)))
        compose.onNodeWithContentDescription("Close in meaning").assertExists()
        compose.onNodeWithContentDescription("From its History chapter").assertExists()
        compose.onNodeWithTag(app.granger.explore.SpreadTags.way(2)).performClick()
        compose.onNodeWithContentDescription("5 of 882").assertExists()
        compose.onNodeWithTag(app.granger.explore.SpreadTags.DOOR_AFTER).performClick()
        assertThat(events).containsExactly("place:technology/transport", "place:null/null", "full", "World_War_I", "Ship", "Propeller", "wander", "Ship").inOrder()
    }

    @Test fun whileABroadTopicsPartsAreReadThePageIsAlreadyASpread() {
        compose.setContent {
            GrangerTheme {
                TopicPage(Topic("w", "Airplane", header = TopicHeader("Airplane", "A fixed-wing aircraft.", null), broadPending = true), "Wikipedia", {}, {}, {}, { _, _, _ -> }, { _, _ -> }, { _, _ -> }, {}, null)
            }
        }
        compose.onNodeWithTag(app.granger.explore.SpreadTags.TRAIL).assertExists()
        compose.onNodeWithText("Read the article").assertExists()
        compose.onNodeWithText("Read the full article").assertDoesNotExist()
    }
}
