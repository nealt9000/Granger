package app.granger.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp") // Tall enough for the whole expanded rail.
class RailRecentsTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private var expanded by mutableStateOf(false)

    private val rail = RailUi(
        tabs = listOf(
            RailTabUi(1L, "Owls", "Birds of the world", 'O', available = true, current = false),
            RailTabUi(2L, "Gone", "A removed pack", 'G', available = false, current = false),
        ),
        recent = listOf(
            RecentItem.Search("cats", at = 3L),
            RecentItem.Chat(7L, "About owls", at = 2L),
            RecentItem.Play("album:Blue", "Blue", at = 1L),
        ),
    )

    private fun shell() = compose.setContent {
        GrangerShell(
            rail = rail,
            expanded = expanded,
            railVisible = true,
            selected = null,
            onToggleExpanded = { expanded = !expanded },
            onEdgeSwipe = {},
            onTool = { calls += "tool:$it" },
            onTab = { calls += "tab:$it" },
            onCloseTab = {},
            onHistory = { calls += "history" },
            onRecentSearch = { calls += "search:$it" },
            onRecentChat = { calls += "chat:$it" },
            onRecentPlay = { calls += "play:${it.key}" },
        ) { Box(Modifier.fillMaxSize()) }
    }

    @Test fun tappingARecentInTheNarrowRailOpensTheFullRailInstead() {
        shell()
        for (description in listOf("Recent search: cats", "Research chat: About owls", "Play again: Blue")) {
            expanded = false
            compose.waitForIdle()
            compose.onNodeWithContentDescription(description).performClick()
            compose.waitForIdle()
            assertThat(expanded).isTrue()
        }
        assertThat(calls).isEmpty()
    }

    /** Bookmark tiles are bare letters here too, and History sits with them: a tap opens the full rail. */
    @Test fun tappingABookmarkOrHistoryInTheNarrowRailOpensTheFullRailInstead() {
        shell()
        for (description in listOf("Owls", "Gone, not available", "History")) {
            expanded = false
            compose.waitForIdle()
            compose.onNodeWithContentDescription(description).performClick()
            compose.waitForIdle()
            assertThat(expanded).isTrue()
        }
        assertThat(calls).isEmpty()
    }

    @Test fun narrowRailToolsStillGoStraightToTheirScreens() {
        shell()
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        assertThat(calls).containsExactly("tool:SEARCH", "tool:SETTINGS").inOrder()
        assertThat(expanded).isFalse()
    }

    @Test fun tappingABookmarkOrHistoryInTheFullRailOpensIt() {
        expanded = true
        shell()
        compose.onNodeWithText("Owls").performClick()
        compose.waitForIdle()
        assertThat(calls).containsExactly("tab:1")
        assertThat(expanded).isFalse()

        expanded = true
        compose.waitForIdle()
        compose.onNodeWithText("History").performClick()
        compose.waitForIdle()
        assertThat(calls).containsExactly("tab:1", "history").inOrder()
        assertThat(expanded).isFalse()
    }

    @Test fun tappingARecentInTheFullRailOpensIt() {
        expanded = true
        shell()
        compose.onNodeWithText("cats").performClick()
        compose.waitForIdle()
        assertThat(calls).containsExactly("search:cats")
        assertThat(expanded).isFalse()

        expanded = true
        compose.waitForIdle()
        compose.onNodeWithText("Blue").performClick()
        compose.waitForIdle()
        assertThat(calls).containsExactly("search:cats", "play:album:Blue").inOrder()
        assertThat(expanded).isFalse()
    }
}
