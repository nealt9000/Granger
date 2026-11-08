package app.granger.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimeScaleTest {
    @Test fun linearAfterTheJoinAndRoundTripsBefore() {
        assertThat(TimeScale.toAxis(1066.0)).isEqualTo(1066.0)
        for (y in listOf(-2999.0, -5000.0, -100_000.0, -65_000_000.0, TimeScale.BIG_BANG.toDouble())) {
            assertThat(TimeScale.toYear(TimeScale.toAxis(y))).isWithin(kotlin.math.abs(y) * 1e-9 + 1e-6).of(y)
        }
    }

    @Test fun theJoinIsSmoothAndTheDeepPastIsCompressed() {
        val j = TimeScale.JOIN.toDouble()
        // Slope near 1 on both sides of the join.
        assertThat(TimeScale.toAxis(j) - TimeScale.toAxis(j - 1)).isWithin(0.01).of(1.0)
        // The whole deep past takes about as much room as recorded history, not millions of times more.
        val deep = TimeScale.toAxis(j) - TimeScale.toAxis(TimeScale.BIG_BANG.toDouble())
        assertThat(deep).isLessThan(8000.0)
        assertThat(deep).isGreaterThan(2000.0)
    }

    @Test fun zoomKeepsTheFocusStillAndStaysInBounds() {
        val all = TimeScale.all(2026)
        val w = TimeScale.Window(1000.0, 2000.0)
        val z = w.zoom(2f, 250f, 1000f, all)
        assertThat(z.span).isWithin(1e-9).of(500.0)
        assertThat(z.year(250f, 1000f)).isWithin(1e-6).of(w.year(250f, 1000f))
        assertThat(w.zoom(1000f, 500f, 1000f, all).span).isEqualTo(TimeScale.MIN_SPAN)
        assertThat(w.zoom(0.00001f, 500f, 1000f, all)).isEqualTo(all)
        assertThat(w.pan(-1e9f, 1000f, all).end).isEqualTo(all.end)
    }

    @Test fun ticksAreRoundAndSpaced() {
        val ticks = TimeScale.ticks(TimeScale.Window(1900.0, 2000.0), 1000f, 60f)
        assertThat(ticks).containsExactly(1900L, 1910L, 1920L, 1930L, 1940L, 1950L, 1960L, 1970L, 1980L, 1990L, 2000L).inOrder()
        val all = TimeScale.all(2026)
        val wide = TimeScale.ticks(all, 1000f, 60f)
        assertThat(wide).contains(-9_999L)        // 10,000 BC
        assertThat(wide).contains(1000L)
        assertThat(wide.zipWithNext().all { (a, b) -> all.x(b.toDouble(), 1000f) - all.x(a.toDouble(), 1000f) >= 60f }).isTrue()
    }
}
