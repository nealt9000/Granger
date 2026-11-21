package app.granger.sources

import android.content.Context

/** Android's [DownloadPrefs] (shared in core:screens, Plan I4b Task 6), in SharedPreferences. */
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
