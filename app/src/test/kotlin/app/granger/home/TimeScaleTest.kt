package app.granger.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimeScaleTest {
    private val history = TimeScale.history(2025)

    @Test fun yearsRoundTripAcrossTheWholeAxis() {
        for (y in listOf(2025.0, 1066.0, -499.0, -100_000.0, -65_000_000.0, TimeScale.BIG_BANG.toDouble())) {
            assertThat(TimeScale.toYear(TimeScale.toAxis(y))).isWithin(kotlin.math.abs(y) * 1e-9 + 1e-6).of(y)
        }
    }

    @Test fun theRecentPastGetsMostOfTheWidth() {
        val w = 1000f
        // On 3000 BC to now, AD 1000 onward takes well over half the width, the 20th century about a fifth.
        assertThat(w - history.x(1000.0, w)).isGreaterThan(550f)
        assertThat(history.x(2000.0, w) - history.x(1900.0, w)).isGreaterThan(150f)
        // The right edge is the latest year the phone has.
        assertThat(history.year(w, w)).isWithin(1.0).of(2025.0)
    }

    @Test fun zoomKeepsTheFocusStillAndStopsAtEightYears() {
        val all = TimeScale.all(2025)
        val z = history.zoom(2f, 250f, 1000f, all)
        assertThat(z.span).isWithin(1e-9).of(history.span / 2)
        assertThat(z.year(250f, 1000f)).isWithin(1e-6).of(history.year(250f, 1000f))
        var close = history
        repeat(60) { close = close.zoom(2f, 500f, 1000f, all) }
        assertThat(close.toYearSpan()).isAtLeast(TimeScale.MIN_YEARS / 2)
        val out = history.zoom(0.00001f, 500f, 1000f, all)
        assertThat(out.start).isWithin(1e-6).of(all.start)
        assertThat(out.end).isWithin(1e-6).of(all.end)
        assertThat(history.pan(-1e9f, 1000f, all).end).isWithin(1e-6).of(all.end)
    }

    @Test fun slicesAreEqualOnScreen() {
        val edges = history.slices(4)
        assertThat(edges.first()).isEqualTo(TimeScale.HISTORY_START)
        assertThat(edges.last()).isEqualTo(2025)
        assertThat(edges).isInOrder()
    }

    @Test fun ticksAreRoundAsWrittenAndSpaced() {
        val ticks = TimeScale.ticks(history, 1000f, 60f)
        assertThat(ticks).contains(1000L)
        assertThat(ticks).doesNotContain(-2000L)            // never "2,001 BC"
        assertThat(ticks.any { it < 0 }).isTrue()
        val decade = TimeScale.ticks(TimeScale.Window(TimeScale.toAxis(1900.0), TimeScale.toAxis(2000.0)), 1000f, 60f)
        assertThat(decade).containsAtLeast(1900L, 1950L, 2000L)
        val all = TimeScale.all(2025)
        val wide = TimeScale.ticks(all, 1000f, 60f)
        assertThat(wide).contains(-9_999L)                  // 10,000 BC
        for (t in listOf(ticks, decade, wide)) {
            val w = if (t === wide) all else if (t === decade) TimeScale.Window(TimeScale.toAxis(1900.0), TimeScale.toAxis(2000.0)) else history
            assertThat(t.zipWithNext().all { (a, b) -> w.x(b.toDouble(), 1000f) - w.x(a.toDouble(), 1000f) >= 60f }).isTrue()
        }
    }

    private fun TimeScale.Window.toYearSpan() = TimeScale.toYear(end) - TimeScale.toYear(start)
}
