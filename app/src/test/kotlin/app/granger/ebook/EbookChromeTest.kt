package app.granger.ebook

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.granger.FakeBookOpener
import app.granger.FakeBookshelf
import app.granger.FakeReadingSessions
import app.granger.MainDispatcherRule
import app.granger.book
import app.granger.core.library.ReadingTab
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The e-book's chrome (Plan U3c Decision 5): a page turned by hand slides it away and a middle tap brings it back;
 * the page moving for any other reason (the slider, Contents, the voice, the navigator settling) leaves it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EbookChromeTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private var clock = 10_000L
    private val bookshelf = FakeBookshelf()
    private val sessions = FakeReadingSessions()
    private val prefsStore = ReadingPrefsStore(
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("ebook-chrome-test", Context.MODE_PRIVATE),
    )

    private fun page(progression: Double, href: String = "c001.xhtml") = Locator(
        href = Url(href)!!,
        mediaType = MediaType.XHTML,
        title = "Chapter",
        locations = Locator.Locations(progression = progression, totalProgression = progression / 2),
    )

    private fun TestScope.reader(): EbookReaderViewModel {
        val file = tmp.root.resolve("b.epub").apply { writeText("epub") }
        bookshelf.bookList.value = listOf(book("b", title = "The Upanishads").copy(filePath = file.path))
        sessions.tabs.value = listOf(ReadingTab(1, "b", "", "The Upanishads", 1L, onRail = true))
        return EbookReaderViewModel(
            1, null, bookshelf, sessions, FakeBookOpener(), prefsStore, this, main.dispatcher, FakeBookSearcher(), SavedStateHandle(),
            now = { clock },
        ).also {
            testScheduler.advanceUntilIdle()
            it.onLocator(page(0.1))
        }
    }

    /** A swipe or an edge tap: the finger down, then up, and the turn lands a moment later. */
    private fun EbookReaderViewModel.turnTo(locator: Locator) {
        onPageTouch(down = true)
        clock += 120
        onPageTouch(down = false)
        clock += 300
        onLocator(locator)
    }

    @Test fun aPageTurnByHandHidesTheChromeAndAMiddleTapBringsItBack() = runTest(main.dispatcher) {
        val vm = reader()
        assertThat(vm.state.value.chromeVisible).isTrue()
        vm.turnTo(page(0.2))
        assertThat(vm.state.value.chromeVisible).isFalse()
        vm.turnTo(page(0.3))
        assertThat(vm.state.value.chromeVisible).isFalse()
        vm.toggleChrome()
        assertThat(vm.state.value.chromeVisible).isTrue()
        // Into the next chapter too.
        vm.turnTo(page(0.0, href = "c002.xhtml"))
        assertThat(vm.state.value.chromeVisible).isFalse()
    }

    @Test fun aMoveWithNoFingerOnThePageKeepsTheChrome() = runTest(main.dispatcher) {
        // The slider, a Contents row, a search hit: their taps are on the chrome, not the page.
        val vm = reader()
        vm.onLocator(page(0.6))
        assertThat(vm.state.value.chromeVisible).isTrue()
        // A touch long before the move doesn't count.
        vm.onPageTouch(down = true)
        vm.onPageTouch(down = false)
        clock += EbookReaderViewModel.PAGE_TURN_GRACE_MS + 1
        vm.onLocator(page(0.7))
        assertThat(vm.state.value.chromeVisible).isTrue()
    }

    @Test fun theSamePlaceAgainIsNoTurn() = runTest(main.dispatcher) {
        // The navigator reporting where it already is (settling, laying out again) while a finger is down.
        val vm = reader()
        vm.turnTo(page(0.1))
        assertThat(vm.state.value.chromeVisible).isTrue()
    }

    @Test fun theVoicesTurnKeepsTheChrome() = runTest(main.dispatcher) {
        val vm = reader()
        vm.onPageTouch(down = true)
        vm.onPageTouch(down = false)
        vm.voiceMoved()
        vm.onLocator(page(0.4))
        assertThat(vm.state.value.chromeVisible).isTrue()
    }

    @Test fun aPanelOverThePageKeepsTheChrome() = runTest(main.dispatcher) {
        val vm = reader()
        vm.open(Panel.SETTINGS)
        vm.turnTo(page(0.2))
        assertThat(vm.state.value.chromeVisible).isTrue()
        vm.open(null)
        vm.turnTo(page(0.3))
        assertThat(vm.state.value.chromeVisible).isFalse()
    }

    @Test fun aSwipeStillUnderwayCounts() = runTest(main.dispatcher) {
        // The finger hasn't lifted yet: a long drag that already turned the page.
        val vm = reader()
        vm.onPageTouch(down = true)
        clock += 5_000
        vm.onLocator(page(0.2))
        assertThat(vm.state.value.chromeVisible).isFalse()
    }
}
