package app.granger.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.granger.readiness.Coverage
import app.granger.readiness.DomainCoverage
import app.granger.readiness.ReadinessReport
import app.granger.readiness.Signal
import app.granger.shell.RailEntry
import app.granger.shell.RailTabUi
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val ids = listOf("medicine", "food", "making", "science", "technology", "maps", "reference", "literature", "thought")
    private val report = ReadinessReport(
        domains = ids.mapIndexed { i, id -> DomainCoverage(id, id, if (i == 0) Coverage.BASIC else Coverage.NONE, 0, 100, 400, emptyList()) },
        signals = listOf(Signal.Unsearchable("p", "Owls can't be searched: the pack has no index.")),
        storage = emptyList(),
        needsCatalogRefresh = false,
    )
    private val owls = RailEntry.Tab(RailTabUi(1L, "Owls", "Birds of the world", 'O', available = true, current = false), at = 5L)
    private val blue = RailEntry.Play("album:Blue", "Blue", at = 1L)

    private fun show(ui: HomeUi) = compose.setContent {
        HomeContent(ui, onReadiness = { calls += "readiness" }, onOpenTab = { calls += "tab:$it" }, onPlay = { calls += "play:${it.playKey}" })
    }

    @Test fun theStripShowsNineDotsAndTheFirstSignalAndOpensReadiness() {
        show(HomeUi(strip = report))
        compose.onAllNodes(hasTestTag("strip-dot-medicine"), useUnmergedTree = true).assertCountEquals(1)
        ids.forEach { compose.onAllNodes(hasTestTag("strip-dot-$it"), useUnmergedTree = true).assertCountEquals(1) }
        compose.onNodeWithText("Owls can't be searched: the pack has no index.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Readiness").performClick()
        assertThat(calls).containsExactly("readiness")
    }

    @Test fun continueShowsTabsAndPlaysAndOpensThem() {
        show(HomeUi(strip = report, continueCards = listOf(owls, blue)))
        compose.onNodeWithText("Continue").assertIsDisplayed()
        compose.onNodeWithText("Owls").performClick()
        compose.onNodeWithText("Blue").performClick()
        assertThat(calls).containsExactly("tab:1", "play:album:Blue").inOrder()
    }

    @Test fun anEmptyRailShowsNoContinue() {
        show(HomeUi(strip = report))
        compose.onAllNodes(hasTestTag("continue-row")).assertCountEquals(0)
    }
}
