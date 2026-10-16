package app.granger

import java.util.Locale

/** "2.0 GB", "350 MB", "512 B": decimal units, like Android's Formatter, but usable in JVM tests. */
fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    return when {
        unit == 0 -> "$bytes B"
        value < 10 -> String.format(Locale.US, "%.1f %s", value, units[unit])
        else -> String.format(Locale.US, "%.0f %s", value, units[unit])
    }
}
