package app.granger.launch

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
}
