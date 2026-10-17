package app.granger.reader

import androidx.lifecycle.viewModelScope
import app.granger.FakeLibrary
import app.granger.FakeReadingSessions
import app.granger.FakeZimArchive
import app.granger.MainDispatcherRule
import app.granger.core.library.Bookmark
import app.granger.core.library.ReadingTab
import app.granger.core.zim.ZimUrls
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ReaderViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val library = FakeLibrary().apply {
        archives["p"] = FakeZimArchive("p", setOf("index", "C++", "What_is_this?", "Photosynthesis"))
    }

    private val sessions = FakeReadingSessions()

    /** Opens tab 1 on [path] in pack "p"; an empty path is a pack opened without an article. */
    private fun TestScope.reader(path: String? = null, appScope: CoroutineScope = this): ReaderViewModel {
        sessions.tabs.value = listOf(ReadingTab(1, "p", path.orEmpty(), path.orEmpty(), 1L, onRail = true))
        return ReaderViewModel(1, library, sessions, appScope, main.dispatcher).also { testScheduler.advanceUntilIdle() }
    }

    private fun url(path: String, fragment: String? = null) = ZimUrls.forEntry("p", path, fragment)

    @Test fun opensTheTabsPath() = runTest(main.dispatcher) {
        assertThat(reader("C++").state.value.url).isEqualTo(url("C++"))
    }

    @Test fun fragmentGoesIntoTheFirstUrl() = runTest(main.dispatcher) {
        sessions.tabs.value = listOf(ReadingTab(1, "p", "Photosynthesis", "Photosynthesis", 1L, onRail = true))
        val vm = ReaderViewModel(1, library, sessions, this, main.dispatcher, fragment = "History")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.url).isEqualTo(url("Photosynthesis", "History"))
        assertThat(vm.state.value.url).endsWith("#History")
    }

    @Test fun missingTabShowsTabGone() = runTest(main.dispatcher) {
        val vm = ReaderViewModel(42, library, sessions, this, main.dispatcher)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.error).isEqualTo(ReaderViewModel.TAB_GONE)
        assertThat(vm.state.value.url).isNull()
    }

    @Test fun pageShownUpdatesTheTab() = runTest(main.dispatcher) {
        val vm = reader("C++")
        vm.onPageShown(url("Photosynthesis"), "Photosynthesis")
        testScheduler.advanceUntilIdle()
        val tab = sessions.tab(1)!!
        assertThat(tab.path).isEqualTo("Photosynthesis")
        assertThat(tab.title).isEqualTo("Photosynthesis")
    }

    @Test fun chromeStartsHiddenAndToggles() = runTest(main.dispatcher) {
        val vm = reader("C++")
        assertThat(vm.state.value.chromeVisible).isFalse()
        vm.toggleChrome()
        assertThat(vm.state.value.chromeVisible).isTrue()
        vm.toggleChrome()
        assertThat(vm.state.value.chromeVisible).isFalse()
    }

    @Test fun showChromeBringsItUpAndIsIdempotent() = runTest(main.dispatcher) {
        val vm = reader("C++")
        vm.showChrome()
        assertThat(vm.state.value.chromeVisible).isTrue()
        vm.showChrome()
        assertThat(vm.state.value.chromeVisible).isTrue()
    }

    @Test fun errorsKeepTheChromeVisibleSoTheUserCanLeave() = runTest(main.dispatcher) {
        // A back stack restored after the OS reclaimed the app can reopen a tab whose pack has since gone.
        library.archives.clear()
        val vm = reader("C++")
        assertThat(vm.state.value.chromeVisible).isTrue()
        vm.toggleChrome()
        assertThat(vm.state.value.chromeVisible).isTrue()
    }

    @Test fun findOpensTheChromeAndHidingTheChromeEndsIt() = runTest(main.dispatcher) {
        val vm = reader("C++")
        vm.startFind()
        assertThat(vm.state.value.findQuery).isEqualTo("")
        assertThat(vm.state.value.chromeVisible).isTrue()
        vm.setFindQuery("light")
        assertThat(vm.state.value.findQuery).isEqualTo("light")
        vm.endFind()
        assertThat(vm.state.value.findQuery).isNull()
        vm.startFind()
        vm.toggleChrome()
        assertThat(vm.state.value.chromeVisible).isFalse()
        assertThat(vm.state.value.findQuery).isNull()
    }

    @Test fun aRedirectPathOpensItsCanonicalArticle() = runTest(main.dispatcher) {
        library.archives["p"] = FakeZimArchive("p", setOf("index", "Photosynthesis"), redirects = mapOf("Plants" to "Photosynthesis"))
        assertThat(reader("Plants").state.value.url).isEqualTo(url("Photosynthesis"))
    }

    @Test fun aLastReadRedirectAlsoOpensItsCanonicalArticle() = runTest(main.dispatcher) {
        library.archives["p"] = FakeZimArchive("p", setOf("index", "Photosynthesis"), redirects = mapOf("Plants" to "Photosynthesis"))
        library.lastRead["p"] = "Plants"
        assertThat(reader().state.value.url).isEqualTo(url("Photosynthesis"))
    }

    @Test fun opensTheMainPageOnFirstVisit() = runTest(main.dispatcher) {
        assertThat(reader().state.value.url).isEqualTo(url("index"))
    }

    @Test fun opensTheLastArticleReadWhenNoneIsRequested() = runTest(main.dispatcher) {
        library.lastRead["p"] = "Photosynthesis"
        assertThat(reader().state.value.url).isEqualTo(url("Photosynthesis"))
    }

    @Test fun unavailablePackShowsAnError() = runTest(main.dispatcher) {
        library.archives.clear()
        val state = reader("C++").state.value
        assertThat(state.error).isEqualTo(ReaderViewModel.PACK_UNAVAILABLE)
        assertThat(state.url).isNull()
    }

    @Test fun packWithoutMainPageShowsAnError() = runTest(main.dispatcher) {
        library.archives["p"] = FakeZimArchive("p", emptySet(), mainPath = null)
        assertThat(reader().state.value.error).isEqualTo(ReaderViewModel.NO_MAIN_PAGE)
    }

    @Test fun pageShownSetsTitleAndBookmarkState() = runTest(main.dispatcher) {
        library.bookmarkList.value = listOf(Bookmark("p", "C++", "C++", 0L))
        val vm = reader()
        vm.onPageShown(url("C++"), "C++")
        testScheduler.advanceUntilIdle()
        val state = vm.state.value
        assertThat(state.page).isEqualTo(ReaderViewModel.Page("C++", "C++"))
        assertThat(state.bookmarked).isTrue()
        assertThat(state.url).isEqualTo(url("C++"))
    }

    @Test fun titleFallsBackToThePath() = runTest(main.dispatcher) {
        val vm = reader()
        vm.onPageShown(url("What_is_this?"), null)
        assertThat(vm.state.value.page!!.title).isEqualTo("What is this?")
    }

    @Test fun pageShownReturnsTheSavedPositionUnlessThereIsAFragment() = runTest(main.dispatcher) {
        library.positions["p" to "Photosynthesis"] = 0.6f
        val vm = reader()
        assertThat(vm.onPageShown(url("Photosynthesis"), "Photosynthesis")).isEqualTo(0.6f)
        assertThat(vm.onPageShown(url("Photosynthesis", "s3"), "Photosynthesis")).isNull()
        assertThat(vm.onPageShown(url("C++"), "C++")).isNull()
    }

    @Test fun pagesFromOtherPacksAreIgnored() = runTest(main.dispatcher) {
        val vm = reader()
        assertThat(vm.onPageShown(ZimUrls.forEntry("other", "index"), "x")).isNull()
        assertThat(vm.state.value.page).isNull()
        vm.savePosition(ZimUrls.forEntry("other", "index"), 0.5f)
        vm.savePosition(null, 0.5f)
        testScheduler.advanceUntilIdle()
        assertThat(library.positions).isEmpty()
    }

    @Test fun toggleBookmarkAddsThenRemoves() = runTest(main.dispatcher) {
        val vm = reader()
        vm.onPageShown(url("C++"), "C++")
        testScheduler.advanceUntilIdle()
        vm.toggleBookmark()
        testScheduler.advanceUntilIdle()
        assertThat(library.bookmarkList.value.map { it.title }).containsExactly("C++")
        assertThat(vm.state.value.bookmarked).isTrue()
        vm.toggleBookmark()
        testScheduler.advanceUntilIdle()
        assertThat(library.bookmarkList.value).isEmpty()
        assertThat(vm.state.value.bookmarked).isFalse()
    }

    @Test fun positionWritesOutliveTheScreen() = runTest(main.dispatcher) {
        // The write goes to the app scope, not viewModelScope, so leaving the reader can't cancel it.
        val appScope = CoroutineScope(Job() + main.dispatcher)
        val vm = reader(appScope = appScope)
        vm.savePosition(url("Photosynthesis"), 0.42f)
        vm.viewModelScope.cancel() // what leaving the screen does
        testScheduler.advanceUntilIdle()
        assertThat(library.positions["p" to "Photosynthesis"]).isEqualTo(0.42f)
        appScope.cancel()
    }
}
