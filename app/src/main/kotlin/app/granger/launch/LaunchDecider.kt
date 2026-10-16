package app.granger.launch

import app.granger.core.library.ReadingTab

/** Where the app opens. */
sealed interface StartScreen {
    /** Resume reading the tab with this id. */
    data class Reader(val tabId: Long) : StartScreen

    /** Open the Library, optionally with a one-line [notice] explaining why we did not resume. */
    data class Library(val notice: String? = null) : StartScreen
}

/** What we know about the previous session when the app starts. */
data class LaunchFacts(
    val previousExitClean: Boolean,
    val bootedSinceLastSession: Boolean,
    val firstRun: Boolean,
    val lastTab: ReadingTab?,
    val lastTabPackOk: Boolean,
)

/** Resume the last tab only after a normal close; first run, a crash or a reboot land on the Library. */
object LaunchDecider {
    const val MISSING_NOTICE =
        "The last thing you were reading isn't available. Check that its pack is still on the phone or SD card."

    fun decide(f: LaunchFacts): StartScreen {
        val tab = f.lastTab
        if (f.firstRun || !f.previousExitClean || f.bootedSinceLastSession || tab == null) {
            return StartScreen.Library()
        }
        return if (f.lastTabPackOk) StartScreen.Reader(tab.id) else StartScreen.Library(MISSING_NOTICE)
    }
}
