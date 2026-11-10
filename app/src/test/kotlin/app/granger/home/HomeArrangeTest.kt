package app.granger.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.granger.core.library.FeedKind
import app.granger.core.library.HomeFeed
import app.granger.core.library.Lane
import app.granger.core.library.TimeKind
import app.granger.core.library.TimeMark
import app.granger.shell.RailEntry
import app.granger.shell.RailTabUi
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan HY: the user arranges Home; Home opens at its top however late a section arrives. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2400dp")
class HomeArrangeTest {
    @get:Rule val compose = createComposeRule()

    private val hastings = TimeMark(1, "top", "Battle_of_Hastings", "Battle of Hastings", TimeKind.POINT, 1066, 0, null, null, 9, setOf(Lane.WARS), 10)
    private val tab = RailEntry.Tab(RailTabUi(1L, "Owls", app.granger.UiText.Raw("Birds"), 'O', available = true, current = false), at = 5L)
    private val feed = FeedUi(
        HomeFeed(4, "family:wikipedia", FeedKind.RANDOM, "", 0, 0), app.granger.UiText.Raw("Wikipedia"), app.granger.UiText.Raw("Random"),
        Drawn(listOf(Card("Owl", "", CardTarget.Article("top", "Owl")))),
    )

    private class Harness(start: HomeUi) { var ui by mutableStateOf(start) }

    private fun show(h: Harness, saved: MutableList<HomeLayout> = mutableListOf()) = compose.setContent {
        HomeContent(
            h.ui, onReadiness = {}, onOpenTab = {}, onPlay = {},
            timeline = TimelineUi(loading = false, marks = listOf(hastings), selected = hastings, now = 2026),
            game = GameUi(loading = false, round = Round.Guess(hastings, 1500), now = 2026),
            arrangeActions = ArrangeActions(onArrange = { saved += it; h.ui = h.ui.copy(layout = it) }),
        )
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    @Test fun arrangeHidesAndMovesSectionsAndHomeFollows() {
        val h = Harness(HomeUi(continueCards = listOf(tab), feeds = listOf(feed)))
        val saved = mutableListOf<HomeLayout>()
        show(h, saved)
        compose.onNodeWithContentDescription("Arrange Home").performClick()
        compose.onNodeWithTag("arrange-switch-timeline").performClick()
        // TalkBack's Move up, twice: Play above Continue.
        repeat(2) {
            val node = compose.onNodeWithTag("arrange-row-play").fetchSemanticsNode()
            compose.runOnUiThread { node.config[SemanticsActions.CustomActions].first { it.label == "Move up" }.action() }
        }
        compose.onNodeWithTag("arrange-done").performClick()
        val layout = saved.single()
        assertThat(layout.hidden).containsExactly("timeline")
        assertThat(layout.visible(listOf(4)).map { it.token }).containsExactly("play", "continue", "wander", "feed:4", "readiness").inOrder()
        compose.onNodeWithTag("timeline-section").assertDoesNotExist()
        assertThat(top("play-section")).isLessThan(top("continue-section"))
    }

    @Test fun resetPutsBackTheDefaultAndCancelSavesNothing() {
        val h = Harness(HomeUi(continueCards = listOf(tab), layout = HomeLayout(listOf("play", "continue", "timeline", "wander", "feeds", "readiness"), setOf("timeline"))))
        val saved = mutableListOf<HomeLayout>()
        show(h, saved)
        compose.onNodeWithTag("home-arrange").performClick()
        compose.onNodeWithContentDescription("Cancel").performClick()
        assertThat(saved).isEmpty()
        compose.onNodeWithTag("home-arrange").performClick()
        compose.onNodeWithTag("arrange-reset").performClick()
        compose.onNodeWithTag("arrange-done").performClick()
        assertThat(saved.single().visible(emptyList())).isEqualTo(HomeLayout().visible(emptyList()))
        assertThat(top("continue-section")).isLessThan(top("timeline-section"))
    }

    @Test fun continueArrivingAfterTheTimelineStillOpensHomeAtTheTop() {
        val h = Harness(HomeUi())
        show(h)
        compose.onNodeWithTag("timeline-section").assertIsDisplayed()
        h.ui = h.ui.copy(continueCards = listOf(tab))
        compose.waitForIdle()
        compose.onNodeWithTag("continue-section").assertIsDisplayed()
        assertThat(top("continue-section")).isLessThan(top("timeline-section"))
    }
}
