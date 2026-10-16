package app.granger.shell

import app.granger.FakeLibrary
import app.granger.FakeReadingSessions
import app.granger.MainDispatcherRule
import app.granger.core.library.Bookmark
import app.granger.core.library.HistoryEntry
import app.granger.core.library.PackStatus
import app.granger.pack
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class HistoryViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val sessions = FakeReadingSessions()
    private val library = FakeLibrary()

    @Test fun historyNewestFirstWithPackTitles() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("a", "Classics"), pack("b", "WikiMed", PackStatus.MISSING))
        sessions.historyList.value = listOf(
            HistoryEntry("a", "Hamlet", "Hamlet", 1L),
            HistoryEntry("gone", "X", "X", 3L),
            HistoryEntry("b", "Aspirin", "Aspirin", 2L),
        )
        val ui = HistoryViewModel(sessions, library).ui.filterNotNull().first()
        assertThat(ui.history).containsExactly(
            HistoryViewModel.Row("gone", "X", "X", HistoryViewModel.REMOVED_PACK, available = false),
            HistoryViewModel.Row("b", "Aspirin", "Aspirin", "WikiMed", available = false),
            HistoryViewModel.Row("a", "Hamlet", "Hamlet", "Classics", available = true),
        ).inOrder()
    }

    @Test fun savedListsStarredArticles() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("a", "Classics"))
        library.bookmarkList.value = listOf(Bookmark("a", "Hamlet", "Hamlet", 3L), Bookmark("gone", "X", "X", 1L))
        val ui = HistoryViewModel(sessions, library).ui.filterNotNull().first()
        assertThat(ui.saved).containsExactly(
            HistoryViewModel.Row("a", "Hamlet", "Hamlet", "Classics", available = true),
            HistoryViewModel.Row("gone", "X", "X", HistoryViewModel.REMOVED_PACK, available = false),
        ).inOrder()
    }

    @Test fun openingFromHistoryAddsATab() = runTest(main.dispatcher) {
        val row = HistoryViewModel.Row("a", "Hamlet", "Hamlet", "Classics", available = true)
        val id = HistoryViewModel(sessions, library).open(row)
        val tab = sessions.tabs.value.single()
        assertThat(tab.id).isEqualTo(id)
        assertThat(listOf(tab.packId, tab.path, tab.title)).containsExactly("a", "Hamlet", "Hamlet").inOrder()
        assertThat(tab.onRail).isTrue()
    }
}
