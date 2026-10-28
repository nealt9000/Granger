package app.granger.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp") // Tall enough for the whole expanded rail.
class RailTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private var expanded by mutableStateOf(false)

    private val owls = RailEntry.Tab(RailTabUi(1L, "Owls", "Birds of the world", 'O', available = true, current = false), at = 5L, pinned = true)
    private val gone = RailEntry.Tab(RailTabUi(2L, "Gone", "A removed pack", 'G', available = false, current = false), at = 4L)
    private val cats = RailEntry.Search("cats", at = 3L)
    private val chat = RailEntry.Chat(7L, "About owls", at = 2L)
    private val blue = RailEntry.Play("album:Blue", "Blue", at = 1L)

    private var rail by mutableStateOf(RailUi(listOf(owls, gone, cats, chat, blue)))

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
            onHistory = { calls += "history" },
            onSearch = { calls += "search:$it" },
            onChat = { calls += "chat:$it" },
            onPlay = { calls += "play:${it.playKey}" },
            actions = RailEntryActions(
                onPin = { calls += "pin:${it.key}" },
                onUnpin = { calls += "unpin:${it.key}" },
                onRemove = { calls += "remove:${it.key}" },
            ),
        ) { Box(Modifier.fillMaxSize()) }
    }

    /** Every item is a tile here, too little to tell apart: a tap opens the full rail, as does History. */
    @Test fun tappingATileOrHistoryInTheNarrowRailOpensTheFullRailInstead() {
        shell()
        for (description in listOf("Owls", "Gone, not available", "Recent search: cats", "Ask chat: About owls", "Play again: Blue", "History")) {
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
        compose.onNodeWithContentDescription("Home").performClick()
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        assertThat(calls).containsExactly("tool:HOME", "tool:SEARCH", "tool:SETTINGS").inOrder()
        assertThat(expanded).isFalse()
    }

    @Test fun homeIsTheFirstTool() {
        assertThat(RailTool.entries.first()).isEqualTo(RailTool.HOME)
        assertThat(RailTool.HOME.shown).isTrue()
    }

    @Test fun narrowRailMarksOnlyPinnedTilesWithTheFlameDot() {
        shell()
        compose.onAllNodesWithTag(PIN_DOT_TAG, useUnmergedTree = true).assertCountEquals(1)
        val pinned = compose.onNodeWithContentDescription("Owls").fetchSemanticsNode()
        assertThat(pinned.config.getOrNull(SemanticsProperties.StateDescription)).isEqualTo("Pinned")
    }

    @Test fun everyTileAndRowHasTalkBackLabels() {
        shell()
        val labelled = SemanticsMatcher("has click and long-click labels") { node ->
            node.config.getOrNull(SemanticsActions.OnClick)?.label != null &&
                node.config.getOrNull(SemanticsActions.OnLongClick)?.label != null
        }
        for (description in listOf("Owls", "Gone, not available", "Recent search: cats", "Ask chat: About owls", "Play again: Blue")) {
            compose.onNode(hasContentDescription(description) and labelled).assertExists()
        }
        assertThat(compose.onNodeWithContentDescription("Owls").fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
            .isEqualTo("Unpin or remove from rail")
        assertThat(compose.onNodeWithContentDescription("Recent search: cats").fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
            .isEqualTo("Pin to top or remove from rail")

        expanded = true
        compose.waitForIdle()
        for (title in listOf("Birds of the world", "A removed pack · not available", "cats", "About owls", "Blue")) {
            compose.onNode(hasText(title, substring = true) and hasClickAction() and labelled).assertExists()
        }
    }

    @Test fun fullRailShowsOneListWithAPinnedLabelThenHistory() {
        expanded = true
        shell()
        compose.onNodeWithText("Pinned").assertExists()
        compose.onAllNodesWithText("Bookmarks").assertCountEquals(0)
        compose.onAllNodesWithText("Recent").assertCountEquals(0)
        val top = { text: String -> compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top }
        val order = listOf("Pinned", "Owls", "Gone", "cats", "About owls", "Blue", "History").map(top)
        assertThat(order).isInOrder()

        rail = RailUi(listOf(cats))
        compose.waitForIdle()
        compose.onAllNodesWithText("Pinned").assertCountEquals(0)
    }

    @Test fun longPressOffersPinOrUnpinAndRemove() {
        expanded = true
        shell()
        compose.onNodeWithText("cats").performTouchInput { longClick() }
        compose.onNodeWithText("Pin to top").assertExists()
        compose.onAllNodesWithText("Unpin").assertCountEquals(0)
        compose.onNodeWithText("Remove from rail").performClick()
        compose.waitForIdle()
        assertThat(calls).containsExactly("remove:search:cats")

        compose.onNodeWithText("Owls").performTouchInput { longClick() }
        compose.onAllNodesWithText("Pin to top").assertCountEquals(0)
        compose.onNodeWithText("Unpin").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Blue").performTouchInput { longClick() }
        compose.onNodeWithText("Pin to top").performClick()
        compose.waitForIdle()
        assertThat(calls).containsExactly("remove:search:cats", "unpin:tab:1", "pin:play:album:Blue").inOrder()
        // The menu leaves the full rail open.
        assertThat(expanded).isTrue()
    }

    @Test fun longPressInTheNarrowRailOffersTheSameMenu() {
        shell()
        compose.onNodeWithContentDescription("Ask chat: About owls").performTouchInput { longClick() }
        compose.onNodeWithText("Pin to top").performClick()
        compose.waitForIdle()
        assertThat(calls).containsExactly("pin:chat:7")
        assertThat(expanded).isFalse()
    }

    @Test fun tappingAnItemInTheFullRailOpensIt() {
        expanded = true
        shell()
        for ((title, call) in listOf("Owls" to "tab:1", "History" to "history", "cats" to "search:cats", "About owls" to "chat:7", "Blue" to "play:album:Blue")) {
            expanded = true
            compose.waitForIdle()
            compose.onNodeWithText(title).performClick()
            compose.waitForIdle()
            assertThat(calls.last()).isEqualTo(call)
            assertThat(expanded).isFalse()
        }
    }

    @Test fun unavailableTabInTheFullRailDoesNotOpen() {
        expanded = true
        shell()
        compose.onNodeWithText("Gone").performClick()
        compose.waitForIdle()
        assertThat(calls).isEmpty()
    }
}
