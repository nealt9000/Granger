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
import app.granger.core.library.Audiobook
import app.granger.core.library.MediaOrigin
import app.granger.core.library.RecentPlay
import app.granger.media.PlayResumer
import app.granger.music.FakeMediaLibrary
import app.granger.music.FakePlaylists
import app.granger.music.song
import app.granger.player.FakePlayerHub
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
    private val plays = MutableStateFlow<List<RecentPlay>>(emptyList())
    private val media = FakeMediaLibrary()
    private val playlists = FakePlaylists(media)
    private val hub = FakePlayerHub()

    private fun playsViewModel() =
        RailViewModel(sessions, library, bookshelf, chats, current, plays, PlayResumer(media, playlists, hub))

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

    @Test fun recentMixesPlaysWithSearchesAndChatsNewestFirst() = runTest(main.dispatcher) {
        sessions.searchRows.value = listOf(RecentSearch("aspirin", 300))
        chats.chats.value = listOf(ChatSummary(1, "Why tides?", 200))
        plays.value = listOf(RecentPlay("album:artist|abbey road|music/", "Abbey Road", 400), RecentPlay("audiobook:phone:e", "Emma", 100))
        val ui = playsViewModel().ui.first { it.recent.size == 4 }
        assertThat(ui.recent).containsExactly(
            RecentItem.Play("album:artist|abbey road|music/", "Abbey Road", 400),
            RecentItem.Search("aspirin", 300),
            RecentItem.Chat(1, "Why tides?", 200),
            RecentItem.Play("audiobook:phone:e", "Emma", 100),
        ).inOrder()
    }

    @Test fun recentPlayForRemovedSourceOpensLibraryWithNotice() = runTest(main.dispatcher) {
        val vm = playsViewModel()
        // The album is gone, the playlist was deleted, and the folder's only song is MISSING.
        media.trackList.value = listOf(song("a", folder = "Old/", status = PackStatus.MISSING))
        for (key in listOf("album:nobody|gone|music/", "playlist:42", "folder:Old/", "artist:Nobody", "audiobook:phone:gone", "nonsense")) {
            val notice = vm.resume(RecentItem.Play(key, "Gone thing", 1))
            assertThat(notice).isNotNull()
            assertThat(notice).contains("Gone thing")
        }
        assertThat(hub.calls).isEmpty()
    }

    @Test fun recentPlayRebuildsTheListWithPlayableTracksOnly() = runTest(main.dispatcher) {
        val one = song("1", title = "One", album = "Help", trackNo = 1)
        val two = song("2", title = "Two", album = "Help", trackNo = 2, status = PackStatus.MISSING)
        val three = song("3", title = "Three", album = "Help", trackNo = 3)
        media.trackList.value = listOf(three, two, one)
        val vm = playsViewModel()
        assertThat(vm.resume(RecentItem.Play("album:${one.albumKey}", "Help", 1))).isNull()
        assertThat(hub.plays.single()).isEqualTo(
            FakePlayerHub.Play(listOf(one.id, three.id), 0, false, app.granger.core.player.PlaySource("album:${one.albumKey}", "Help")),
        )
        val id = playlists.create("Mix", listOf(three.id, two.id, one.id))
        assertThat(vm.resume(RecentItem.Play("playlist:$id", "Mix", 1))).isNull()
        assertThat(hub.plays.last().trackIds).containsExactly(three.id, one.id).inOrder()
        assertThat(vm.resume(RecentItem.Play("songs", "All music", 1))).isNull()
        assertThat(hub.plays.last().trackIds).containsExactly(one.id, three.id).inOrder()
        assertThat(vm.resume(RecentItem.Play("folder:Music/", "Music", 1))).isNull()
        assertThat(hub.plays.last().trackIds).containsExactly(one.id, three.id).inOrder()
        assertThat(vm.resume(RecentItem.Play("artist:artist", "Artist", 1))).isNull()
        assertThat(hub.plays.last().trackIds).containsExactly(one.id, three.id).inOrder()
    }

    @Test fun recentAudiobookResumesThroughTheHub() = runTest(main.dispatcher) {
        media.bookList.value = listOf(
            Audiobook("audiobook:phone:e", MediaOrigin.PHONE, "e", "Emma", "Jane Austen", "", "", null, 1_000L, PackStatus.OK, 0L),
        )
        val vm = playsViewModel()
        assertThat(vm.resume(RecentItem.Play("audiobook:phone:e", "Emma", 1))).isNull()
        assertThat(hub.bookPlays).containsExactly("audiobook:phone:e" to null)
    }
}
