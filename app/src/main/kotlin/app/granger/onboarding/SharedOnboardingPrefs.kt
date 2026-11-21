package app.granger.onboarding

import android.content.Context
import android.content.SharedPreferences

/** [OnboardingPrefs] (shared, Plan I4b Task 8) in SharedPreferences `"onboarding"`. */
class SharedOnboardingPrefs(private val prefs: SharedPreferences) : OnboardingPrefs {
    constructor(context: Context) : this(context.getSharedPreferences("onboarding", Context.MODE_PRIVATE))

    override var done: Boolean
        get() = prefs.getBoolean(KEY_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_DONE, value).apply()

    override var outfitGroup: String?
        get() = prefs.getString(KEY_GROUP, null)
        set(value) = prefs.edit().putString(KEY_GROUP, value).apply()

    private companion object {
        const val KEY_DONE = "done"
        const val KEY_GROUP = "outfit_group"
    }
}
