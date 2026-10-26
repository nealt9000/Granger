package app.granger.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.granger.core.library.FeedKind
import app.granger.core.library.HomeFeed
import app.granger.readiness.Coverage
import app.granger.readiness.DomainCoverage
import app.granger.readiness.ReadinessReport
import app.granger.readiness.Signal
import app.granger.formatBytes
import app.granger.readiness.Source
import app.granger.readiness.StorageRow
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
    private val dayFeed = FeedUi(
        HomeFeed(1, "family:wikipedia", FeedKind.ON_THIS_DAY, "", 0, 0), "Wikipedia",
        Drawn("October 22", listOf(Card("794 – Kyoto founded.", "Events", CardTarget.Article("u1", "October_22", "Events")))),
    )
    private val drawing = FeedUi(HomeFeed(2, "collection:c:granger-classics", FeedKind.RANDOM, "", 1, 0), "Granger Classics", null)

    private val actions = FeedActions(
        onAdd = { scope, kind, query -> calls += "add:${scope.text}:$kind:$query" },
        onRemove = { calls += "remove:$it" },
        onMove = { id, up -> calls += "move:$id:$up" },
    )

    private fun show(ui: HomeUi) = compose.setContent {
        HomeContent(
            ui,
            onReadiness = { calls += "readiness" },
            onOpenTab = { calls += "tab:$it" },
            onPlay = { calls += "play:${it.playKey}" },
            onOpenCard = { calls += "card:${it.title}" },
            actions = actions,
        )
    }

    @Test fun theStripShowsNineDotsAndTheFirstSignalAndOpensReadiness() {
        show(HomeUi(strip = report))
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

    @Test fun theSetupStripShowsProgressAndOpensTheDownloads() {
        compose.setContent {
            HomeContent(HomeUi(strip = report, setup = SetupProgress(2, 5, 1_000, 4_000, 0)), onReadiness = {}, onOpenTab = {}, onPlay = {}, onOpenSetup = { calls += "setup" })
        }
        compose.onNodeWithText("Setting up · 2 of 5 · 1.0 KB of 4.0 KB").performClick()
        assertThat(calls).containsExactly("setup")
    }

    @Test fun theStatusLineCountsWhatThePhoneHoldsNotItsOwnMedia() {
        assertThat(stripStatus(report)).isNull()
        val held = report.copy(
            storage = listOf(
                StorageRow(Source.KIWIX, 11_100_000_000, 40),
                StorageRow(Source.GRANGER, 400_000_000, 3),
                StorageRow(Source.values().last(), 9_000_000_000, 900, referencedOnly = true),
            ),
        )
        assertThat(stripStatus(held)).isEqualTo("> ${formatBytes(11_500_000_000)} here, ready offline.")
        show(HomeUi(strip = held))
        compose.onNodeWithText("> ${formatBytes(11_500_000_000)} here, ready offline.").assertIsDisplayed()
    }

    @Test fun anEmptyRailShowsNoContinue() {
        show(HomeUi(strip = report))
        compose.onAllNodes(hasTestTag("continue-row")).assertCountEquals(0)
    }

    @Test fun feedRowsShowTheirCardsAndOpenThem() {
        show(HomeUi(strip = report, feeds = listOf(dayFeed, drawing)))
        compose.onNodeWithText("October 22").assertIsDisplayed()
        compose.onNodeWithText("794 – Kyoto founded.").performClick()
        assertThat(calls).containsExactly("card:794 – Kyoto founded.")
        // A row still drawing shows its kind and scope and a note.
        compose.onNodeWithText("Random · Granger Classics").assertIsDisplayed()
        compose.onNodeWithText("Drawing…").assertIsDisplayed()
    }

    @Test fun addFeedTakesAScopeAKindAndAQuery() {
        val choices = listOf(ScopeChoice(FeedScope.Library, "Whole library"), ScopeChoice(FeedScope.Pack("wikipedia_en_all"), "Wikipedia"))
        show(HomeUi(strip = report, scopeChoices = choices))
        compose.onNodeWithContentDescription("Add feed").performClick()
        compose.onNodeWithText("Add feed").assertIsDisplayed()
        compose.onNodeWithText("Wikipedia").performClick()
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithText("Search for").performTextInput("owls")
        compose.onNodeWithText("Add").assertIsEnabled().performClick()
        assertThat(calls).containsExactly("add:pack:wikipedia_en_all:SEARCH:owls")
    }
}
