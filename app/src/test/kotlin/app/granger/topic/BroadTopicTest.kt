package app.granger.topic

import androidx.compose.ui.test.junit4.createComposeRule
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
        ),
        people = listOf(301),
    )
    private val paths = mapOf(
        101L to "F-16", 102L to "MiG-21", 103L to "Boeing_747", 201L to "World_War_I", 202L to "Boeing_747_launch", 203L to "Daedalus",
        211L to "Engine", 212L to "Propeller", 213L to "Jet_engine", 301L to "Charles_Lindbergh",
    )
    private val marks = mapOf(
        "World_War_I" to mark(201, "World_War_I", 1914, TimeKind.SPAN, 1918), "Boeing_747_launch" to mark(202, "Boeing_747_launch", 1970),
        "Charles_Lindbergh" to mark(301, "Charles_Lindbergh", 1902, TimeKind.LIFE, 1974),
    )

    private fun build() = runBlocking {
        BroadTopics.build(
            parts,
            resolve = { q -> paths[q]?.let { "w" to it } },
            card = { p, path -> TopicArticle(p, path, path.replace('_', ' '), "", "pic:$path") },
            figure = { image -> if (image == "a/Undated.jpg") null else "fig:$image" },
            markOf = { _, path -> marks[path] },
            now = 2026,
        )!!
    }

    @Test fun theStoryIsTheMostDatedSectionInYearOrderAndTheRestAreChapters() {
        val b = build()
        assertThat(b.under).isEqualTo(72)
        assertThat(b.groups).containsExactly(BroadGroup("Fighter aircraft", 2))
        // The uninstalled kind is left out.
        assertThat(b.kinds.map { it.first.path to it.second }).containsExactly("F-16" to 0, "MiG-21" to 0, "Boeing_747" to -1).inOrder()
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
    }

    @Test fun yearsComeFromCaptionsAndALongStoryIsSpreadOverItsSpan() {
        assertThat(BroadTopics.yearIn("Otto Lilienthal in mid-flight, Berlin, c. 1895", 2026)).isEqualTo(1895L)
        assertThat(BroadTopics.yearIn("An Airbus A321 on final assembly line 3", 2026)).isNull()
        assertThat(BroadTopics.yearIn("Concept for 2099", 2026)).isNull()
        val s = BroadTopics.spread((1..20).toList(), 8)
        assertThat(s.first()).isEqualTo(1)
        assertThat(s.last()).isEqualTo(20)
        assertThat(s).hasSize(8)
    }

    @Test fun aBroadTopicsPageShowsItsPartsAndTheRingGoesToOne() {
        val b = build()
        val events = mutableListOf<String>()
        val topic = Topic("w", "Airplane", header = TopicHeader("Airplane", "A fixed-wing aircraft.", null), broad = b,
            related = listOf(TopicArticle("w", "Helicopter", "Helicopter", "", null)), relatedBooks = emptyList(), done = true)
        compose.setContent {
            GrangerTheme {
                TopicPage(topic, "Wikipedia", {}, {}, {}, { _, path, _ -> events += path }, { _, _ -> }, { _, _ -> }, {}, null)
            }
        }
        compose.onNodeWithText("A topic to explore").assertExists()
        compose.onNodeWithText("72 kinds · 1 person · 2 chapters").assertExists()
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasTestTag(BroadTags.KINDS))
        compose.onNodeWithText("Kinds · 72").assertExists()
        compose.onNodeWithTag(BroadTags.group(0)).performClick()
        compose.onNodeWithText("Boeing 747").assertDoesNotExist()   // not a fighter
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasTestTag(BroadTags.STORY))
        compose.onNodeWithText("History").assertExists()
        compose.onNodeWithText("World War I").performClick()
        assertThat(events).containsExactly("World_War_I")

        // As a map: the ring's stops are the parts; Propulsion scrolls the page to its chapter.
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasTestTag(BroadTags.MAP))
        compose.onNodeWithTag(BroadTags.MAP).performClick()
        compose.onNodeWithText("Ways into Airplane").assertExists()
        compose.onNodeWithTag(BroadTags.ring(3)).performClick()   // kinds, story, people, Propulsion, close kin
        compose.onNodeWithText("Ways into Airplane").assertDoesNotExist()
        compose.onNodeWithTag(BroadTags.chapter(0)).assertExists()
    }
}
