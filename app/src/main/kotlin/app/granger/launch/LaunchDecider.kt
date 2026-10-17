package app.granger.launch

import app.granger.core.library.BookIds
import app.granger.core.library.PackStatus
import app.granger.core.library.ReadingTab
import java.io.File

/** Where the app opens. */
sealed interface StartScreen {
    /** Resume reading the tab with this id; [book] tabs open the e-book reader. */
    data class Reader(val tabId: Long, val book: Boolean = false) : StartScreen

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
        return if (f.lastTabPackOk) StartScreen.Reader(tab.id, BookIds.isBook(tab.packId)) else StartScreen.Library(MISSING_NOTICE)
    }
}

/**
 * A pack can be resumed only if the last scan found it OK and its file is still there: a pulled SD card or a file
 * deleted outside the app leaves a stale OK row until the next scan. [fileExists] is injectable for tests.
 */
fun isPackResumable(status: PackStatus, filePath: String, fileExists: (String) -> Boolean = { File(it).exists() }): Boolean =
    status == PackStatus.OK && fileExists(filePath)
