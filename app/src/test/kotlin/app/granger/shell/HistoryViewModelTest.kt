package app.granger.shell

import app.granger.FakeBookshelf
import app.granger.FakeLibrary
import app.granger.FakeRailHidden
import app.granger.FakeRailPins
import app.granger.book
import app.granger.FakeReadingSessions
import app.granger.MainDispatcherRule
import app.granger.core.library.Bookmark
import app.granger.core.library.HistoryEntry
import app.granger.core.library.PackStatus
import app.granger.core.library.RecentSearch
import app.granger.pack
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class HistoryViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val sessions = FakeReadingSessions()
    private val library = FakeLibrary()
    private val bookshelf = FakeBookshelf()
    private val pins = FakeRailPins()
    private val hidden = FakeRailHidden()

    @Test fun historyNewestFirstWithPackTitles() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("a", "Classics"), pack("b", "WikiMed", PackStatus.MISSING))
        sessions.historyList.value = listOf(
            HistoryEntry("a", "Hamlet", "Hamlet", 1L),
            HistoryEntry("gone", "X", "X", 3L),
            HistoryEntry("b", "Aspirin", "Aspirin", 2L),
        )
        val ui = HistoryViewModel(sessions, library, bookshelf, pins, hidden).ui.filterNotNull().first()
        assertThat(ui.history).containsExactly(
            HistoryViewModel.Row("gone", "X", "X", HistoryViewModel.REMOVED_PACK, available = false),
            HistoryViewModel.Row("b", "Aspirin", "Aspirin", "WikiMed", available = false),
            HistoryViewModel.Row("a", "Hamlet", "Hamlet", "Classics", available = true),
        ).inOrder()
    }

    @Test fun savedListsStarredArticles() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("a", "Classics"))
        library.bookmarkList.value = listOf(Bookmark("a", "Hamlet", "Hamlet", 3L), Bookmark("gone", "X", "X", 1L))
        val ui = HistoryViewModel(sessions, library, bookshelf, pins, hidden).ui.filterNotNull().first()
        assertThat(ui.saved).containsExactly(
            HistoryViewModel.Row("a", "Hamlet", "Hamlet", "Classics", available = true),
            HistoryViewModel.Row("gone", "X", "X", HistoryViewModel.REMOVED_PACK, available = false),
        ).inOrder()
    }

    @Test fun openingFromHistoryAddsATab() = runTest(main.dispatcher) {
        val row = HistoryViewModel.Row("a", "Hamlet", "Hamlet", "Classics", available = true)
        val id = HistoryViewModel(sessions, library, bookshelf, pins, hidden).open(row)
        val tab = sessions.tabs.value.single()
        assertThat(tab.id).isEqualTo(id)
        assertThat(listOf(tab.packId, tab.path, tab.title)).containsExactly("a", "Hamlet", "Hamlet").inOrder()
        assertThat(tab.onRail).isTrue()
    }

    @Test fun historyFilterMatchesTitleOrPack() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("a", "Classics"), pack("b", "WikiMed"))
        sessions.historyList.value = listOf(
            HistoryEntry("a", "Hamlet", "Hamlet", 3L),
            HistoryEntry("b", "Aspirin", "Aspirin", 2L),
            HistoryEntry("b", "Ibuprofen", "Ibuprofen", 1L),
        )
        library.bookmarkList.value = listOf(Bookmark("a", "Hamlet", "Hamlet", 3L))
        val vm = HistoryViewModel(sessions, library, bookshelf, pins, hidden)
        val ui = backgroundScope.launch { vm.ui.collect {} }
        testScheduler.advanceUntilIdle()
        vm.setFilter("HAM")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value!!.history.map { it.title }).containsExactly("Hamlet")
        vm.setFilter("wikimed")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value!!.history.map { it.title }).containsExactly("Aspirin", "Ibuprofen")
        assertThat(vm.ui.value!!.saved.map { it.title }).containsExactly("Hamlet")
        vm.setFilter("")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value!!.history).hasSize(3)
        ui.cancel()
    }

    @Test fun bookHistoryRowUsesBookTitle() = runTest(main.dispatcher) {
        bookshelf.bookList.value = listOf(book("book:granger:1", "Emma", author = "Jane Austen"))
        sessions.historyList.value = listOf(
            HistoryEntry("book:granger:1", "", "old title", 2L),
            HistoryEntry("book:granger:gone", "", "Lost", 1L),
        )
        val vm = HistoryViewModel(sessions, library, bookshelf, pins, hidden)
        val ui = vm.ui.filterNotNull().first()
        assertThat(ui.history).containsExactly(
            HistoryViewModel.Row("book:granger:1", "", "Emma", "Jane Austen", available = true),
            HistoryViewModel.Row("book:granger:gone", "", "Lost", HistoryViewModel.REMOVED_BOOK, available = false),
        ).inOrder()
        val id = vm.open(ui.history.first())
        assertThat(sessions.tabs.value.single { it.id == id }.packId).isEqualTo("book:granger:1")
    }

    @Test fun clearKeepsPinnedAndCurrent() = runTest(main.dispatcher) {
        val pinned = sessions.openTab("a", "Owls", "Owls")
        val current = sessions.openTab("a", "Bats", "Bats")
        sessions.openTab("a", "Cats", "Cats")
        sessions.searchRows.value = listOf(RecentSearch("holst", 2), RecentSearch("tides", 1))
        pins.pin("tab:$pinned")
        pins.pin("search:holst")
        val vm = HistoryViewModel(sessions, library, bookshelf, pins, hidden)
        vm.clear(keepTabIds = setOf(current))
        testScheduler.advanceUntilIdle()
        assertThat(sessions.historyList.value).isEmpty()
        assertThat(sessions.searchRows.value.map { it.query }).containsExactly("holst")
        assertThat(sessions.tabs.value.filter { it.onRail }.map { it.id }).containsExactly(pinned, current)
        assertThat(vm.ui.filterNotNull().first().history).isEmpty()
    }

    @Test fun clearForgetsPlaysAndMovesTheRailsCutOff() = runTest(main.dispatcher) {
        var forgot = 0
        hidden.hide("chat:3", 40L)
        val vm = HistoryViewModel(sessions, library, bookshelf, pins, hidden, forgetPlays = { forgot++ }, now = { 5_000L })
        vm.clear(keepTabIds = emptySet())
        testScheduler.advanceUntilIdle()
        assertThat(forgot).isEqualTo(1)
        assertThat(hidden.clearedAt.value).isEqualTo(5_000L)
        assertThat(hidden.hidden.value).isEmpty()
    }

    /** Plan U3b, Neal's walkthrough: only the page you came from stays, not every reader tab under History. */
    @Test fun clearKeepsOnlyTheNearestReaderTabPlusPins() = runTest(main.dispatcher) {
        val pinned = sessions.openTab("a", "Owls", "Owls")
        val older = sessions.openTab("a", "Bats", "Bats")
        val nearest = sessions.openTab("a", "Cats", "Cats")
        pins.pin("tab:$pinned")
        // Home, the older reader, Search, the nearest reader, then History on top.
        val kept = HistoryViewModel.keptTab(listOf(null, older, null, nearest, null))
        assertThat(kept).containsExactly(nearest)
        assertThat(HistoryViewModel.keptTab(listOf(null, null))).isEmpty()
        HistoryViewModel(sessions, library, bookshelf, pins, hidden).clear(kept)
        testScheduler.advanceUntilIdle()
        assertThat(sessions.tabs.value.filter { it.onRail }.map { it.id }).containsExactly(pinned, nearest)
    }

    /** Plan U3c walkthrough: a reader under Home stayed on the rail after Clear history, though it wasn't on screen. */
    @Test fun clearFromAnotherScreenKeepsNoReaderTab() = runTest(main.dispatcher) {
        val buried = sessions.openTab("a", "Bats", "Bats")
        sessions.openTab("a", "Cats", "Cats")
        // The reader, then Home over it, then History.
        val kept = HistoryViewModel.keptTab(listOf(null, buried, null, null))
        assertThat(kept).isEmpty()
        assertThat(HistoryViewModel.keptTab(listOf(null))).isEmpty()
        assertThat(HistoryViewModel.keptTab(emptyList())).isEmpty()
        HistoryViewModel(sessions, library, bookshelf, pins, hidden).clear(kept)
        testScheduler.advanceUntilIdle()
        assertThat(sessions.tabs.value.filter { it.onRail }).isEmpty()
    }
}
