package app.granger.home

import app.granger.core.library.Lane
import app.granger.core.library.TimeIndex
import app.granger.core.library.TimeIndexes
import app.granger.core.library.TimeKind
import app.granger.core.library.TimeMark
import com.google.common.truth.Truth.assertThat
import java.io.File
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

    private fun TestScope.model(file: File? = gtimeFile(tmp.root, "top", rows), store: GameStore = GameStore.Memory(), alternates: Boolean = true, start: GameKind = GameKind.ORDER): GameModel =
        GameModel(
            scope = this,
            indexes = { TimeIndexes(listOfNotNull(file?.let { TimeIndex.open(it, "top") })) },
            picture = { _, path -> if (path == name(0)) "/thumbs/a.jpg" else null },
            store = store, alternates = alternates, start = start,
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
        val m = model(store = store)
        m.ensureStarted(); advanceUntilIdle()
        val r = m.ui.value.round as Round.Order
        m.pick(r.answer.last().qid)
        assertThat((m.ui.value.round as Round.Order).right).isFalse()
        assertThat(store.level).isEqualTo(0)
        m.next(); advanceUntilIdle()   // a Guess round on Home
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

    @Test fun homeAlternatesGamesAndTheGamesScreenKeepsToOne() = runTest {
        val home = model()
        home.ensureStarted(); advanceUntilIdle()
        home.next(); advanceUntilIdle()
        assertThat(home.ui.value.round).isInstanceOf(Round.Guess::class.java)
        home.next(); advanceUntilIdle()
        assertThat(home.ui.value.round).isInstanceOf(Round.Order::class.java)

        val screen = model(file = gtimeFile(tmp.newFolder(), "top", rows), alternates = false, start = GameKind.GUESS)
        screen.ensureStarted(); advanceUntilIdle()
        screen.next(); advanceUntilIdle()
        assertThat(screen.ui.value.round).isInstanceOf(Round.Guess::class.java)
        screen.choose(GameKind.ORDER); advanceUntilIdle()
        assertThat(screen.ui.value.round).isInstanceOf(Round.Order::class.java)
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
            val marks = when (val r = m.ui.value.round) { is Round.Order -> r.marks; is Round.Guess -> listOf(r.mark); null -> emptyList() }
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
