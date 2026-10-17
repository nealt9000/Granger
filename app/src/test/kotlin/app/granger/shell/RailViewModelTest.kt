package app.granger.shell

import app.granger.FakeBookshelf
import app.granger.FakeLibrary
import app.granger.book
import app.granger.core.library.BookPosition
import app.granger.core.library.ChatSummary
import app.granger.core.library.RecentSearch
import app.granger.FakeReadingSessions
import app.granger.MainDispatcherRule
import app.granger.core.library.PackStatus
import app.granger.core.library.ReadingTab
import app.granger.pack
import app.granger.research.FakeChats
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class RailViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val sessions = FakeReadingSessions()
    private val library = FakeLibrary()
    private val bookshelf = FakeBookshelf()
    private val chats = FakeChats()
    private val current = MutableStateFlow<Long?>(null)

    private fun tab(id: Long, packId: String, title: String, openedAt: Long = id) =
        ReadingTab(id, packId, title, title, openedAt, onRail = true)

    @Test fun tabsShowPackTitleAndAvailability() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("a", "Classics"), pack("b", "WikiMed", PackStatus.MISSING))
        sessions.tabs.value = listOf(tab(1, "gone", "x-ray"), tab(2, "b", "Aspirin"), tab(3, "a", "Hamlet"))
        val ui = RailViewModel(sessions, library, bookshelf, chats, current).ui.first { it.tabs.isNotEmpty() }
        assertThat(ui.tabs).containsExactly(
            RailTabUi(3, "Hamlet", "Classics", 'H', available = true, current = false, coverPath = null),
            RailTabUi(2, "Aspirin", "WikiMed", 'A', available = false, current = false, coverPath = null),
            RailTabUi(1, "x-ray", HistoryViewModel.REMOVED_PACK, 'X', available = false, current = false, coverPath = null),
        ).inOrder()
    }

    @Test fun currentTabIsMarked() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("a"))
        sessions.tabs.value = listOf(tab(1, "a", "One"), tab(2, "a", "Two"))
        val vm = RailViewModel(sessions, library, bookshelf, chats, current)
        current.value = 2
        assertThat(vm.ui.first { it.tabs.any { t -> t.current } }.tabs.single { it.current }.tabId).isEqualTo(2)
        current.value = 1
        assertThat(vm.ui.first { it.tabs.any { t -> t.current && t.tabId == 1L } }.tabs.count { it.current }).isEqualTo(1)
    }

    @Test fun closeTabCallsSessions() = runTest(main.dispatcher) {
        sessions.tabs.value = listOf(tab(5, "a", "Five"))
        RailViewModel(sessions, library, bookshelf, chats, current).closeTab(5)
        testScheduler.advanceUntilIdle()
        assertThat(sessions.closed).containsExactly(5L)
        assertThat(sessions.tabs.value.single().onRail).isFalse()
    }

    @Test fun recentSearchesPassThrough() = runTest(main.dispatcher) {
        sessions.searches.value = listOf("aspirin", "hamlet")
        val ui = RailViewModel(sessions, library, bookshelf, chats, current).ui.first { it.recent.isNotEmpty() }
        assertThat(ui.recent.map { (it as RecentItem.Search).query }).containsExactly("aspirin", "hamlet").inOrder()
    }

    @Test fun recentMergesChatsAndSearchesNewestFirst() = runTest(main.dispatcher) {
        sessions.searchRows.value = listOf(RecentSearch("aspirin", 300), RecentSearch("hamlet", 100))
        chats.chats.value = listOf(ChatSummary(1, "Why tides?", 200), ChatSummary(2, "Who was Emma?", 400))
        val ui = RailViewModel(sessions, library, bookshelf, chats, current).ui.first { it.recent.size == 4 }
        assertThat(ui.recent).containsExactly(
            RecentItem.Chat(2, "Who was Emma?", 400),
            RecentItem.Search("aspirin", 300),
            RecentItem.Chat(1, "Why tides?", 200),
            RecentItem.Search("hamlet", 100),
        ).inOrder()
    }

    @Test fun recentKeepsFive() = runTest(main.dispatcher) {
        sessions.searchRows.value = (1..5).map { RecentSearch("q$it", it * 10L) }
        chats.chats.value = (1..5).map { ChatSummary(it.toLong(), "c$it", it * 10L + 5) }
        val ui = RailViewModel(sessions, library, bookshelf, chats, current).ui.first { it.recent.isNotEmpty() }
        assertThat(ui.recent).hasSize(5)
        assertThat(ui.recent.map { it.at }).containsExactly(55L, 50L, 45L, 40L, 35L).inOrder()
    }

    @Test fun bookTabShowsCoverChapterAndPercent() = runTest(main.dispatcher) {
        bookshelf.bookList.value = listOf(book("book:granger:1", "Emma", author = "Jane Austen", coverPath = "/covers/a.jpg"))
        bookshelf.positionMap.value = mapOf("book:granger:1" to BookPosition("book:granger:1", "{}", 0.426, "Chapter 3", 1L))
        sessions.tabs.value = listOf(tab(4, "book:granger:1", "Emma").copy(path = ""))
        val ui = RailViewModel(sessions, library, bookshelf, chats, current).ui.first { it.tabs.isNotEmpty() }
        assertThat(ui.tabs.single()).isEqualTo(
            RailTabUi(4, "Emma", "Chapter 3 \u00b7 43%", 'E', available = true, current = false, coverPath = "/covers/a.jpg"),
        )
    }

    @Test fun bookTabWithoutPositionShowsAuthor() = runTest(main.dispatcher) {
        bookshelf.bookList.value = listOf(book("book:granger:1", "Emma", author = "Jane Austen"))
        sessions.tabs.value = listOf(tab(4, "book:granger:1", "Emma"))
        val ui = RailViewModel(sessions, library, bookshelf, chats, current).ui.first { it.tabs.isNotEmpty() }
        assertThat(ui.tabs.single().subtitle).isEqualTo("Jane Austen")
    }

    @Test fun missingBookTabIsUnavailable() = runTest(main.dispatcher) {
        bookshelf.bookList.value = listOf(book("book:granger:1", "Emma", status = PackStatus.MISSING))
        sessions.tabs.value = listOf(tab(4, "book:granger:1", "Emma"), tab(5, "book:granger:gone", "Lost"))
        val ui = RailViewModel(sessions, library, bookshelf, chats, current).ui.first { it.tabs.size == 2 }
        assertThat(ui.tabs.map { it.available }).containsExactly(false, false)
    }
}
