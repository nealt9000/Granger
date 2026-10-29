package app.granger.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
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

    private fun shell(showResearch: Boolean = false) = compose.setContent {
        GrangerShell(
            showResearch = showResearch,
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

    /** The four are few enough to know by sight: a tile opens its item (Plan U3b Decision 6). */
    @Test fun tappingATileInTheNarrowRailOpensIt() {
        shell()
        val tiles = listOf(
            "Owls" to "tab:1", "Recent search: cats" to "search:cats", "Ask chat: About owls" to "chat:7", "Play again: Blue" to "play:album:Blue",
        )
        for ((description, call) in tiles) {
            compose.onNodeWithContentDescription(description).performClick()
            compose.waitForIdle()
            assertThat(calls.last()).isEqualTo(call)
        }
        // An unavailable tab says so instead of opening.
        compose.onNodeWithContentDescription("Gone, not available").performClick()
        compose.waitForIdle()
        assertThat(calls).hasSize(tiles.size)
        assertThat(expanded).isFalse()
    }

    @Test fun historyOpensTheHistoryScreen() {
        shell()
        compose.onNodeWithContentDescription("History").performClick()
        compose.waitForIdle()
        assertThat(calls).containsExactly("history")
        assertThat(expanded).isFalse()
    }

    @Test fun tappingTheNarrowRailsBackgroundOpensTheFullRail() {
        rail = RailUi(listOf(cats))
        shell()
        // Below History, above Settings: the rail's empty space.
        compose.onRoot().performTouchInput { click(Offset(28.dp.toPx(), 600.dp.toPx())) }
        compose.waitForIdle()
        assertThat(expanded).isTrue()
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

    /** The Player tool is Listen: your player and your collection, a stack of records (Plan U2 Decision 4). */
    @Test fun listenIsTheToolForThePlayerAndTheCollection() {
        assertThat(RailTool.LISTEN.label).isEqualTo("Listen")
        assertThat(RailTool.entries.map { it.label }).doesNotContain("Player")
        assertThat(RailTool.LISTEN.drawable()).isEqualTo(app.granger.R.drawable.ic_headphones)
        // Home, Library and Listen each have their own mark.
        assertThat(listOf(RailTool.HOME, RailTool.LIBRARY, RailTool.LISTEN).map { it.drawable() }.toSet()).hasSize(3)
        shell()
        compose.onNodeWithContentDescription("Listen").performClick()
        compose.waitForIdle()
        assertThat(calls).containsExactly("tool:LISTEN")
    }

    /** Plan U3c Decision 1: Browse, a globe, between Ask and Library; Settings stays at the foot. */
    @Test fun browseSitsBetweenAskAndLibrary() {
        assertThat(RailTool.entries.map { it.label })
            .containsExactly("Home", "Search", "Ask", "Browse", "Library", "Listen", "Settings").inOrder()
        assertThat(RailTool.BROWSE.shown).isTrue()
        assertThat(RailTool.BROWSE.drawable()).isEqualTo(app.granger.R.drawable.ic_browse)
        assertThat(RailTool.entries.mapNotNull { it.drawable() }.toSet()).hasSize(5)
        shell()
        val top = { d: String -> compose.onNodeWithContentDescription(d).fetchSemanticsNode().boundsInRoot.top }
        // Ask shows only on a phone that can run it; Browse still comes after Search and before the Library.
        assertThat(listOf("Search", "Browse", "Library").map(top)).isInStrictOrder()
        compose.onNodeWithContentDescription("Browse").performClick()
        compose.waitForIdle()
        assertThat(calls).containsExactly("tool:BROWSE")
        expanded = true
        compose.waitForIdle()
        compose.onNodeWithText("Browse").assertExists()
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

    /** Plan U3b, Neal's walkthrough: the tab being read aloud wears a speaker badge and says so, in both rails. */
    @Test fun aTabReadAloudWearsTheBadgeAndSaysSo() {
        rail = RailUi(listOf(owls.copy(tab = owls.tab.copy(readingAloud = true)), cats))
        shell()
        compose.onAllNodesWithTag(READING_BADGE_TAG, useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithContentDescription("Owls, reading aloud").performClick()
        assertThat(calls).containsExactly("tab:1")
        expanded = true
        compose.waitForIdle()
        compose.onNodeWithText("Birds of the world · reading aloud").assertExists()
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

    // Plan U3c Decision 3: the tools and Settings always show; the open items take what's left, dropping from the end.
    private fun tilesShown() = listOf("Owls", "Gone, not available", "Recent search: cats", "Ask chat: About owls")
        .count { compose.onAllNodesWithContentDescription(it).fetchSemanticsNodes().isNotEmpty() }

    private fun assertToolsAndSettingsShow() {
        for (tool in listOf("Home", "Search", "Ask", "Browse", "Library", "Listen", "Settings")) {
            compose.onNodeWithContentDescription(tool).assertExists()
        }
    }

    @Config(qualifiers = "w411dp-h866dp") // A Pixel 6 less its status and navigation bars.
    @Test fun aPixel6RailShowsAllFourAndHistory() {
        rail = RailUi(listOf(owls, gone, cats, chat))
        shell(showResearch = true)
        assertToolsAndSettingsShow()
        assertThat(tilesShown()).isEqualTo(4)
        compose.onNodeWithContentDescription("History").assertExists()
    }

    @Config(qualifiers = "w360dp-h640dp")
    @Test fun a640dpRailDropsTheLastItem() {
        rail = RailUi(listOf(owls, gone, cats, chat))
        shell(showResearch = true)
        assertToolsAndSettingsShow()
        assertThat(tilesShown()).isEqualTo(3)
        compose.onAllNodesWithContentDescription("Ask chat: About owls").assertCountEquals(0)
        compose.onNodeWithContentDescription("History").assertExists()
    }

    @Config(qualifiers = "w411dp-h450dp") // The keyboard up.
    @Test fun withTheKeyboardUpOnlyHistoryKeepsItsPlace() {
        rail = RailUi(listOf(owls, gone, cats, chat))
        shell(showResearch = true)
        assertToolsAndSettingsShow()
        assertThat(tilesShown()).isEqualTo(0)
        compose.onNodeWithContentDescription("History").assertExists()
    }

    @Test fun itemsFitInWholeSlotsWithHistoryFirst() {
        assertThat(narrowRailItemsThatFit(0.dp, 4)).isEqualTo(NarrowRailFit(0, history = false))
        assertThat(narrowRailItemsThatFit(47.dp, 4)).isEqualTo(NarrowRailFit(0, history = false))
        assertThat(narrowRailItemsThatFit(48.dp, 4)).isEqualTo(NarrowRailFit(0, history = true))
        assertThat(narrowRailItemsThatFit(103.dp, 4)).isEqualTo(NarrowRailFit(0, history = true))
        assertThat(narrowRailItemsThatFit(104.dp, 4)).isEqualTo(NarrowRailFit(1, history = true))
        assertThat(narrowRailItemsThatFit(1000.dp, 4)).isEqualTo(NarrowRailFit(4, history = true))
        assertThat(narrowRailItemsThatFit(1000.dp, 2)).isEqualTo(NarrowRailFit(2, history = true))
    }
}
