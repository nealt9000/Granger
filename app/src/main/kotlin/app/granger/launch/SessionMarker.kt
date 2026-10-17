package app.granger.launch

import android.content.SharedPreferences
import android.os.SystemClock
import kotlin.math.abs

/**
 * Remembers whether the previous session ended cleanly and whether the phone rebooted since.
 * Reboots are detected by [bootCount] (e.g. `Settings.Global.BOOT_COUNT`, null when unavailable) changing
 * between sessions. Without a count on either side it falls back to [bootTimeMillis], the wall-clock time
 * of the current boot; two values within [BOOT_TOLERANCE_MS] are the same boot (the clock jitters).
 */
class SessionMarker(
    private val prefs: SharedPreferences,
    private val bootTimeMillis: () -> Long = { System.currentTimeMillis() - SystemClock.elapsedRealtime() },
    private val bootCount: () -> Int? = { null },
) {
    /**
     * Returns (previous exit clean, booted since last session, first run), then records this session as
     * dirty until [markCleanExit]. The dirty write is synchronous so a crash right after launch cannot lose it.
     */
    fun readAndStartSession(): Triple<Boolean, Boolean, Boolean> {
        val firstRun = !prefs.contains(KEY_BOOT)
        val clean = prefs.getBoolean(KEY_CLEAN, false)
        val boot = bootTimeMillis()
        val count = bootCount()?.takeIf { it >= 0 }
        val booted = if (count != null && prefs.contains(KEY_COUNT)) {
            count != prefs.getInt(KEY_COUNT, -1)
        } else {
            abs(boot - prefs.getLong(KEY_BOOT, 0L)) > BOOT_TOLERANCE_MS
        }
        prefs.edit().putBoolean(KEY_CLEAN, false).putLong(KEY_BOOT, boot).apply {
            if (count != null) putInt(KEY_COUNT, count) else remove(KEY_COUNT)
        }.commit()
        return Triple(clean, booted, firstRun)
    }

    fun markCleanExit() {
        prefs.edit().putBoolean(KEY_CLEAN, true).apply()
    }

    fun markDirty() {
        prefs.edit().putBoolean(KEY_CLEAN, false).commit()
    }

    private companion object {
        const val KEY_CLEAN = "clean_exit"
        const val KEY_COUNT = "boot_count"
        const val KEY_BOOT = "boot_time"
        const val BOOT_TOLERANCE_MS = 5_000L
    }
}
