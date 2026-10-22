package app.granger.onboarding

import android.content.Context
import android.content.SharedPreferences

/** The one mark onboarding keeps (SharedPreferences `"onboarding"`): the flow was finished or skipped once. */
interface OnboardingPrefs {
    var done: Boolean
}

class SharedOnboardingPrefs(private val prefs: SharedPreferences) : OnboardingPrefs {
    constructor(context: Context) : this(context.getSharedPreferences("onboarding", Context.MODE_PRIVATE))

    override var done: Boolean
        get() = prefs.getBoolean(KEY_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_DONE, value).apply()

    private companion object { const val KEY_DONE = "done" }
}
