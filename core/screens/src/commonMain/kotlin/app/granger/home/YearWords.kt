package app.granger.home

import app.granger.core.library.TimeKind
import app.granger.core.library.TimeMark

/**
 * Year wording for marks, the ruler and screen readers, in the app's language (shared since Plan I3): the templates
 * are the app's strings ("%1$s BC", "c. %1$s"), [num] groups a whole number and [decimal] writes one decimal place,
 * both in the app's locale.
 */
class YearWords(
    private val bc: String,
    private val circa: String,
    private val millionAgo: String,
    private val billionAgo: String,
    private val rangeText: String,
    private val living: String,
    private val num: (Long) -> String = ::grouped,
    private val decimal: (Double) -> String = { v -> (kotlin.math.round(v * 10) / 10).toString() },
) {
    /** One year: 1066, 500 BC, 10,000 BC, 65 million years ago. Years are astronomical (500 BC = -499). */
    fun year(y: Long): String = when {
        y > 0 -> y.toString()
        y > -9_999 -> fill(bc, (1 - y).toString())     // 1500 BC, written like 1500
        y > -999_999 -> fill(bc, num(1 - y))             // 10,000 BC
        y > -1_000_000_000 -> fill(millionAgo, trim((1 - y) / 1e6))
        else -> fill(billionAgo, trim((1 - y) / 1e9))
    }

    fun tick(y: Long): String = year(y)

    /** A mark's dates: a point's year, a span or lifetime's two ends, "c." for anything coarser than a year. */
    fun of(m: TimeMark): String {
        val a = year(rounded(m.y0, m.prec))
        val end = m.y1
        val text = when {
            m.kind == TimeKind.POINT -> a
            end != null -> "$a – ${year(rounded(end, m.prec))}"
            m.kind == TimeKind.LIFE -> fill(living, a)
            else -> a
        }
        return if (m.prec < 9) fill(circa, text) else text
    }

    /**
     * A year rounded to what its precision supports, as people write it: Wikidata stores "the 4th century" as 301, which
     * reads as c. 300; 1450s as c. 1450; 1500 BC (astronomical -1499) stays 1500 BC.
     */
    private fun rounded(y: Long, prec: Int): Long {
        val unit = when (prec) { 8 -> 10; 7 -> 100; 6 -> 1000; else -> return y }
        val written = if (y > 0) y else 1L - y
        val r = written / unit * unit
        val w = if (r == 0L) written else r
        return if (y > 0) w else 1 - w
    }

    fun range(from: Int, to: Int): String = fill(rangeText, year(from.toLong()), year(to.toLong()))

    /** 66 and 230 stay whole; 13.8 and 2.5 keep their decimal. */
    private fun trim(v: Double): String {
        val tenths = kotlin.math.round(v * 10) / 10
        return if (v >= 100 || tenths == kotlin.math.floor(tenths)) num(kotlin.math.round(v).toLong()) else decimal(tenths)
    }

    companion object {
        /** A string template's %1$s and %2$s (and a bare %s) filled in. */
        fun fill(template: String, a: String, b: String = ""): String =
            template.replace("%1\$s", a).replace("%2\$s", b).replace("%s", a)

        /** 10000 → "10,000". */
        fun grouped(n: Long): String {
            val digits = kotlin.math.abs(n).toString()
            val out = StringBuilder()
            digits.forEachIndexed { i, c -> if (i > 0 && (digits.length - i) % 3 == 0) out.append(','); out.append(c) }
            return (if (n < 0) "-" else "") + out
        }
    }
}
