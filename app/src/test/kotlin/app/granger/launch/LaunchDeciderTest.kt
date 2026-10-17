package app.granger.launch

import app.granger.book
import app.granger.core.library.PackStatus
import app.granger.core.library.ReadingTab
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LaunchDeciderTest {
    private val tab = ReadingTab(id = 7, packId = "p", path = "A/Cat", title = "Cat", openedAt = 1, onRail = true)
    private val normal = LaunchFacts(
        previousExitClean = true, bootedSinceLastSession = false, firstRun = false,
        lastTab = tab, lastTabPackOk = true,
    )

    @Test fun normalOpenResumesLastTab() {
        assertThat(LaunchDecider.decide(normal)).isEqualTo(StartScreen.Reader(7))
    }

    @Test fun normalOpenResumesLastBook() {
        val book = tab.copy(id = 9, packId = "book:granger:pg1", path = "")
        assertThat(LaunchDecider.decide(normal.copy(lastTab = book))).isEqualTo(StartScreen.Reader(9, book = true))
    }

    @Test fun launchWithMissingBookOpensLibraryWithNotice() {
        val book = tab.copy(id = 9, packId = "book:granger:pg1", path = "")
        assertThat(LaunchDecider.decide(normal.copy(lastTab = book, lastTabPackOk = false)))
            .isEqualTo(StartScreen.Library(LaunchDecider.MISSING_NOTICE))
    }

    @Test fun firstRunOpensLibrary() {
        assertThat(LaunchDecider.decide(normal.copy(firstRun = true))).isEqualTo(StartScreen.Library())
    }

    @Test fun crashBeforeCleanExitOpensLibrary() {
        assertThat(LaunchDecider.decide(normal.copy(previousExitClean = false))).isEqualTo(StartScreen.Library())
    }

    @Test fun rebootOpensLibrary() {
        assertThat(LaunchDecider.decide(normal.copy(bootedSinceLastSession = true))).isEqualTo(StartScreen.Library())
    }

    @Test fun noTabsOpensLibrary() {
        assertThat(LaunchDecider.decide(normal.copy(lastTab = null))).isEqualTo(StartScreen.Library())
    }

    @Test fun launchWithMissingPackOpensLibraryWithNotice() {
        assertThat(LaunchDecider.decide(normal.copy(lastTabPackOk = false)))
            .isEqualTo(StartScreen.Library(LaunchDecider.MISSING_NOTICE))
    }

    @Test fun aMissingFileIsNotResumableEvenWhenTheRowIsOk() {
        assertThat(isPackResumable(PackStatus.OK, "/sd/p.zim") { false }).isFalse()
        assertThat(isPackResumable(PackStatus.OK, "/sd/p.zim") { true }).isTrue()
        assertThat(isPackResumable(PackStatus.MISSING, "/sd/p.zim") { true }).isFalse()
    }

    @Test fun aBookIsResumableOnlyWhenOkAndItsFileExists() {
        val ok = book("book:granger:1")
        assertThat(isBookResumable(ok) { true }).isTrue()
        assertThat(isBookResumable(ok) { false }).isFalse()
        assertThat(isBookResumable(book("book:granger:1", status = PackStatus.MISSING)) { true }).isFalse()
        assertThat(isBookResumable(null) { true }).isFalse()
    }
}
