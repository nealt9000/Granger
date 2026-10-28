package app.granger.reader

import androidx.lifecycle.viewModelScope
import app.granger.FakeLibrary
import app.granger.FakeReadingSessions
import app.granger.FakeZimArchive
import app.granger.MainDispatcherRule
import app.granger.core.library.Bookmark
import app.granger.core.library.ReadingTab
import app.granger.core.zim.ZimHit
import app.granger.core.zim.ZimUrls
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    // ---- read aloud (Plan V1) ----

    private val article = """
        <html><body><h1 id="t">Photosynthesis</h1><p>Plants make sugar. They use light.</p><h2 id="Overview">Overview</h2><p>Second paragraph here.</p></body></html>
    """.trimIndent()

    private fun TestScope.readingReader(hub: app.granger.player.FakePlayerHub): ReaderViewModel {
        library.archives["p"] = FakeZimArchive("p", setOf("index", "Photosynthesis", "Other"), contents = mapOf("Photosynthesis" to article))
        sessions.tabs.value = listOf(ReadingTab(1, "p", "Photosynthesis", "Photosynthesis", 1L, onRail = true))
        val vm = ReaderViewModel(1, library, sessions, this, main.dispatcher, player = hub)
        testScheduler.advanceUntilIdle()
        launch { vm.onPageShown(url("Photosynthesis"), "Photosynthesis") }
        testScheduler.advanceUntilIdle()
        return vm
    }

    private fun voiceUiFor(key: String) = app.granger.core.player.PlayerUi(
        app.granger.core.player.NowPlaying.Voice(key, "Photosynthesis", null, "", 0, 1f, app.granger.core.speech.VoiceId.Granger("v")),
        playing = true, positionMs = 0L, durationMs = 0L, sleepRemainingMs = null,
    )

    // ---- citations (Plan R3) ----

    @Test fun aCitedQuoteYieldsACiteRangeUntilTheReaderLeavesThePage() = runTest(main.dispatcher) {
        library.archives["p"] = FakeZimArchive("p", setOf("index", "Photosynthesis", "Other"), contents = mapOf("Photosynthesis" to article))
        sessions.tabs.value = listOf(ReadingTab(1, "p", "Photosynthesis", "Photosynthesis", 1L, onRail = true))
        val vm = ReaderViewModel(1, library, sessions, this, main.dispatcher, fragment = "Overview", quote = "Second paragraph here.")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.url).isEqualTo(url("Photosynthesis", "Overview"))
        assertThat(vm.state.value.cite).isEqualTo(CiteRange("g3", "Second paragraph here.", 0, 22))
        vm.onPageShown(url("Photosynthesis", "Overview"), "Photosynthesis")
        assertThat(vm.state.value.cite).isNotNull()
        vm.onPageShown(url("Other"), "Other")
        assertThat(vm.state.value.cite).isNull()
        vm.onPageShown(url("Photosynthesis"), "Photosynthesis")
        assertThat(vm.state.value.cite).isNull()
    }

    @Test fun noQuoteOrAQuoteNotOnThePageMarksNothing() = runTest(main.dispatcher) {
        library.archives["p"] = FakeZimArchive("p", setOf("index", "Photosynthesis"), contents = mapOf("Photosynthesis" to article))
        sessions.tabs.value = listOf(ReadingTab(1, "p", "Photosynthesis", "Photosynthesis", 1L, onRail = true))
        val plain = ReaderViewModel(1, library, sessions, this, main.dispatcher, fragment = "Overview")
        val missing = ReaderViewModel(1, library, sessions, this, main.dispatcher, quote = "Not on this page at all, anywhere.")
        testScheduler.advanceUntilIdle()
        assertThat(plain.state.value.cite).isNull()
        assertThat(missing.state.value.cite).isNull()
    }

    @Test fun readAloudStartsAtTheFirstVisibleBlockOrTheTop() = runTest(main.dispatcher) {
        val hub = app.granger.player.FakePlayerHub()
        val vm = readingReader(hub)
        vm.readAloud(BlockRef("g1", "Plants make sugar."))
        testScheduler.advanceUntilIdle()
        assertThat(hub.calls).containsExactly("readAloud:pack:p/Photosynthesis@pack:p/Photosynthesis#g1:0")
        hub.calls.clear()
        vm.readAloud(null)
        testScheduler.advanceUntilIdle()
        assertThat(hub.calls).containsExactly("readAloud:pack:p/Photosynthesis@pack:p/Photosynthesis#t:0")
    }

    @Test fun readFromStartsAtTheSelectionsSentenceOrJumpsWhenAlreadyReading() = runTest(main.dispatcher) {
        val hub = app.granger.player.FakePlayerHub()
        val vm = readingReader(hub)
        vm.readFrom(BlockRef("g1", "Plants make sugar.", offset = 20))
        testScheduler.advanceUntilIdle()
        assertThat(hub.calls).containsExactly("readAloud:pack:p/Photosynthesis@pack:p/Photosynthesis#g1:1")
        hub.calls.clear()
        hub.ui.value = voiceUiFor("pack:p/Photosynthesis")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.readingThisPage).isTrue()
        vm.readFrom(BlockRef("g3", "Second paragraph here.", offset = 0))
        testScheduler.advanceUntilIdle()
        assertThat(hub.calls).containsExactly("jumpTo:pack:p/Photosynthesis#g3:0")
    }

    @Test fun theSentenceIsMarkedAndScrollingAwayStopsFollowingUntilFollowIsTapped() = runTest(main.dispatcher) {
        val hub = app.granger.player.FakePlayerHub()
        val vm = readingReader(hub)
        hub.ui.value = voiceUiFor("pack:p/Photosynthesis")
        val utterance = app.granger.core.speech.Utterance(
            app.granger.core.speech.ReadAddress.Article("p", "Photosynthesis", "g1", 1), app.granger.core.speech.UtteranceKind.SENTENCE,
            "They use light.", paragraph = 1, pauseAfterMs = 700, start = 19, end = 34,
        )
        hub.voiceProgress.value = app.granger.core.player.voice.VoiceProgress(utterance.address, utterance, 2)
        testScheduler.advanceUntilIdle()
        val reading = vm.state.value.reading!!
        assertThat(reading.address.blockId).isEqualTo("g1")
        assertThat(reading.start).isEqualTo(19)
        assertThat(reading.end).isEqualTo(34)
        assertThat(reading.following).isTrue()
        vm.onUserScroll()
        assertThat(vm.state.value.reading!!.following).isFalse()
        vm.follow()
        assertThat(vm.state.value.reading!!.following).isTrue()
        // The voice moving to another page clears the mark here.
        hub.ui.value = voiceUiFor("pack:p/Other")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.reading).isNull()
        assertThat(vm.state.value.readingThisPage).isFalse()
    }

    @Test fun followingALinkWhileReadingStopsTheVoice() = runTest(main.dispatcher) {
        val hub = app.granger.player.FakePlayerHub()
        val vm = readingReader(hub)
        hub.ui.value = voiceUiFor("pack:p/Photosynthesis")
        testScheduler.advanceUntilIdle()
        vm.onPageVisited(url("Photosynthesis"))            // a jump within the page: nothing
        assertThat(hub.calls).isEmpty()
        vm.onPageVisited(url("Other"))
        testScheduler.advanceUntilIdle()
        assertThat(hub.calls).containsExactly("stopVoice")
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

    @Test fun theTabKeepsThePagesTitleWithoutItsSite() = runTest(main.dispatcher) {
        val vm = reader("C++")
        vm.onPageShown(url("Photosynthesis"), "Photosynthesis - Wikipedia")
        testScheduler.advanceUntilIdle()
        assertThat(sessions.tab(1)!!.title).isEqualTo("Photosynthesis")
        assertThat(vm.state.value.page!!.title).isEqualTo("Photosynthesis")
        // The pack's own title as a suffix goes too; the fake pack is called "p".
        vm.onPageShown(url("C++"), "C++ - p")
        testScheduler.advanceUntilIdle()
        assertThat(sessions.tab(1)!!.title).isEqualTo("C++")
    }

    @Test fun thePacksMainPageIsCalledByThePack() = runTest(main.dispatcher) {
        val vm = reader("C++")
        // The fake pack "p" has its main entry at "index".
        vm.onPageShown(url("index"), "Welcome to the library")
        testScheduler.advanceUntilIdle()
        assertThat(sessions.tab(1)!!.title).isEqualTo("p")
        vm.onPageShown(url("Photosynthesis"), "Main Page")
        testScheduler.advanceUntilIdle()
        assertThat(sessions.tab(1)!!.title).isEqualTo("p")
    }

    /** Shows each of [paths] in turn, as following links does. */
    private fun TestScope.show(vm: ReaderViewModel, vararg paths: String) {
        for (path in paths) {
            launch { vm.onPageShown(url(path), path) }
            testScheduler.advanceUntilIdle()
        }
    }

    private suspend fun stack() = sessions.tab(1)!!.backStack.split('\n')

    @Test fun followingALinkPushesThePath() = runTest(main.dispatcher) {
        val vm = reader("C++")
        show(vm, "C++", "Photosynthesis")
        assertThat(stack()).containsExactly("C++", "Photosynthesis").inOrder()
        assertThat(sessions.tab(1)!!.path).isEqualTo("Photosynthesis")
        // A reload of the same page isn't another step back.
        show(vm, "Photosynthesis")
        assertThat(stack()).containsExactly("C++", "Photosynthesis").inOrder()
    }

    @Test fun aVisitIsRecordedBeforeThePageFinishes() = runTest(main.dispatcher) {
        // Back pressed while the next page is still loading must go back to the page the link was on.
        val vm = reader("C++")
        show(vm, "C++")
        vm.onPageVisited(url("Photosynthesis"))
        testScheduler.advanceUntilIdle()
        assertThat(stack()).containsExactly("C++", "Photosynthesis").inOrder()
        // A jump to a section of the page shown isn't a new page.
        show(vm, "Photosynthesis")
        vm.onPageVisited(url("Photosynthesis", "s3"))
        testScheduler.advanceUntilIdle()
        assertThat(stack()).containsExactly("C++", "Photosynthesis").inOrder()
        assertThat(sessions.tab(1)!!.title).isEqualTo("Photosynthesis")
    }

    @Test fun backWithWebHistoryGoesBackInTheWebView() = runTest(main.dispatcher) {
        val vm = reader("C++")
        show(vm, "C++", "Photosynthesis")
        assertThat(vm.back(webCurrent = url("Photosynthesis"), webPrevious = url("C++"))).isEqualTo(ReaderViewModel.BackStep.InWebView)
        // The stored stack steps back with it, so the page the WebView goes back to isn't pushed again.
        assertThat(stack()).containsExactly("C++")
        show(vm, "C++")
        assertThat(stack()).containsExactly("C++")
    }

    @Test fun backOutOfASectionKeepsTheStack() = runTest(main.dispatcher) {
        val vm = reader("C++")
        show(vm, "C++", "Photosynthesis")
        assertThat(vm.back(webCurrent = url("Photosynthesis", "s3"), webPrevious = url("Photosynthesis")))
            .isEqualTo(ReaderViewModel.BackStep.InWebView)
        assertThat(stack()).containsExactly("C++", "Photosynthesis").inOrder()
    }

    @Test fun backWithoutWebHistoryLoadsTheStoredPreviousPath() = runTest(main.dispatcher) {
        // The WebView's history went with the destination (a rail switch) or the process.
        val vm = reader("C++")
        show(vm, "C++", "What_is_this?", "Photosynthesis")
        assertThat(vm.back(webCurrent = url("Photosynthesis"), webPrevious = null)).isEqualTo(ReaderViewModel.BackStep.Load(url("What_is_this?")))
        show(vm, "What_is_this?")
        assertThat(vm.back(webCurrent = url("What_is_this?"), webPrevious = null)).isEqualTo(ReaderViewModel.BackStep.Load(url("C++")))
        show(vm, "C++")
        assertThat(stack()).containsExactly("C++")
    }

    @Test fun backAtTheFirstPageLeavesTheReader() = runTest(main.dispatcher) {
        val vm = reader("C++")
        show(vm, "C++")
        assertThat(vm.back(webCurrent = url("C++"), webPrevious = null)).isEqualTo(ReaderViewModel.BackStep.Leave)
        assertThat(stack()).containsExactly("C++")
    }

    @Test fun backFromAnErrorLeavesTheReader() = runTest(main.dispatcher) {
        library.archives.clear()
        assertThat(reader("C++").back(webCurrent = null, webPrevious = null)).isEqualTo(ReaderViewModel.BackStep.Leave)
    }

    @Test fun homeAndRandomPushLikeLinks() = runTest(main.dispatcher) {
        library.archives["p"] = FakeZimArchive("p", setOf("index", "C++", "Photosynthesis"), random = "Photosynthesis")
        val vm = reader("C++")
        show(vm, "C++")
        launch { vm.onPageShown(vm.homeUrl()!!, "Home") }
        testScheduler.advanceUntilIdle()
        launch { vm.onPageShown(vm.randomUrl()!!, "Photosynthesis") }
        testScheduler.advanceUntilIdle()
        assertThat(stack()).containsExactly("C++", "index", "Photosynthesis").inOrder()
        assertThat(vm.back(webCurrent = url("Photosynthesis"), webPrevious = null)).isEqualTo(ReaderViewModel.BackStep.Load(url("index")))
    }

    @Test fun restoredTabKeepsItsStack() = runTest(main.dispatcher) {
        // The reader's destination was replaced (a rail switch) or the process died: a new view model for the same tab.
        show(reader("C++"), "C++", "What_is_this?", "Photosynthesis")
        val restored = ReaderViewModel(1, library, sessions, this, main.dispatcher).also { testScheduler.advanceUntilIdle() }
        assertThat(restored.state.value.url).isEqualTo(url("Photosynthesis"))
        assertThat(restored.back(webCurrent = null, webPrevious = null)).isEqualTo(ReaderViewModel.BackStep.Load(url("What_is_this?")))
    }

    @Test fun chromeStartsVisibleAndAMiddleTapToggles() = runTest(main.dispatcher) {
        // A new reader opens with its top bar and the rail showing; a middle tap goes full screen, another comes back.
        val vm = reader("C++")
        assertThat(vm.state.value.chromeVisible).isTrue()
        vm.toggleChrome()
        assertThat(vm.state.value.chromeVisible).isFalse()
        vm.toggleChrome()
        assertThat(vm.state.value.chromeVisible).isTrue()
    }

    @Test fun leavingTheScreenBringsTheChromeBackForTheReturn() = runTest(main.dispatcher) {
        // Read full screen, then left for another screen: coming back to the tab shows the chrome again.
        val vm = reader("C++")
        vm.toggleChrome()
        assertThat(vm.state.value.chromeVisible).isFalse()
        vm.onScreenLeft()
        assertThat(vm.state.value.chromeVisible).isTrue()
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

    private fun TestScope.searchable(archive: FakeZimArchive): ReaderViewModel {
        library.archives["p"] = archive
        return reader("C++").also { it.startPackSearch() }
    }

    @Test fun homeIsTheMainPageEvenWhenTheLastReadPageDiffers() = runTest(main.dispatcher) {
        library.lastRead["p"] = "C++"
        val vm = reader("C++")
        assertThat(vm.state.value.hasHome).isTrue()
        assertThat(vm.homeUrl()).isEqualTo(url("index"))
    }

    @Test fun homeIsUnavailableWithoutAMainPage() = runTest(main.dispatcher) {
        library.archives["p"] = FakeZimArchive("p", setOf("C++"), mainPath = null)
        val vm = reader("C++")
        assertThat(vm.state.value.hasHome).isFalse()
        assertThat(vm.homeUrl()).isNull()
    }

    @Test fun randomIsTheArchivesRandomPath() = runTest(main.dispatcher) {
        library.archives["p"] = FakeZimArchive("p", setOf("index", "C++"), random = "C++")
        assertThat(reader("index").randomUrl()).isEqualTo(url("C++"))
    }

    @Test fun randomWithoutAnArticleIsNull() = runTest(main.dispatcher) {
        assertThat(reader("C++").randomUrl()).isNull()
    }

    @Test fun typingShowsTitleSuggestionsAfterAPause() = runTest(main.dispatcher) {
        val vm = searchable(FakeZimArchive("p", setOf("index", "C++"), suggestions = listOf(ZimHit("C++", "C++"))))
        vm.setPackQuery("c")
        assertThat(vm.state.value.packSearch!!.hits).isEmpty()
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.packSearch!!.hits.map { it.path }).containsExactly("C++")
        assertThat(vm.state.value.packSearch!!.searched).isTrue()
    }

    @Test fun submittingAddsFullTextHitsAfterSuggestionsWithoutDuplicates() = runTest(main.dispatcher) {
        val vm = searchable(
            FakeZimArchive(
                "p", setOf("index", "C++"),
                suggestions = listOf(ZimHit("C++", "C++")),
                results = listOf(ZimHit("Other", "Other", "<b>c</b>"), ZimHit("C++", "C++", "<b>c</b>")),
            ),
        )
        vm.setPackQuery("c")
        vm.submitPackSearch()
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.packSearch!!.hits.map { it.path }).containsExactly("C++", "Other").inOrder()
    }

    @Test fun aBlankQueryClearsTheResults() = runTest(main.dispatcher) {
        val vm = searchable(FakeZimArchive("p", setOf("index"), suggestions = listOf(ZimHit("index", "Home"))))
        vm.setPackQuery("h")
        testScheduler.advanceUntilIdle()
        vm.setPackQuery("")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.packSearch).isEqualTo(ReaderViewModel.PackSearch(""))
    }

    @Test fun noHitsAreReportedAsSearched() = runTest(main.dispatcher) {
        val vm = searchable(FakeZimArchive("p", setOf("index")))
        vm.setPackQuery("zzz")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.packSearch).isEqualTo(ReaderViewModel.PackSearch("zzz", emptyList(), searched = true))
    }

    @Test fun closingTheSearchClearsItAndHidingTheChromeEndsIt() = runTest(main.dispatcher) {
        val vm = searchable(FakeZimArchive("p", setOf("index"), suggestions = listOf(ZimHit("index", "Home"))))
        vm.setPackQuery("h")
        testScheduler.advanceUntilIdle()
        vm.endPackSearch()
        assertThat(vm.state.value.packSearch).isNull()
        vm.startPackSearch()
        vm.toggleChrome()
        assertThat(vm.state.value.packSearch).isNull()
    }

    @Test fun thePackTitleIsKnownForThePlaceholder() = runTest(main.dispatcher) {
        assertThat(reader("C++").state.value.packTitle).isEqualTo("p")
    }

    @Test fun redirectHitsOpenTheirRealArticleAndDedupeWithTheirTarget() = runTest(main.dispatcher) {
        val vm = searchable(
            FakeZimArchive(
                "p", setOf("index", "Photosynthesis"),
                redirects = mapOf("Photosynthetic" to "Photosynthesis"),
                suggestions = listOf(ZimHit("Photosynthetic", "Photosynthetic")),
                results = listOf(ZimHit("Photosynthesis", "Photosynthesis", "<b>x</b>")),
            ),
        )
        vm.setPackQuery("photo")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.packSearch!!.hits.map { it.path }).containsExactly("Photosynthesis")
        vm.submitPackSearch()
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.packSearch!!.hits.map { it.path }).containsExactly("Photosynthesis")
    }

    @Test fun retypingWithinThePauseSuggestsOnce() = runTest(main.dispatcher) {
        val archive = FakeZimArchive("p", setOf("index"))
        val vm = searchable(archive)
        vm.setPackQuery("p")
        vm.setPackQuery("ph")
        vm.setPackQuery("pho")
        testScheduler.advanceUntilIdle()
        assertThat(archive.suggestCalls).isEqualTo(1)
    }

    @Test fun typingAfterSubmitDropsTheStaleFullTextResults() = runTest(main.dispatcher) {
        val vm = searchable(FakeZimArchive("p", setOf("index"), results = listOf(ZimHit("Other", "Other", "s"))))
        vm.setPackQuery("c")
        vm.submitPackSearch()
        vm.setPackQuery("cx")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.packSearch!!.query).isEqualTo("cx")
        assertThat(vm.state.value.packSearch!!.hits.map { it.path }).doesNotContain("Other")
    }

    @Test fun closingWhileASearchIsInFlightLeavesNoResults() = runTest(main.dispatcher) {
        val vm = searchable(FakeZimArchive("p", setOf("index"), results = listOf(ZimHit("Other", "Other", "s"))))
        vm.setPackQuery("c")
        vm.submitPackSearch()
        vm.endPackSearch()
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.packSearch).isNull()
    }

    @Test fun theVoiceEntryFollowsThePlayerForThisPageOnly() = runTest(main.dispatcher) {
        val hub = app.granger.player.FakePlayerHub()
        val vm = readingReader(hub)
        assertThat(vm.state.value.voice).isEqualTo(app.granger.player.VoiceButton.IDLE)
        hub.ui.value = voiceUiFor("pack:p/Photosynthesis")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.voice).isEqualTo(app.granger.player.VoiceButton.READING)
        hub.ui.value = voiceUiFor("pack:p/Photosynthesis").copy(playing = false)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.voice).isEqualTo(app.granger.player.VoiceButton.PAUSED)
        assertThat(vm.state.value.readingThisPage).isTrue()
        hub.ui.value = voiceUiFor("pack:p/Other")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.voice).isEqualTo(app.granger.player.VoiceButton.IDLE)
    }

    @Test fun pauseResumeTogglesThePlayerOnlyWhileThisPageIsRead() = runTest(main.dispatcher) {
        val hub = app.granger.player.FakePlayerHub()
        val vm = readingReader(hub)
        vm.pauseResume()
        assertThat(hub.calls).isEmpty()
        hub.ui.value = voiceUiFor("pack:p/Photosynthesis")
        testScheduler.advanceUntilIdle()
        vm.pauseResume()
        assertThat(hub.calls).containsExactly("playPause")
    }
}
