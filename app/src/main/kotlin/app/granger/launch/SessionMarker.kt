package app.granger.launch

import android.content.SharedPreferences
import android.os.SystemClock
import kotlin.math.abs

/**
 * Remembers whether the previous session ended cleanly and whether the phone rebooted since.
 * [bootTimeMillis] is the wall-clock time of the current boot; two values within [BOOT_TOLERANCE_MS]
 * are the same boot (the clock jitters by a few milliseconds between reads).
 */
class SessionMarker(
    private val prefs: SharedPreferences,
    private val bootTimeMillis: () -> Long = { System.currentTimeMillis() - SystemClock.elapsedRealtime() },
) {
    /**
     * Returns (previous exit clean, booted since last session, first run), then records this session as
     * dirty until [markCleanExit].
     */
    fun readAndStartSession(): Triple<Boolean, Boolean, Boolean> {
        val firstRun = !prefs.contains(KEY_BOOT)
        val clean = prefs.getBoolean(KEY_CLEAN, false)
        val boot = bootTimeMillis()
        val booted = abs(boot - prefs.getLong(KEY_BOOT, 0L)) > BOOT_TOLERANCE_MS
        prefs.edit().putBoolean(KEY_CLEAN, false).putLong(KEY_BOOT, boot).apply()
        return Triple(clean, booted, firstRun)
    }

    fun markCleanExit() {
        prefs.edit().putBoolean(KEY_CLEAN, true).apply()
    }

    fun markDirty() {
        prefs.edit().putBoolean(KEY_CLEAN, false).apply()
    }

    private companion object {
        const val KEY_CLEAN = "clean_exit"
        const val KEY_BOOT = "boot_time"
        const val BOOT_TOLERANCE_MS = 5_000L
    }
}
