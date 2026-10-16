package app.granger.sources

import android.content.Context

interface DownloadPrefs {
    /** Spec: Wi-Fi only by default; the user can turn it off. */
    var wifiOnly: Boolean

    /** The chosen volume's root path; null means the first (internal) volume. */
    var volumePath: String?
}

class SharedDownloadPrefs(context: Context) : DownloadPrefs {
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    override var wifiOnly: Boolean
        get() = prefs.getBoolean("wifiOnly", true)
        set(value) = prefs.edit().putBoolean("wifiOnly", value).apply()

    override var volumePath: String?
        get() = prefs.getString("volumePath", null)
        set(value) = prefs.edit().putString("volumePath", value).apply()
}
