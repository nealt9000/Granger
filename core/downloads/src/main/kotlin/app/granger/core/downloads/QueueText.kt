package app.granger.core.downloads

import android.content.res.Resources
import android.icu.text.MessageFormat
import kotlin.math.roundToLong

/**
 * Plain words for the queue's figures, shared by the screens and the notification.
 */
object QueueText {
    /** Rounded so it doesn't jump about: "about 25 min", "about 3 h". */
    fun timeLeft(res: Resources, millis: Long): String {
        val (unit, n) = roundLeft(millis)
        return when (unit) {
            Span.NONE -> res.getString(R.string.downloads_time_left_under_minute)
            Span.MIN -> res.getQuantityString(R.plurals.downloads_time_left_minutes, n.toInt(), n)
            Span.H -> res.getQuantityString(R.plurals.downloads_time_left_hours, n.toInt(), n)
            Span.DAYS -> res.getQuantityString(R.plurals.downloads_time_left_days, n.toInt(), n)
            Span.S -> error("not a time left")
        }
    }

    /** "in 40 s", "in 2 min", "in 3 h". */
    fun inTime(res: Resources, millis: Long): String {
        val (unit, n) = roundIn(millis)
        val id = when (unit) {
            Span.MIN -> R.plurals.downloads_in_minutes
            Span.H -> R.plurals.downloads_in_hours
            else -> R.plurals.downloads_in_seconds
        }
        return res.getQuantityString(id, n.toInt(), n)
    }

    /** 1 is "Next", then "2nd in line", "3rd in line", "4th in line", "11th in line"… The ordinal is the locale's. */
    fun place(res: Resources, n: Int): String {
        if (n <= 1) return res.getString(R.string.downloads_place_next)
        val locale = res.configuration.locales[0]
        val ordinal = MessageFormat("{0,ordinal}", locale).format(arrayOf(n))
        return res.getString(R.string.downloads_place_in_line, ordinal)
    }

    private enum class Span { NONE, S, MIN, H, DAYS }

    private fun roundLeft(millis: Long): Pair<Span, Long> {
        val minutes = millis / 60_000.0
        return when {
            minutes < 1 -> Span.NONE to 0L
            minutes < 10 -> Span.MIN to minutes.roundToLong().coerceAtLeast(1)
            minutes < 55 -> Span.MIN to (minutes / 5).roundToLong() * 5
            minutes < 47.5 * 60 -> Span.H to (minutes / 60).roundToLong().coerceAtLeast(1)
            else -> Span.DAYS to (minutes / 1440).roundToLong()
        }
    }

    private fun roundIn(millis: Long): Pair<Span, Long> {
        val s = (millis / 1000).coerceAtLeast(1)
        return when {
            s < 60 -> Span.S to s
            s < 3600 -> Span.MIN to (s + 30) / 60
            else -> Span.H to (s + 1800) / 3600
        }
    }
}
