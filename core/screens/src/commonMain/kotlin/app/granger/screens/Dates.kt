package app.granger.screens

/** A calendar date without a time zone (common code has no java.time; Plan I4 Task 2). */
data class CivilDate(val year: Int, val month: Int, val day: Int) : Comparable<CivilDate> {
    /** Days since 1970-01-01. */
    val epochDay: Long get() {
        // Howard Hinnant's days_from_civil.
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468
    }

    fun plusDays(days: Long): CivilDate = ofEpochDay(epochDay + days)

    override fun compareTo(other: CivilDate): Int = epochDay.compareTo(other.epochDay)

    /** In the phone's medium style. */
    fun medium(): String = mediumDate(year, month, day)

    companion object {
        /** "2026-03-05" (anything after the tenth character ignored); null when it isn't a real date. */
        fun parse(text: String): CivilDate? {
            val m = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").matchEntire(text.take(10)) ?: return null
            val (y, mo, d) = m.destructured
            val date = CivilDate(y.toInt(), mo.toInt(), d.toInt())
            if (date.month !in 1..12 || date.day !in 1..31) return null
            return date.takeIf { ofEpochDay(it.epochDay) == it }
        }

        fun ofEpochDay(day: Long): CivilDate {
            val z = day + 719468
            val era = (if (z >= 0) z else z - 146096) / 146097
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
            return CivilDate(y, m, d)
        }

        fun today(): CivilDate = ofEpochDay(todayEpochDay())
    }
}

/**
 * "2.0 GB", "350 MB", "512 B": decimal units, the number in the phone's style ("2,0 GB" in Spanish); the unit symbols
 * are the same in every language. Android's app-side `formatBytes` gives the same words.
 */
fun bytesText(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    return when {
        unit == 0 -> "$bytes ${units[0]}"
        value < 10 -> "${formatFixed(value, 1)} ${units[unit]}"
        else -> "${formatFixed(value, 0)} ${units[unit]}"
    }
}
