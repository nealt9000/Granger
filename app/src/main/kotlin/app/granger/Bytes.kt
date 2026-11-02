package app.granger

import java.util.Locale

/**
 * "2.0 GB", "350 MB", "512 B": decimal units, like Android's Formatter, but usable in JVM tests. The number takes
 * [locale]'s decimal separator ("2,0 GB" in Spanish); the unit symbols are the same in every language.
 */
fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    return when {
        unit == 0 -> String.format(locale, "%d %s", bytes, units[0])
        value < 10 -> String.format(locale, "%.1f %s", value, units[unit])
        else -> String.format(locale, "%.0f %s", value, units[unit])
    }
}
