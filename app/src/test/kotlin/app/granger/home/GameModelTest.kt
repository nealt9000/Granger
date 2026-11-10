package app.granger.home

import app.granger.core.library.Lane
import app.granger.core.library.TimeIndex
import app.granger.core.library.TimeIndexes
import app.granger.core.library.TimeKind
import app.granger.core.library.TimeMark
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GameModelTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A name with no digits, so no title gives its year away: 0 → Event_a, 27 → Event_bb. */
    private fun name(i: Int): String = "Event_" + generateSequence(i) { if (it >= 26) it / 26 - 1 else null }.map { 'a' + it % 26 }.joinToString("").reversed()

    /** A well-known event every 50 years from 2950 BC to 2000, plus two that mustn't come up. */
    private val rows = (0 until 100).map { i -> listOf(i + 1L, name(i), 0, -2950 + i * 50, null, laneBits(Lane.EVENTS_POLITICS), 100) } +
        listOf(
            listOf(500L, "Battle_of_Hastings_1066", 0, 1066, null, laneBits(Lane.WARS), 999),   // year in the title
            listOf(501L, "List_of_popes", 0, 1300, null, laneBits(Lane.PEOPLE), 999),
        )

    /** People every 25 years from 1000 to 1900, each living 50: neighbours overlap, the next but one don't. */
    private val people = (0 until 37).map { i -> listOf(1000L + i, "Person_" + name(i), 2, 1000 + i * 25, 1050 + i * 25, laneBits(Lane.PEOPLE), 100) }

    private fun TestScope.model(file: File? = gtimeFile(tmp.root, "top", rows), store: GameStore = GameStore.Memory(), mixed: Boolean = true, start: GameKind? = GameKind.ORDER): GameModel =
        GameModel(
            scope = this,
            indexes = { TimeIndexes(listOfNotNull(file?.let { TimeIndex.open(it, "top") })) },
            picture = { _, path -> if (path == name(0)) "/thumbs/a.jpg" else null },
            store = store, mixed = mixed, start = start,
            io = StandardTestDispatcher(testScheduler), random = Random(7), now = 2026,
        )

    @Test fun hiddenWhenNoPackHasATimeIndex() = runTest {
        val m = model(file = null)
        m.ensureStarted(); advanceUntilIdle()
        assertThat(m.ui.value.available).isFalse()
    }

    @Test fun dealsTwoCardsAtTheFirstLevelWithinItsGap() = runTest {
        val m = model()
        m.ensureStarted(); advanceUntilIdle()
        val r = m.ui.value.round as Round.Order
        assertThat(r.marks).hasSize(2)
        assertThat(r.gap).isIn(GameModel.GAPS[0] / 3..GameModel.GAPS[0] * 2)
        assertThat(r.marks.map { it.title }).containsNoneOf("Battle of Hastings 1066", "List of popes")
    }

    @Test fun tappingTheEarlierCardRevealsARightAnswerAndNarrowsTheGap() = runTest {
        val store = GameStore.Memory()
        val m = model(store = store)
        m.ensureStarted(); advanceUntilIdle()
        val r = m.ui.value.round as Round.Order
        m.pick(r.answer.first().qid)
        val done = m.ui.value.round as Round.Order
        assertThat(done.revealed).isTrue()
        assertThat(done.right).isTrue()
        assertThat(m.ui.value.played).isEqualTo(1)
        assertThat(m.ui.value.level).isEqualTo(1)
        assertThat(store.played).isEqualTo(1)
    }

    @Test fun aWrongAnswerWidensTheGapAgainButNeverBelowTheFirstLevel() = runTest {
        val store = GameStore.Memory(level = 1)
        val m = model(store = store, mixed = false)
        m.startRun(GameKind.ORDER); advanceUntilIdle()
        val r = m.ui.value.round as Round.Order
        m.pick(r.answer.last().qid)
        assertThat((m.ui.value.round as Round.Order).right).isFalse()
        assertThat(store.level).isEqualTo(0)
        m.next(); advanceUntilIdle()
        val again = m.ui.value.round as Round.Order
        m.pick(again.answer.last().qid)
        assertThat(store.level).isEqualTo(0)
    }

    @Test fun threeCardsFromLevelTwoTappedInOrderAndATapTakesOneBack() = runTest {
        val m = model(store = GameStore.Memory(level = 2))
        m.ensureStarted(); advanceUntilIdle()
        val r = m.ui.value.round as Round.Order
        assertThat(r.marks).hasSize(3)
        val (a, b, c) = r.answer
        m.pick(b.qid)
        m.pick(b.qid)   // taken back
        assertThat((m.ui.value.round as Round.Order).picked).isEmpty()
        m.pick(a.qid)
        m.pick(b.qid)   // the last goes last by itself
        val done = m.ui.value.round as Round.Order
        assertThat(done.picked).containsExactly(a.qid, b.qid, c.qid).inOrder()
        assertThat(done.right).isTrue()
    }

    @Test fun homeDealsAnotherGameEachRoundAndTheGamesScreenWaitsForAChoice() = runTest {
        val home = model(file = gtimeFile(tmp.newFolder(), "top", rows + people), start = null)
        home.ensureStarted(); advanceUntilIdle()
        val kinds = mutableListOf(home.ui.value.round!!.kind)
        repeat(15) { home.next(); advanceUntilIdle(); kinds += home.ui.value.round!!.kind }
        assertThat(kinds.zipWithNext().none { (a, b) -> a == b }).isTrue()
        assertThat(kinds.toSet().size).isAtLeast(5)

        val screen = model(mixed = false, start = null)
        screen.ensureStarted(); advanceUntilIdle()
        assertThat(screen.ui.value.round).isNull()
        assertThat(screen.ui.value.loading).isFalse()
        screen.startRun(GameKind.GUESS); advanceUntilIdle()
        assertThat(screen.ui.value.round).isInstanceOf(Round.Guess::class.java)
        screen.next(); advanceUntilIdle()
        assertThat(screen.ui.value.round).isInstanceOf(Round.Guess::class.java)
    }

    @Test fun aGameWithTooFewArticlesGivesWayToAnotherOnHome() = runTest {
        val m = model(start = GameKind.OVERLAP)   // no lifetimes in these rows
        m.ensureStarted(); advanceUntilIdle()
        assertThat(m.ui.value.round).isNotNull()
        assertThat(m.ui.value.round!!.kind).isNotEqualTo(GameKind.OVERLAP)
        val run = model(file = gtimeFile(tmp.newFolder(), "top", rows), mixed = false)
        run.startRun(GameKind.OVERLAP); advanceUntilIdle()
        assertThat(run.ui.value.round).isNull()   // a run keeps to its game: "not enough" rather than another game
    }

    @Test fun beforeOrAfterPlacesOneMarkAgainstANearbyOne() = runTest {
        val m = model(start = GameKind.BEFORE_AFTER)
        m.ensureStarted(); advanceUntilIdle()
        val r = m.ui.value.round as Round.BeforeAfter
        assertThat(r.gap).isAtLeast(2)
        assertThat(r.revealed).isFalse()
        m.say(r.before)
        val done = m.ui.value.round as Round.BeforeAfter
        assertThat(done.revealed).isTrue()
        assertThat(done.right).isTrue()
        assertThat(m.ui.value.played).isEqualTo(1)
        m.say(!r.before)   // answered: no change
        assertThat((m.ui.value.round as Round.BeforeAfter).right).isTrue()
    }

    @Test fun aliveAtTheSameTimeAsksAboutTwoLifetimesSometimesYesSometimesNo() = runTest {
        val m = model(file = gtimeFile(tmp.newFolder(), "top", rows + people), mixed = false)
        m.startRun(GameKind.OVERLAP); advanceUntilIdle()
        val answers = mutableSetOf<Boolean>()
        repeat(GameModel.RUN) {
            val r = m.ui.value.round as Round.Overlap
            assertThat(listOf(r.a.kind, r.b.kind)).containsExactly(TimeKind.LIFE, TimeKind.LIFE)
            answers += r.overlaps
            m.say(r.overlaps)
            assertThat((m.ui.value.round as Round.Overlap).right).isTrue()
            m.next(); advanceUntilIdle()
        }
        assertThat(answers).containsExactly(true, false)
        assertThat(m.ui.value.run!!.right).isEqualTo(GameModel.RUN)
    }

    @Test fun oddOneOutHasThreeCloseTogetherAndOneFarOff() = runTest {
        val m = model(start = GameKind.ODD_ONE_OUT)
        m.ensureStarted(); advanceUntilIdle()
        val r = m.ui.value.round as Round.OddOneOut
        assertThat(r.marks).hasSize(4)
        val odd = r.marks.single { it.qid == r.odd }
        val rest = r.marks - odd
        val spread = rest.maxOf { it.y0 } - rest.minOf { it.y0 }
        assertThat(rest.minOf { abs(it.y0 - odd.y0) }).isGreaterThan(spread)
        m.pick(rest.first().qid)
        val done = m.ui.value.round as Round.OddOneOut
        assertThat(done.right).isFalse()
        m.pick(odd.qid)   // answered: no change
        assertThat((m.ui.value.round as Round.OddOneOut).picked).isEqualTo(rest.first().qid)
    }

    @Test fun whichCenturyOffersFourInARowWithTheAnswerAndNoneAfterThisOne() = runTest {
        val m = model(start = GameKind.CENTURY)
        m.ensureStarted(); advanceUntilIdle()
        val r = m.ui.value.round as Round.Century
        assertThat(r.choices).hasSize(4)
        assertThat(r.choices).contains(r.answer)
        assertThat(r.choices.zipWithNext().all { (a, b) -> b == a + 1 }).isTrue()
        assertThat(r.choices.max()).isAtMost(centuryOf(2026))
        m.pickCentury(r.answer)
        assertThat((m.ui.value.round as Round.Century).right).isTrue()
    }

    @Test fun centuriesCountFromOneEitherSideOfYearOne() {
        assertThat(centuryOf(1)).isEqualTo(0)
        assertThat(centuryOf(100)).isEqualTo(0)
        assertThat(centuryOf(101)).isEqualTo(1)
        assertThat(centuryOf(2026)).isEqualTo(20)
        assertThat(centuryOf(0)).isEqualTo(-1)      // 1 BC
        assertThat(centuryOf(-99)).isEqualTo(-1)    // 100 BC
        assertThat(centuryOf(-100)).isEqualTo(-2)   // 101 BC
        val en = CenturyNames("%1${'$'}s century", "%1${'$'}s century BC", roman = false, ordinals = listOf("%1${'$'}dst", "%1${'$'}dnd", "%1${'$'}drd", "%1${'$'}dth"))
        assertThat(listOf(1, 2, 3, 4, 11, 12, 13, 21, 22).map(en::number)).containsExactly("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd").inOrder()
        assertThat(en.of(18)).isEqualTo("19th century")
        assertThat(en.of(-5)).isEqualTo("5th century BC")
        val es = CenturyNames("Siglo %1${'$'}s", "Siglo %1${'$'}s a. C.", roman = true, ordinals = List(4) { "" })
        assertThat(es.of(18)).isEqualTo("Siglo XIX")
        assertThat(es.of(-4)).isEqualTo("Siglo IV a. C.")
    }

    @Test fun aRunIsTenRoundsThenTheScoreAndTheBestIsKept() = runTest {
        val store = GameStore.Memory()
        val m = model(store = store, mixed = false)
        m.startRun(GameKind.BEFORE_AFTER); advanceUntilIdle()
        repeat(GameModel.RUN) { i ->
            assertThat(m.ui.value.run!!.number).isEqualTo(i + 1)
            val r = m.ui.value.round as Round.BeforeAfter
            m.say(if (i < 7) r.before else !r.before)
            m.next(); advanceUntilIdle()
        }
        val run = m.ui.value.run!!
        assertThat(run.finished).isTrue()
        assertThat(run.right).isEqualTo(7)
        assertThat(run.newBest).isTrue()
        assertThat(store.best(GameKind.BEFORE_AFTER)).isEqualTo(7)
        assertThat(m.ui.value.best).containsExactly(GameKind.BEFORE_AFTER, 7)
        m.startRun(GameKind.BEFORE_AFTER); advanceUntilIdle()
        assertThat(m.ui.value.run).isEqualTo(GameRun(GameKind.BEFORE_AFTER, best = 7))
        m.endRun()
        assertThat(m.ui.value.run).isNull()
        assertThat(m.ui.value.round).isNull()
    }

    @Test fun continueCarriesHomesUnansweredRoundIntoARunAndHomeDealsAnother() = runTest {
        val home = model()
        home.ensureStarted(); advanceUntilIdle()
        val round = home.ui.value.round as Round.Order
        val screen = model(file = gtimeFile(tmp.newFolder(), "top", rows), mixed = false)
        screen.startRun(GameKind.ORDER, round, mapOf(1L to "/thumbs/x.jpg")); advanceUntilIdle()
        assertThat(screen.ui.value.round).isSameInstanceAs(round)
        assertThat(screen.ui.value.pictures).containsExactly(1L, "/thumbs/x.jpg")
        assertThat(screen.ui.value.run!!.number).isEqualTo(1)
        home.replace(); advanceUntilIdle()
        assertThat(home.ui.value.round!!.kind).isNotEqualTo(GameKind.ORDER)

        // An answered round isn't carried: the run starts with a fresh one of the same game.
        screen.pick(round.answer.first().qid)
        val answered = screen.ui.value.round!!
        screen.startRun(GameKind.ORDER, answered); advanceUntilIdle()
        assertThat(screen.ui.value.round).isNotSameInstanceAs(answered)
        assertThat(screen.ui.value.round!!.revealed).isFalse()
    }

    @Test fun guessMovesNudgesStaysInHistoryAndRevealsHowFarOff() = runTest {
        val m = model(start = GameKind.GUESS)
        m.ensureStarted(); advanceUntilIdle()
        val mark = (m.ui.value.round as Round.Guess).mark
        m.guess(mark.y0 - 20)
        m.nudge(10)
        m.nudge(1)
        assertThat((m.ui.value.round as Round.Guess).guess).isEqualTo(mark.y0 - 9)
        m.submit()
        val done = m.ui.value.round as Round.Guess
        assertThat(done.revealed).isTrue()
        assertThat(done.off).isEqualTo(9)
        assertThat(m.ui.value.played).isEqualTo(1)
        assertThat(m.ui.value.level).isEqualTo(0)   // Guess the year doesn't move Which came first?'s level
        m.guess(5000)   // revealed: no more moving
        assertThat((m.ui.value.round as Round.Guess).guess).isEqualTo(mark.y0 - 9)
    }

    @Test fun roundsPreferWellKnownArticlesAndFallBackWhenThereAreTooFew() = runTest {
        val known = rows.mapIndexed { i, r -> if (i % 2 == 0) r.toMutableList().also { it[6] = 200 } else r.toMutableList().also { it[6] = 5 } }
        val m = model(file = gtimeFile(tmp.newFolder(), "top", known))
        m.ensureStarted(); advanceUntilIdle()
        repeat(6) {
            val marks = m.ui.value.round!!.marks
            assertThat(marks.map { it.rank }.toSet()).containsExactly(200)
            m.next(); advanceUntilIdle()
        }
        val obscure = model(file = gtimeFile(tmp.newFolder(), "top", rows.map { r -> r.toMutableList().also { it[6] = 5 } }))
        obscure.ensureStarted(); advanceUntilIdle()
        assertThat(obscure.ui.value.round).isNotNull()
    }

    @Test fun picturesArriveForTheCardsShown() = runTest {
        val m = model(start = GameKind.GUESS)
        m.ensureStarted(); advanceUntilIdle()
        val r = m.ui.value.round as Round.Guess
        assertThat(m.ui.value.pictures.keys).isEqualTo(if (r.mark.path == name(0)) setOf(r.mark.qid) else emptySet<Long>())
    }

    @Test fun closenessScalesWithHowLongAgo() {
        assertThat(closeness(0, 1945, 2026)).isEqualTo(Closeness.SPOT_ON)
        assertThat(closeness(1, 1945, 2026)).isEqualTo(Closeness.VERY_CLOSE)
        assertThat(closeness(5, 1945, 2026)).isEqualTo(Closeness.CLOSE)
        assertThat(closeness(20, 1945, 2026)).isEqualTo(Closeness.OFF)
        assertThat(closeness(20, -499, 2026)).isEqualTo(Closeness.VERY_CLOSE)   // 500 BC: 20 years is near
    }

    @Test fun playableNeedsAYearPrecisionInHistoryAndATitleThatDoesntGiveItAway() {
        fun mark(title: String, y0: Int = 1800, prec: Int = 9) = TimeMark(1, "top", title, title, TimeKind.POINT, y0, 0, null, null, prec, emptySet(), 1)
        assertThat(GameModel.playable(mark("Battle of Waterloo"), 2026)).isTrue()
        assertThat(GameModel.playable(mark("1812 Overture"), 2026)).isFalse()
        assertThat(GameModel.playable(mark("19th century"), 2026)).isFalse()
        assertThat(GameModel.playable(mark("List of kings"), 2026)).isFalse()
        assertThat(GameModel.playable(mark("Pyramids", y0 = -5000), 2026)).isFalse()
        assertThat(GameModel.playable(mark("Renaissance", prec = 7), 2026)).isFalse()
        assertThat(GameModel.playable(mark("Moon base", y0 = 2030), 2026)).isFalse()
    }
}
