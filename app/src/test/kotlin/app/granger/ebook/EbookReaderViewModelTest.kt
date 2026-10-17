package app.granger.ebook

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.granger.BookTarget
import app.granger.FakeBookOpener
import app.granger.FakeBookshelf
import app.granger.FakeReadingSessions
import app.granger.MainDispatcherRule
import app.granger.TrackingContainer
import app.granger.testPublication
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import org.readium.r2.shared.publication.Publication
import java.io.File
import app.granger.book
import app.granger.core.library.BookPosition
import app.granger.core.library.ReadingTab
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import app.granger.core.library.BookMark
import app.granger.core.library.MarkKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EbookReaderViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val bookshelf = FakeBookshelf()
    private val sessions = FakeReadingSessions()
    private val opener = FakeBookOpener()
    private val prefsStore = ReadingPrefsStore(
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("reading-vm-test", Context.MODE_PRIVATE),
    )

    @After fun forgetOpenBook() {
        OpenBooks.current = null
    }

    private fun locator(href: String, progression: Double, total: Double, title: String = "Chapter") = Locator(
        href = Url(href)!!,
        mediaType = MediaType.XHTML,
        title = title,
        locations = Locator.Locations(progression = progression, totalProgression = total),
    )

    /** Tab 1 shows book "b", whose file exists unless [fileExists] is false. */
    private fun TestScope.reader(
        fileExists: Boolean = true,
        target: BookTarget? = null,
        appScope: CoroutineScope = this,
    ): EbookReaderViewModel {
        val file = tmp.root.resolve("b.epub").apply { if (fileExists) writeText("epub") }
        bookshelf.bookList.value = listOf(book("b", title = "The Upanishads").copy(filePath = file.path))
        sessions.tabs.value = listOf(ReadingTab(1, "b", "", "The Upanishads", 1L, onRail = true))
        return EbookReaderViewModel(1, target, bookshelf, sessions, opener, prefsStore, appScope, main.dispatcher)
            .also { testScheduler.advanceUntilIdle() }
    }

    @Test fun opensAtSavedPosition() = runTest(main.dispatcher) {
        val saved = locator("c002.xhtml", 0.4, 0.45)
        bookshelf.positionMap.value = mapOf("b" to BookPosition("b", saved.toJSON().toString(), 0.45, "Two", 0L))
        val vm = reader()
        val state = vm.state.value
        assertThat(state.loading).isFalse()
        assertThat(state.error).isNull()
        assertThat(state.title).isEqualTo("The Upanishads")
        assertThat(state.publication).isSameInstanceAs(opener.publication)
        assertThat(state.initial!!.href).isEqualTo(Url("c002.xhtml"))
        assertThat(state.initial!!.locations.progression).isEqualTo(0.4)
        assertThat(OpenBooks.current?.first).isEqualTo("b")
        // The tab now points at the book.
        assertThat(sessions.tab(1)!!.packId).isEqualTo("b")
        assertThat(sessions.tab(1)!!.title).isEqualTo("The Upanishads")
    }

    @Test fun routeTargetWinsOverSavedPosition() = runTest(main.dispatcher) {
        bookshelf.positionMap.value =
            mapOf("b" to BookPosition("b", locator("c002.xhtml", 0.4, 0.45).toJSON().toString(), 0.45, "Two", 0L))
        val vm = reader(target = BookTarget("c003.xhtml", 0.5))
        assertThat(vm.state.value.initial!!.href).isEqualTo(Url("c003.xhtml"))
    }

    @Test fun missingFileShowsBookGone() = runTest(main.dispatcher) {
        val vm = reader(fileExists = false)
        assertThat(vm.state.value.error).isEqualTo(EbookReaderViewModel.BOOK_GONE)
        assertThat(vm.state.value.loading).isFalse()
        assertThat(vm.state.value.chromeVisible).isTrue()
        assertThat(opener.opened).isEmpty()
    }

    @Test fun unopenableShowsBookDamaged() = runTest(main.dispatcher) {
        opener.publication = null
        val vm = reader()
        assertThat(vm.state.value.error).isEqualTo(EbookReaderViewModel.BOOK_DAMAGED)
        assertThat(vm.state.value.publication).isNull()
        assertThat(OpenBooks.current).isNull()
    }

    @Test fun missingTabShowsTabGone() = runTest(main.dispatcher) {
        val vm = EbookReaderViewModel(42, null, bookshelf, sessions, opener, prefsStore, this, main.dispatcher)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.error).isEqualTo(EbookReaderViewModel.TAB_GONE)
        assertThat(vm.state.value.chromeVisible).isTrue()
    }

    @Test fun locatorChangesAreSavedDebounced() = runTest(main.dispatcher) {
        val vm = reader()
        vm.onLocator(locator("c001.xhtml", 0.5, 0.15))
        advanceTimeBy(500)
        vm.onLocator(locator("c002.xhtml", 0.2, 0.4, title = "Two"))
        advanceTimeBy(900)
        runCurrent()
        assertThat(bookshelf.positionMap.value["b"]).isNull()
        advanceTimeBy(200)
        runCurrent()
        val saved = bookshelf.positionMap.value["b"]!!
        assertThat(saved.totalProgression).isEqualTo(0.4)
        assertThat(saved.chapterTitle).isEqualTo("Two")
        assertThat(Locator.fromJSON(JSONObject(saved.locatorJson))!!.href).isEqualTo(Url("c002.xhtml"))
        assertThat(vm.state.value.locator!!.href).isEqualTo(Url("c002.xhtml"))
    }

    @Test fun flushSavesAtOnce() = runTest(main.dispatcher) {
        val vm = reader()
        vm.onLocator(locator("c003.xhtml", 0.1, 0.7))
        vm.flush()
        runCurrent()
        assertThat(bookshelf.positionMap.value["b"]!!.totalProgression).isEqualTo(0.7)
        // The debounced save that was pending doesn't write again later.
        bookshelf.positionMap.value = emptyMap()
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(bookshelf.positionMap.value["b"]).isNull()
    }

    private fun EbookReaderViewModel.clear() = ViewModelStore().also { it.put("vm", this) }.clear()

    @Test fun clearingClosesThePublicationAndReleasesTheBook() = runTest(main.dispatcher) {
        val container = TrackingContainer()
        opener.publication = testPublication(container = container)
        val vm = reader()
        assertThat(OpenBooks.current?.first).isEqualTo("b")
        vm.clear()
        advanceUntilIdle()
        assertThat(container.closed).isTrue()
        assertThat(OpenBooks.current).isNull()
    }

    @Test fun clearingLeavesANewerReaderCurrent() = runTest(main.dispatcher) {
        val first = reader()
        val newer = EbookReaderViewModel(1, null, bookshelf, sessions, FakeBookOpener(), prefsStore, this, main.dispatcher)
        advanceUntilIdle()
        val newerFactory = newer.navigatorFactory
        first.clear()
        advanceUntilIdle()
        assertThat(OpenBooks.current?.second).isSameInstanceAs(newerFactory)
    }

    @Test fun publicationOpenedAfterClearingIsClosed() = runTest(main.dispatcher) {
        val container = TrackingContainer()
        val gate = CompletableDeferred<Unit>()
        var started = false
        val slow = object : BookOpener {
            override suspend fun open(file: File): Publication {
                started = true
                gate.await()
                return testPublication(container = container)
            }
        }
        val file = tmp.root.resolve("b.epub").apply { writeText("epub") }
        bookshelf.bookList.value = listOf(book("b").copy(filePath = file.path))
        sessions.tabs.value = listOf(ReadingTab(1, "b", "", "b", 1L, onRail = true))
        val vm = EbookReaderViewModel(1, null, bookshelf, sessions, slow, prefsStore, this, main.dispatcher)
        runCurrent()
        assertThat(started).isTrue()
        vm.clear()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(container.closed).isTrue()
        assertThat(OpenBooks.current).isNull()
    }

    @Test fun chromeStartsHiddenAndToggles() = runTest(main.dispatcher) {
        val vm = reader()
        assertThat(vm.state.value.chromeVisible).isFalse()
        vm.toggleChrome()
        assertThat(vm.state.value.chromeVisible).isTrue()
        vm.toggleChrome()
        assertThat(vm.state.value.chromeVisible).isFalse()
        vm.showChrome()
        assertThat(vm.state.value.chromeVisible).isTrue()
    }

    private fun bookmark(id: Long, href: String, position: Int, total: Double, createdAt: Long, title: String = "T") = BookMark(
        id, "b", MarkKind.BOOKMARK, locator(href, 0.1, total, title).copy(
            locations = Locator.Locations(progression = 0.1, position = position, totalProgression = total),
        ).toJSON().toString(), title, "", "", createdAt, createdAt,
    )

    @Test fun tocIsFlattenedWithDepth() = runTest(main.dispatcher) {
        opener.publication = testPublication(toc = listOf("c001.xhtml" to listOf("c002.xhtml#s1"), "c003.xhtml" to emptyList()))
        val vm = reader()
        assertThat(vm.state.value.toc.map { it.depth }).containsExactly(0, 1, 0).inOrder()
        assertThat(vm.state.value.toc.map { it.title }).containsExactly("c001.xhtml", "c002.xhtml#s1", "c003.xhtml").inOrder()
    }

    @Test fun currentChapterFollowsLocator() = runTest(main.dispatcher) {
        opener.publication = testPublication(toc = listOf("c001.xhtml" to listOf("c002.xhtml#s1"), "c003.xhtml" to emptyList()))
        val vm = reader()
        assertThat(vm.state.value.currentTocIndex).isNull()
        vm.onLocator(locator("c002.xhtml", 0.5, 0.4))
        assertThat(vm.state.value.currentTocIndex).isEqualTo(1)
        vm.onLocator(locator("c003.xhtml", 0.5, 0.8))
        assertThat(vm.state.value.currentTocIndex).isEqualTo(2)
    }

    @Test fun positionsCountComesFromThePublication() = runTest(main.dispatcher) {
        val vm = reader()
        assertThat(vm.state.value.positionsCount).isEqualTo(3)
    }

    @Test fun bookmarkTogglesAtCurrentPlace() = runTest(main.dispatcher) {
        val vm = reader()
        vm.onLocator(locator("c002.xhtml", 0.2, 0.4, "Two").copy(locations = Locator.Locations(position = 2, totalProgression = 0.4)))
        assertThat(vm.state.value.isBookmarkedHere).isFalse()
        vm.toggleBookmark()
        advanceUntilIdle()
        assertThat(bookshelf.markList.value.single().kind).isEqualTo(MarkKind.BOOKMARK)
        assertThat(bookshelf.markList.value.single().text).isEqualTo("Two")
        assertThat(vm.state.value.bookmarks).hasSize(1)
        assertThat(vm.state.value.isBookmarkedHere).isTrue()
        vm.toggleBookmark()
        advanceUntilIdle()
        assertThat(bookshelf.markList.value).isEmpty()
        assertThat(vm.state.value.isBookmarkedHere).isFalse()
    }

    @Test fun marksAreSplitAndBookmarksNewestFirst() = runTest(main.dispatcher) {
        bookshelf.markList.value = listOf(
            bookmark(1, "c001.xhtml", 1, 0.1, 100),
            bookmark(2, "c003.xhtml", 3, 0.8, 200),
            bookmark(3, "c001.xhtml", 1, 0.1, 150).copy(kind = MarkKind.HIGHLIGHT),
        )
        val vm = reader()
        assertThat(vm.state.value.bookmarks.map { it.id }).containsExactly(2L, 1L).inOrder()
        assertThat(vm.state.value.highlights.map { it.id }).containsExactly(3L)
    }

    @Test fun sliderJumpEmitsNavigation() = runTest(main.dispatcher) {
        val vm = reader()
        val seen = mutableListOf<Locator>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigation.collect { seen += it } }
        runCurrent()
        vm.goToProgression(0.9)
        advanceUntilIdle()
        assertThat(seen.single().href).isEqualTo(Url("c003.xhtml"))
    }

    @Test fun contentsTapNavigatesKeepingFragmentAndClosesPanel() = runTest(main.dispatcher) {
        val vm = reader()
        val seen = mutableListOf<Locator>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigation.collect { seen += it } }
        runCurrent()
        vm.open(Panel.CONTENTS)
        vm.goTo("c002.xhtml#s1")
        vm.goTo("missing.xhtml")
        advanceUntilIdle()
        assertThat(seen).hasSize(1)
        assertThat(seen[0].href).isEqualTo(Url("c002.xhtml"))
        assertThat(seen[0].locations.fragments).containsExactly("s1")
        assertThat(vm.state.value.panel).isNull()
    }

    @Test fun markTapNavigatesToItsLocator() = runTest(main.dispatcher) {
        val vm = reader()
        val seen = mutableListOf<Locator>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigation.collect { seen += it } }
        runCurrent()
        vm.goToMark(bookmark(1, "c003.xhtml", 3, 0.8, 1))
        advanceUntilIdle()
        assertThat(seen.single().href).isEqualTo(Url("c003.xhtml"))
    }

    @Test fun prefsChangesAreStored() = runTest(main.dispatcher) {
        val vm = reader()
        vm.updatePrefs(ReadingPrefs(theme = ReadingTheme.SEPIA, fontScale = 9.0))
        advanceUntilIdle()
        assertThat(vm.state.value.prefs.theme).isEqualTo(ReadingTheme.SEPIA)
        assertThat(vm.state.value.prefs.fontScale).isEqualTo(ReadingPrefs.MAX_FONT_SCALE)
        assertThat(prefsStore.prefs.value.theme).isEqualTo(ReadingTheme.SEPIA)
    }
}
