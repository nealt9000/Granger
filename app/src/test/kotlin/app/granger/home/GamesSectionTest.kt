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
        onStart = { calls += "start:$it" },
        onEndRun = { calls += "end" },
        onSay = { yes ->
            calls += "say:$yes"
            h.ui = h.ui.copy(round = when (val r = h.ui.round) { is Round.BeforeAfter -> r.copy(said = yes); is Round.Overlap -> r.copy(said = yes); else -> r })
        },
        onCentury = { c ->
            calls += "century:$c"
            h.ui = h.ui.copy(round = (h.ui.round as Round.Century).copy(picked = c))
        },
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

    private val newtonLife = TimeMark(3, "top", "Isaac_Newton", "Isaac Newton", TimeKind.LIFE, 1643, 0, 1727, 0, 9, setOf(Lane.PEOPLE), 10)
    private val bach = TimeMark(4, "top", "J._S._Bach", "J. S. Bach", TimeKind.LIFE, 1685, 0, 1750, 0, 9, setOf(Lane.PEOPLE), 10)

    @Test fun theMoreLinkSaysMoreGames() {
        home(Harness(GameUi(loading = false, round = Round.Order(listOf(magna, hastings)), now = 2026)))
        compose.onNodeWithText("More games").assertExists()
    }

    @Test fun beforeOrAfterShowsTheAnchorsYearAndRevealsTheGap() {
        val h = Harness(GameUi(loading = false, round = Round.BeforeAfter(magna, hastings), now = 2026))
        home(h)
        compose.onNodeWithText("Before or after?").assertExists()
        compose.onNodeWithText("Did this come before or after Battle of Hastings (1066)?").assertExists()
        compose.onNodeWithTag("game-after").performClick()
        assertThat(calls).containsExactly("say:false")
        compose.onNodeWithText("Right!").assertExists()
        compose.onNodeWithText("149 years after").assertExists()
        compose.onNodeWithTag("game-answer-2").performClick()
        assertThat(opened).containsExactly("Magna_Carta")
    }

    @Test fun aliveAtTheSameTimeDrawsBothLifetimes() {
        val h = Harness(GameUi(loading = false, round = Round.Overlap(newtonLife, bach, now = 2026), now = 2026))
        home(h)
        compose.onNodeWithText("Were these two alive at the same time?").assertExists()
        compose.onNodeWithTag("game-yes").performClick()
        compose.onNodeWithText("They shared 42 years").assertExists()
        compose.onNodeWithTag("game-life-strip").assertExists()
    }

    @Test fun oddOneOutMarksTheOddCardAfterATap() {
        val wright = mark(5, "Wright Flyer", 1903)
        val h = Harness(GameUi(loading = false, round = Round.OddOneOut(listOf(hastings, magna, newton, wright), odd = 5), now = 2026))
        val actions = GameActions(onPick = { qid -> h.ui = h.ui.copy(round = (h.ui.round as Round.OddOneOut).copy(picked = qid)) })
        compose.setContent { HomeContent(HomeUi(), onReadiness = {}, onOpenTab = {}, onPlay = {}, game = h.ui, gameActions = actions) }
        compose.onNodeWithTag("game-card-2").performClick()
        compose.onNodeWithText("Not quite").assertExists()
        assertThat(compose.onNodeWithTag("game-answer-5").fetchSemanticsNode().config[SemanticsProperties.StateDescription]).isEqualTo("The odd one out")
    }

    @Test fun whichCenturyNamesTheChoicesAndTheAnswer() {
        val h = Harness(GameUi(loading = false, round = Round.Century(magna, listOf(10, 11, 12, 13)), now = 2026))
        home(h)
        compose.onNodeWithText("13th century").assertExists()
        compose.onNodeWithTag("game-century-12").performClick()
        assertThat(calls).containsExactly("century:12")
        compose.onNodeWithText("It was 1215 · 13th century").assertExists()
    }

    @Test fun theGamesScreenPutsContinueFirstThenEveryGame() {
        val h = Harness(GameUi(loading = false, played = 12, best = mapOf(GameKind.GUESS to 8), now = 2026))
        val carry = GameUi(loading = false, round = Round.Order(listOf(magna, hastings)), now = 2026)
        var back = false
        var continued = false
        compose.setContent { GamesScreen(h.ui, actions(h), onOpen = {}, onBack = { back = true }, carry = carry, onContinue = { continued = true }) }
        assertThat(top("games-continue")).isLessThan(top("games-start-order"))
        compose.onNodeWithText("Finish Home's round, then play on to 10.").assertExists()
        compose.onNodeWithText("Best: 8 of 10").assertExists()
        compose.onNodeWithText("12 rounds played on this phone").assertExists()
        GameKind.entries.forEach { compose.onNodeWithTag("games-start-${it.name.lowercase()}").assertExists() }
        compose.onNodeWithTag("games-continue").performClick()
        assertThat(continued).isTrue()
        compose.onNodeWithTag("games-start-century").performClick()
        assertThat(calls).containsExactly("start:CENTURY")
        compose.onNodeWithContentDescription("Back to Home").performClick()
        assertThat(back).isTrue()
    }

    @Test fun aRunShowsItsProgressAndEndsWithTheScore() {
        val h = Harness(GameUi(loading = false, round = Round.Order(listOf(magna, hastings), picked = listOf(2, 1), revealed = true), run = GameRun(GameKind.ORDER, number = 10, right = 6), now = 2026))
        compose.setContent { GamesScreen(h.ui, actions(h), onOpen = {}, onBack = {}) }
        compose.onNodeWithText("Round 10 of 10 · 6 right").assertExists()
        compose.onNodeWithText("See your score").performClick()
        assertThat(calls).containsExactly("next")
        h.ui = h.ui.copy(run = h.ui.run!!.copy(finished = true, right = 7, best = 7, newBest = true))
        compose.onNodeWithText("7 of 10").assertExists()
        compose.onNodeWithText("A new best!").assertExists()
        compose.onNodeWithTag("games-again").performClick()
        compose.onNodeWithTag("games-all").performClick()
        assertThat(calls).containsExactly("next", "start:ORDER", "end").inOrder()
    }
}
