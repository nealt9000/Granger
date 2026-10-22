package app.granger.launch

import app.granger.core.library.Book
import app.granger.core.library.BookIds
import app.granger.core.library.PackStatus
import app.granger.core.library.ReadingTab
import app.granger.settings.OpenTo
import java.io.File

/** Where the app opens. */
sealed interface StartScreen {
    /** Resume reading the tab with this id; [book] tabs open the e-book reader. */
    data class Reader(val tabId: Long, val book: Boolean = false) : StartScreen

    /** Open the Library, optionally with a one-line [notice] explaining why we did not resume. */
    data class Library(val notice: String? = null) : StartScreen

    /** Open Home (Plan H2): nothing to resume, or the setting says so. */
    data object Home : StartScreen
}

/** What we know about the previous session when the app starts. */
data class LaunchFacts(
    val previousExitClean: Boolean,
    val bootedSinceLastSession: Boolean,
    val firstRun: Boolean,
    val lastTab: ReadingTab?,
    val lastTabPackOk: Boolean,
    /** Settings → Open to (Plan H2). */
    val openTo: OpenTo = OpenTo.RESUME,
)

/**
 * Resume the last tab only after a normal close; first run, a crash, a reboot or nothing to resume land on Home
 * (Plan H2), as does every normal open when the setting says Home. A last tab whose pack is gone lands on the Library
 * with a notice: that is where it is fixed.
 */
object LaunchDecider {
    const val MISSING_NOTICE =
        "The last thing you were reading isn't available. Check that its pack is still on the phone or SD card."

    fun decide(f: LaunchFacts): StartScreen {
        val tab = f.lastTab
        if (f.firstRun || !f.previousExitClean || f.bootedSinceLastSession || tab == null) {
            return StartScreen.Home
        }
        if (!f.lastTabPackOk) return StartScreen.Library(MISSING_NOTICE)
        return if (f.openTo == OpenTo.HOME) StartScreen.Home else StartScreen.Reader(tab.id, BookIds.isBook(tab.packId))
    }
}

/** A book can be resumed only if its row is OK and its file is still there. */
fun isBookResumable(book: Book?, fileExists: (String) -> Boolean = { File(it).exists() }): Boolean =
    book != null && isPackResumable(book.status, book.filePath, fileExists)

/**
 * A pack can be resumed only if the last scan found it OK and its file is still there: a pulled SD card or a file
 * deleted outside the app leaves a stale OK row until the next scan. [fileExists] is injectable for tests.
 */
fun isPackResumable(status: PackStatus, filePath: String, fileExists: (String) -> Boolean = { File(it).exists() }): Boolean =
    status == PackStatus.OK && fileExists(filePath)
