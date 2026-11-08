package app.granger.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import app.granger.core.library.FeedKind
import app.granger.core.library.HomeFeed
import app.granger.core.library.Lane
import app.granger.core.library.TimeKind
import app.granger.core.library.TimeMark
import app.granger.readiness.ReadinessReport
import app.granger.shell.RailEntry
import app.granger.shell.RailTabUi
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2400dp")
class GamesSectionTest {
    @get:Rule val compose = createComposeRule()

    private fun mark(qid: Long, title: String, y0: Int, kind: TimeKind = TimeKind.POINT) =
        TimeMark(qid, "top", title.replace(' ', '_'), title, kind, y0, 0, null, null, 9, setOf(Lane.EVENTS_POLITICS), 10)

    private val hastings = mark(1, "Battle of Hastings", 1066)
    private val magna = mark(2, "Magna Carta", 1215)
    private val newton = mark(3, "Isaac Newton", 1643, TimeKind.LIFE)

    private class Harness(start: GameUi) { var ui by mutableStateOf(start) }

    private val opened = mutableListOf<String>()
    private val calls = mutableListOf<String>()

    private fun actions(h: Harness) = GameActions(
        onPick = { qid ->
            calls += "pick:$qid"
            val r = h.ui.round as Round.Order
            h.ui = h.ui.copy(round = r.copy(picked = listOf(qid, r.marks.first { it.qid != qid }.qid), revealed = true))
        },
        onNudge = { calls += "nudge:$it" },
        onSubmit = { calls += "submit" },
        onNext = { calls += "next" },
        onChoose = { calls += "choose:$it" },
    )

    private fun home(h: Harness) {
        val tab = RailEntry.Tab(RailTabUi(1L, "Owls", app.granger.UiText.Raw("Birds"), 'O', available = true, current = false), at = 5L)
        val feed = FeedUi(
            HomeFeed(1, "family:wikipedia", FeedKind.RANDOM, "", 0, 0), app.granger.UiText.Raw("Wikipedia"), app.granger.UiText.Raw("Random"),
            Drawn(listOf(Card("Owl", "", CardTarget.Article("top", "Owl")))),
        )
        compose.setContent {
            HomeContent(
                HomeUi(strip = ReadinessReport.EMPTY, continueCards = listOf(tab), feeds = listOf(feed)),
                onReadiness = {}, onOpenTab = {}, onPlay = {},
                onOpenCard = { opened += (it.target as CardTarget.Article).path },
                timeline = TimelineUi(loading = false, marks = listOf(hastings), selected = hastings, now = 2026), timelineActions = TimelineActions(),
                wander = WanderUi(centre = WanderNode("top", "Istanbul", "Istanbul")), wanderActions = WanderActions(),
                game = h.ui, gameActions = actions(h), onOpenGames = { calls += "more" },
            )
        }
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    @Test fun homeGoesContinueTimelinePlayWanderThenFeedsAndReadinessLast() {
        home(Harness(GameUi(loading = false, round = Round.Order(listOf(magna, hastings)), now = 2026)))
        assertThat(top("continue-section")).isLessThan(top("timeline-section"))
        assertThat(top("timeline-section")).isLessThan(top("play-section"))
        assertThat(top("play-section")).isLessThan(top("wander-section"))
        compose.onNodeWithTag("home-list").performScrollToKey("strip")
        assertThat(top("feed-section-1")).isLessThan(top("readiness-section"))
    }

    @Test fun whichCameFirstTapRevealsTheOrderAndEachAnswerOpens() {
        val h = Harness(GameUi(loading = false, round = Round.Order(listOf(magna, hastings)), now = 2026))
        home(h)
        compose.onNodeWithText("Which came first?").assertExists()
        val card = compose.onNodeWithTag("game-card-1").fetchSemanticsNode()
        assertThat(card.config[SemanticsActions.OnClick].label).isEqualTo("Choose as number 1")
        compose.onNodeWithTag("game-card-1").performClick()
        assertThat(calls).containsExactly("pick:1")
        compose.onNodeWithText("Right!").assertExists()
        compose.onNodeWithText("149 years apart").assertExists()
        assertThat(compose.onNodeWithTag("game-answer-2").fetchSemanticsNode().config[SemanticsProperties.StateDescription]).isEqualTo("In the right place")
        compose.onNodeWithTag("game-answer-2").performClick()
        assertThat(opened).containsExactly("Magna_Carta")
        compose.onNodeWithTag("game-next").performClick()
        compose.onNodeWithTag("play-more").performClick()
        assertThat(calls).containsAtLeast("next", "more").inOrder()
    }

    @Test fun guessTheYearAsksAboutABirthNudgesAndSubmitsAndTheCardOnlyOpensAfter() {
        home(Harness(GameUi(loading = false, round = Round.Guess(newton, 1500), now = 2026)))
        compose.onNodeWithText("When was this person born?").assertExists()
        compose.onNodeWithTag("game-guess-card").performClick()
        assertThat(opened).isEmpty()
        compose.onNodeWithContentDescription("Ten years later").performClick()
        compose.onNodeWithContentDescription("One year earlier").performClick()
        compose.onNodeWithTag("game-submit").performClick()
        assertThat(calls).containsExactly("nudge:10", "nudge:-1", "submit").inOrder()
    }

    @Test fun aRevealedGuessSaysHowCloseAndOpens() {
        home(Harness(GameUi(loading = false, round = Round.Guess(newton, 1650, revealed = true), now = 2026)))
        compose.onNodeWithText("Very close").assertExists()
        compose.onNodeWithText("It was 1643 · 7 years off").assertExists()   // the year asked about, not the lifetime
        compose.onNodeWithTag("game-answer-3").performClick()
        assertThat(opened).containsExactly("Isaac_Newton")
    }

    @Test fun playIsHiddenWithoutAnIndex() {
        home(Harness(GameUi(available = false)))
        compose.onNodeWithTag("play-section").assertDoesNotExist()
    }

    @Test fun theGamesScreenSwitchesGameAndCountsRounds() {
        val h = Harness(GameUi(loading = false, kind = GameKind.ORDER, round = Round.Order(listOf(magna, hastings)), played = 12, now = 2026))
        var back = false
        compose.setContent { GamesScreen(h.ui, actions(h), onOpen = {}, onBack = { back = true }) }
        compose.onNodeWithText("12 rounds played on this phone").assertExists()
        compose.onNodeWithTag("games-tab-guess").performClick()
        assertThat(calls).containsExactly("choose:GUESS")
        compose.onNodeWithContentDescription("Back to Home").performClick()
        assertThat(back).isTrue()
    }
}
