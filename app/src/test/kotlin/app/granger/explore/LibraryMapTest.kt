package app.granger.explore

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertCountEquals
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class LibraryMapTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tenGoRoundTheMapSixInsideFourOnTheDiagonals() {
        val p = LibraryMapLayout.places(14)
        assertThat(p).hasSize(LibraryMapLayout.MAX)
        assertThat(p.count { it.first == 0 }).isEqualTo(6)
        assertThat(p.first()).isEqualTo(0 to -90.0)                  // the biggest at the top
        assertThat(p.filter { it.first == 1 }.map { it.second }).containsExactly(-135.0, -45.0, 135.0, 45.0)
        // The seven eras go round one ring.
        assertThat(LibraryMapLayout.places(7, ring = 7).all { it.first == 0 }).isTrue()
        // Area follows the count.
        assertThat(LibraryMapLayout.size(39_165.0, 39_165.0)).isEqualTo(LibraryMapLayout.BIG)
        assertThat(LibraryMapLayout.size(0.0, 39_165.0)).isEqualTo(LibraryMapLayout.MIN)
    }

    private fun door(s: Subject, n: Int) = SubjectDoor(s, n, emptyList(), emptyList())

    @Test fun aSubjectStepsInsideAndItsArticlesOpenTheirTopicPages() {
        val calls = mutableListOf<String>()
        val doors = Subject.entries.mapIndexed { i, s -> door(s, 20_000 - i * 1_000) }
        val ui = ExploreUi(146_618, doors, listOf(EraRow(Era.C19, 4_713, listOf(ExploreArticle("top", "Napoleon", "Napoleon")))), listOf("Top"))
        val actions = ExploreActions(
            onOpenSubject = { calls += "subject:${it.id}" },
            onOpenArticle = { p, path, _ -> calls += "article:$p/$path" },
            onOpenTimeline = { p, path -> calls += "timeline:$p/$path" },
        )
        compose.setContent {
            LibraryMap(ui, inside = { c ->
                if (c == MapCentre.OfSubject(Subject.HISTORY)) listOf(ExploreArticle("top", "History", "History"), ExploreArticle("top", "World_War_II", "World War II")) else emptyList()
            }, actions = actions, onClose = { calls += "close" })
        }
        compose.onAllNodesWithTag(LibraryMapTags.SPOT).assertCountEquals(10)
        compose.onNodeWithText("Also").assertExists()                // seven more below
        compose.onNodeWithText("History").performClick()
        compose.waitForIdle()
        // Inside History: its articles, but not the one named like it.
        compose.onAllNodesWithTag(LibraryMapTags.SPOT).assertCountEquals(1)
        compose.onNodeWithText("World War II").performClick()
        compose.onNodeWithTag(LibraryMapTags.OPEN).performClick()
        assertThat(calls).containsExactly("article:top/World_War_II", "subject:history").inOrder()
    }

    @Test fun anErasLongPressOpensTheTimelineAtItsBestKnown() {
        val calls = mutableListOf<String>()
        val ui = ExploreUi(10, listOf(door(Subject.HISTORY, 10)), listOf(EraRow(Era.C19, 4_713, listOf(ExploreArticle("top", "Napoleon", "Napoleon")))), listOf("Top"))
        compose.setContent {
            LibraryMap(ui, inside = { emptyList() }, actions = ExploreActions(onOpenTimeline = { p, path -> calls += "timeline:$p/$path" }), onClose = {})
        }
        compose.onNodeWithTag(LibraryMapTags.TIME).performClick()
        compose.onNodeWithText("The 1800s").performTouchInput { longClick() }
        assertThat(calls).containsExactly("timeline:top/Napoleon")
    }
}
