package app.granger.sources

import android.content.Context
import app.granger.core.downloads.QueuePrefs

/** Also the queue's own settings ([QueuePrefs]): Pause all survives a restart (Plan DL Decision 3). */
interface DownloadPrefs : QueuePrefs {
    /** Spec: Wi-Fi only by default; the user can turn it off. */
    var wifiOnly: Boolean

    /** The chosen volume's root path; null means the first (internal) volume. */
    var volumePath: String?

    /** Get asks first for a download over [LARGE_DOWNLOAD_BYTES] (Plan P2); on by default, "Don't ask again" clears it. */
    var askBeforeLarge: Boolean

    companion object {
        /** 100 MB, in the decimal units [app.granger.formatBytes] shows. */
        const val LARGE_DOWNLOAD_BYTES = 100L * 1000 * 1000
    }
}

class SharedDownloadPrefs(context: Context) : DownloadPrefs {
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    override var wifiOnly: Boolean
        get() = prefs.getBoolean("wifiOnly", true)
        set(value) = prefs.edit().putBoolean("wifiOnly", value).apply()

    override var volumePath: String?
        get() = prefs.getString("volumePath", null)
        set(value) = prefs.edit().putString("volumePath", value).apply()

    override var askBeforeLarge: Boolean
        get() = prefs.getBoolean("askBeforeLarge", true)
        set(value) = prefs.edit().putBoolean("askBeforeLarge", value).apply()

    override var paused: Boolean
        get() = prefs.getBoolean("paused", false)
        set(value) = prefs.edit().putBoolean("paused", value).apply()

    override var batchStartedAt: Long
        get() = prefs.getLong("batchStartedAt", 0)
        set(value) = prefs.edit().putLong("batchStartedAt", value).apply()
}
