package app.granger.home

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * The Timeline's axis: position is the logarithm of years before [ANCHOR], so the recent past, where most articles are,
 * gets most of the width, and the deep past still fits on the same axis. On 3000 BC to now, AD 1000 onward takes about
 * 60% of the width and the 20th century about a fifth (Neal, 2026-11-08: "about 1 cm from year 1000 onwards"). Zoomed in
 * to a few centuries the scale is close to linear. Years are astronomical (500 BC = -499); "axis units" are what the
 * view scrolls and zooms over, and a [Window] is the part on screen.
 */
object TimeScale {
    const val ANCHOR = 2100.0
    private const val UNIT = 1000.0
    const val BIG_BANG = -13_800_000_000L
    /** The closest zoom: about eight years across the screen. */
    const val MIN_YEARS = 8.0
    /** Where the timeline opens: 3000 BC. */
    const val HISTORY_START = -2999

    fun toAxis(year: Double): Double = -UNIT * ln(ANCHOR - minOf(year, ANCHOR - 1))

    fun toYear(axis: Double): Double = ANCHOR - exp(-axis / UNIT)

    /** The whole axis: the Big Bang to [latest], the last year the phone has an article for. */
    fun all(latest: Int): Window = Window(toAxis(BIG_BANG.toDouble()), toAxis(latest + 0.5))

    /** Where the timeline opens: recorded history, 3000 BC to [latest]; the deep past is a pinch away. */
    fun history(latest: Int): Window = Window(toAxis(HISTORY_START.toDouble()), toAxis(latest + 0.5))

    /** The visible stretch of the axis, in axis units. */
    data class Window(val start: Double, val end: Double) {
        val span: Double get() = end - start

        fun x(year: Double, width: Float): Float = ((toAxis(year) - start) / span * width).toFloat()
        fun year(x: Float, width: Float): Double = toYear(start + x / width * span)

        /** Zoomed by [factor] (>1 zooms in) around screen position [focusX], kept inside [bounds] and no closer than [MIN_YEARS]. */
        fun zoom(factor: Float, focusX: Float, width: Float, bounds: Window): Window {
            val focus = start + focusX / width * span
            val newSpan = (span / factor).coerceAtMost(bounds.span)
            val newStart = focus - (focus - start) * newSpan / span
            val next = Window(newStart, newStart + newSpan).within(bounds)
            return if (factor > 1 && toYear(next.end) - toYear(next.start) < MIN_YEARS) this else next
        }

        /** Moved by [dx] screen pixels (a drag to the right goes back in time). */
        fun pan(dx: Float, width: Float, bounds: Window): Window {
            val d = -dx / width * span
            return Window(start + d, end + d).within(bounds)
        }

        /** Centred on [year] at the same span. */
        fun centredOn(year: Double, bounds: Window): Window {
            val c = toAxis(year)
            return Window(c - span / 2, c + span / 2).within(bounds)
        }

        fun within(bounds: Window): Window {
            val s = span.coerceAtMost(bounds.span)
            val st = start.coerceIn(bounds.start, bounds.end - s)
            return Window(st, st + s)
        }

        /** The years at either edge, for querying the index. */
        fun years(): Pair<Int, Int> = toYear(start).toLong().coerceAtLeast(Int.MIN_VALUE.toLong()).toInt() to toYear(end).toInt()

        /** [n] slices of equal width on screen, as start-year edges (n + 1 of them), for spreading dots evenly. */
        fun slices(n: Int): List<Int> =
            (0..n).map { i -> kotlin.math.floor(toYear(start + span * i / n) + 1e-6).coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt() }.distinct()
    }

    /**
     * Ruler ticks for [window] on a [width]-pixel axis, at least [minGap] pixels apart. Rounder years win: powers of ten
     * "years ago" in the deep past, then millennia, centuries and decades as written (1000, 1500 BC, 1950), each added only
     * where it leaves room from the ticks already chosen. Returned in time order.
     */
    fun ticks(window: Window, width: Float, minGap: Float): List<Long> {
        val (y0, y1) = toYear(window.start) to toYear(window.end)
        val candidates = mutableListOf<Long>()
        for (k in 10 downTo 4) {
            val y = 1 - 10.0.pow(k).toLong()   // 10 billion … 10,000 years ago, astronomically
            if (y in y0.toLong()..y1.toLong()) candidates += y
        }
        // Round years as written, back to 100,000 years ago; past that only the powers of ten above.
        val from = maxOf(y0, -100_000.0)
        for (step in STEPS) {
            if ((y1 - from) / step > MAX_CANDIDATES) continue
            var ad = maxOf(step, (kotlin.math.ceil(from / step) * step).toLong())
            while (ad <= y1) { candidates += ad; ad += step }
            var bc = maxOf(step, (kotlin.math.ceil((1 - minOf(y1, 0.0)) / step) * step).toLong())
            while (1 - bc >= from) { candidates += 1 - bc; bc += step }
        }
        val kept = mutableListOf<Pair<Long, Float>>()
        for (y in candidates) {
            val x = window.x(y.toDouble(), width)
            if (x < 0 || x > width) continue
            if (kept.all { abs(it.second - x) >= minGap }) kept += y to x
        }
        return kept.map { it.first }.sorted()
    }

    private val STEPS = listOf(1000L, 500, 100, 50, 10, 5, 1)
    private const val MAX_CANDIDATES = 400
}
