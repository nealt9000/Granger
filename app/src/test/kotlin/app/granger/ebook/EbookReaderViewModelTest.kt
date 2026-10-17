package app.granger.ebook

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.granger.BookTarget
import app.granger.FakeBookOpener
import app.granger.FakeBookshelf
import app.granger.FakeReadingSessions
import app.granger.MainDispatcherRule
import app.granger.book
import app.granger.core.library.BookPosition
import app.granger.core.library.ReadingTab
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
}
