package app.granger.home

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * The Timeline's axis (spec §5.1): linear from [JOIN] (3000 BC) to now, logarithmic before it, joined so both the
 * position and the slope match and the bend is soft. Years are astronomical (500 BC = -499). "Axis units" are what the
 * view scrolls and zooms over; a [Window] is the part on screen.
 */
object TimeScale {
    const val JOIN = -2999            // 3000 BC
    private const val SOFT = 300.0    // years: how gently the deep past compresses
    const val BIG_BANG = -13_800_000_000L
    const val MIN_SPAN = 8.0          // the closest zoom: about eight years across the screen

    fun toAxis(year: Double): Double =
        if (year >= JOIN) year else JOIN - SOFT * ln(1 + (JOIN - year) / SOFT)

    fun toYear(axis: Double): Double =
        if (axis >= JOIN) axis else JOIN - SOFT * (exp((JOIN - axis) / SOFT) - 1)

    /** The whole axis: the Big Bang to [now] plus a little room. */
    fun all(now: Int): Window = Window(toAxis(BIG_BANG.toDouble()), now + 5.0)

    /** Where the timeline opens: recorded history, 3000 BC to now; the deep past is a pinch away. */
    fun history(now: Int): Window = Window(JOIN.toDouble(), now + 5.0)

    /** The visible stretch of the axis, in axis units. */
    data class Window(val start: Double, val end: Double) {
        val span: Double get() = end - start

        fun x(year: Double, width: Float): Float = ((toAxis(year) - start) / span * width).toFloat()
        fun year(x: Float, width: Float): Double = toYear(start + x / width * span)

        /** Zoomed by [factor] (>1 zooms in) around screen position [focusX], kept inside [bounds] and no closer than [MIN_SPAN]. */
        fun zoom(factor: Float, focusX: Float, width: Float, bounds: Window): Window {
            val focus = start + focusX / width * span
            val newSpan = (span / factor).coerceIn(MIN_SPAN, bounds.span)
            val newStart = focus - (focus - start) * newSpan / span
            return Window(newStart, newStart + newSpan).within(bounds)
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
    }

    /**
     * Ruler ticks for [window] on a [width]-pixel axis, at least [minGap] pixels apart: round years on the linear part
     * (every 1, 2, 5, 10 … 1000 years, the finest step that fits), and powers of ten "years ago" on the compressed part.
     */
    fun ticks(window: Window, width: Float, minGap: Float): List<Long> {
        val out = mutableListOf<Long>()
        val (y0, y1) = toYear(window.start) to toYear(window.end)
        if (y1 > JOIN) {
            // Round as people write years: AD 1000, 1500 BC (astronomical -1499), never "2,001 BC".
            val from = maxOf(y0, JOIN.toDouble())
            val pxPerYear = width / window.span
            val step = STEPS.firstOrNull { it * pxPerYear >= minGap } ?: STEPS.last()
            var ad = maxOf(step, (kotlin.math.ceil(from / step) * step).toLong())
            while (ad <= y1) { out += ad; ad += step }
            var bc = step
            while (1 - bc >= from) {
                if (1 - bc <= y1) out += 1 - bc
                bc += step
            }
        }
        if (y0 < JOIN) {
            for (k in 4..10) {
                val y = (-(10.0.pow(k))).toLong() + 1   // 10,000 BC … 10 billion years ago, astronomically
                if (y in y0.toLong()..minOf(y1, JOIN.toDouble()).toLong()) out += y
            }
        }
        val sorted = out.distinct().sorted()
        val kept = mutableListOf<Long>()
        for (y in sorted) if (kept.isEmpty() || abs(window.x(y.toDouble(), width) - window.x(kept.last().toDouble(), width)) >= minGap) kept += y
        return kept
    }

    private val STEPS = listOf(1L, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000)
}
