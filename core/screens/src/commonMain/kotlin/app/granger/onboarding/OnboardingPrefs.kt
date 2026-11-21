package app.granger.onboarding

/** The one mark onboarding keeps: the flow was finished or skipped once (Android: SharedPreferences `"onboarding"`). */
interface OnboardingPrefs {
    var done: Boolean

    /** The group Outfit for offline queued (Plan OB call 3): Explore's downloads line follows it until dismissed. */
    var outfitGroup: String?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") value) {}
}
