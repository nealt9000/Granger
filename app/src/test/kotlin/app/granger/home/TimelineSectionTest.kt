package app.granger.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import app.granger.core.library.Lane
import app.granger.core.library.TimeKind
import app.granger.core.library.TimeMark
import app.granger.readiness.ReadinessReport
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class TimelineSectionTest {
    @get:Rule val compose = createComposeRule()

    private fun mark(qid: Long, title: String, y0: Int, y1: Int? = null, kind: TimeKind = TimeKind.POINT, lanes: Set<Lane> = setOf(Lane.EVENTS_POLITICS)) =
        TimeMark(qid, "top", title.replace(' ', '_'), title, kind, y0, 0, y1, null, 9, lanes, 10)

    private val un = mark(1, "United Nations", 1945)
    private val yalta = mark(2, "Yalta Conference", 1945)
    private val plan = mark(3, "Marshall Plan", 1948)
    private val plato = mark(4, "Plato", -427, -347, TimeKind.LIFE, setOf(Lane.PEOPLE, Lane.PHILOSOPHY_RELIGION))
    private val order = listOf(plato, un, yalta, plan)

    private class Harness(start: TimelineUi) { var ui by mutableStateOf(start) }

    private fun show(h: Harness, opened: MutableList<String> = mutableListOf(), toggled: MutableList<Lane> = mutableListOf()) {
        val actions = TimelineActions(
            onStep = { by -> val i = order.indexOf(h.ui.selected) + by; order.getOrNull(i)?.let { h.ui = h.ui.copy(selected = it) } },
            onSelect = { m -> h.ui = h.ui.copy(selected = m) },
            onToggle = { toggled += it },
        )
        compose.setContent {
            HomeContent(
                HomeUi(strip = ReadinessReport.EMPTY), onReadiness = {}, onOpenTab = {}, onPlay = {},
                onOpenCard = { opened += (it.target as CardTarget.Article).path },
                timeline = h.ui, timelineActions = actions,
                wander = WanderUi(centre = WanderNode("top", "Istanbul", "Istanbul")), wanderActions = WanderActions(),
            )
        }
    }

    private fun ui() = TimelineUi(
        loading = false, lanes = listOf(Lane.PHILOSOPHY_RELIGION, Lane.EVENTS_POLITICS, Lane.PEOPLE),
        marks = order, selected = un, nearby = listOf(yalta, plan), now = 2026,
    )

    @Test fun theStepButtonsMoveTheLensCardAndOpenOpensIt() {
        val opened = mutableListOf<String>()
        val h = Harness(ui())
        show(h, opened)
        compose.onNodeWithTag("timeline-title").assertTextEquals("United Nations")
        compose.onNodeWithTag("timeline-next").performClick()
        compose.onNodeWithTag("timeline-title").assertTextEquals("Yalta Conference")
        compose.onNodeWithTag("timeline-prev").performClick()
        compose.onNodeWithTag("timeline-prev").performClick()
        compose.onNodeWithTag("timeline-title").assertTextEquals("Plato")
        compose.onNodeWithTag("timeline-year").assertTextEquals("428 BC – 348 BC")
        compose.onNodeWithTag("timeline-open").performClick()
        assertThat(opened).containsExactly("Plato")
    }

    @Test fun nearbyAndChipsReachTheModel() {
        val toggled = mutableListOf<Lane>()
        val h = Harness(ui())
        show(h, toggled = toggled)
        compose.onNodeWithTag("timeline-nearby-3").performClick()
        assertThat(h.ui.selected).isEqualTo(plan)
        compose.onNodeWithTag("timeline-chip-people").performClick()
        assertThat(toggled).containsExactly(Lane.PEOPLE)
    }

    @Test fun theTimelineLeadsHomeAboveWanderAndAVerticalSwipeOnTheRiverScrollsHome() {
        show(Harness(ui()))
        val timelineTop = compose.onNodeWithTag("timeline-section").fetchSemanticsNode().boundsInRoot.top
        val wanderTop = compose.onNodeWithTag("wander-section").fetchSemanticsNode().boundsInRoot.top
        assertThat(timelineTop).isLessThan(wanderTop)
        compose.onNodeWithTag("timeline-river").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertThat(compose.onNodeWithTag("timeline-section").fetchSemanticsNode().boundsInRoot.top).isLessThan(timelineTop)
        compose.onNodeWithTag("home-list").performScrollToKey("strip")
    }

    @Test fun theTimelineIsHiddenWithoutAnIndex() {
        show(Harness(TimelineUi(available = false)))
        compose.onNodeWithTag("timeline").assertDoesNotExist()
    }

    @Test fun yearsReadAsPeopleWriteThem() {
        val w = YearWords("%s BC", "c. %s", "%s million years ago", "%s billion years ago", "%s to %s", "%s –")
        assertThat(w.year(1066)).isEqualTo("1066")
        assertThat(w.year(-499)).isEqualTo("500 BC")
        assertThat(w.year(-9_999)).isEqualTo("10,000 BC")
        assertThat(w.year(-65_999_999)).isEqualTo("66 million years ago")
        assertThat(w.year(-999_999)).isEqualTo("1 million years ago")
        assertThat(w.year(-13_799_999_999)).isEqualTo("13.8 billion years ago")
        assertThat(w.of(mark(9, "Living", 1961, kind = TimeKind.LIFE))).isEqualTo("1961 –")
        assertThat(w.of(mark(9, "Rough", 1500).copy(prec = 7))).isEqualTo("c. 1500")
        assertThat(w.of(mark(9, "Fourth century", 301).copy(prec = 7))).isEqualTo("c. 300")
        assertThat(w.of(mark(9, "Bronze age", -1549).copy(prec = 7))).isEqualTo("c. 1500 BC")
        assertThat(w.of(mark(9, "Late Middle Ages", 1301, 1492, TimeKind.SPAN).copy(prec = 7))).isEqualTo("c. 1300 – 1400")
    }
}
