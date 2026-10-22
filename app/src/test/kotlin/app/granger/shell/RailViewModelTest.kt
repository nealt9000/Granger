package app.granger.shell

import app.granger.FakeBookshelf
import app.granger.FakeLibrary
import app.granger.FakeRailHidden
import app.granger.FakeRailPins
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
import kotlinx.coroutines.launch
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
    private val pins = FakeRailPins()
    private val hidden = FakeRailHidden()
    private val playRows = mutableMapOf<String, RecentPlay>()

    private val voice = MutableStateFlow<app.granger.core.player.NowPlaying.Voice?>(null)

    private fun vm() = RailViewModel(
        sessions, library, bookshelf, chats, current, pins, hidden, plays,
        recentPlay = { playRows[it] }, resumer = PlayResumer(media, playlists, hub), voice = voice,
    )

    @Test fun aLiveReadingHeadsTheListAndLeavesWithTheVoice() = runTest(main.dispatcher) {
        sessions.tabs.value = listOf(tab(1, "p", "Photosynthesis"))
        val vm = vm()
        val collecting = backgroundScope.launch { vm.ui.collect {} }
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.entries.filterIsInstance<RailEntry.Reading>()).isEmpty()
        voice.value = app.granger.core.player.NowPlaying.Voice("book:b", "Hamlet", "Act I", "", 0, 1f, app.granger.core.speech.VoiceId.Granger("v"))
        testScheduler.advanceUntilIdle()
        val first = vm.ui.value.entries.first()
        assertThat(first).isInstanceOf(RailEntry.Reading::class.java)
        assertThat((first as RailEntry.Reading).title).isEqualTo("Hamlet")
        assertThat(first.pinned).isFalse()
        assertThat(vm.ui.value.tabs).hasSize(1)                       // the rest of the list is unchanged under it
        voice.value = null
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.entries.filterIsInstance<RailEntry.Reading>()).isEmpty()
        collecting.cancel()
    }

    private fun tab(id: Long, packId: String, title: String, openedAt: Long = id) =
        ReadingTab(id, packId, title, title, openedAt, onRail = true)

    private val RailUi.tabs get() = entries.filterIsInstance<RailEntry.Tab>().map { it.tab }

    @Test fun tabsShowPackTitleAndAvailability() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("a", "Classics"), pack("b", "WikiMed", PackStatus.MISSING))
        sessions.tabs.value = listOf(tab(1, "gone", "x-ray"), tab(2, "b", "Aspirin"), tab(3, "a", "Hamlet"))
        val ui = vm().ui.first { it.entries.isNotEmpty() }
        assertThat(ui.tabs).containsExactly(
            RailTabUi(3, "Hamlet", "Classics", 'H', available = true, current = false, coverPath = null),
            RailTabUi(2, "Aspirin", "WikiMed", 'A', available = false, current = false, coverPath = null),
            RailTabUi(1, "x-ray", HistoryViewModel.REMOVED_PACK, 'X', available = false, current = false, coverPath = null),
        ).inOrder()
    }

    @Test fun currentTabIsMarked() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("a"))
        sessions.tabs.value = listOf(tab(1, "a", "One"), tab(2, "a", "Two"))
        val vm = vm()
        current.value = 2
        assertThat(vm.ui.first { it.tabs.any { t -> t.current } }.tabs.single { it.current }.tabId).isEqualTo(2)
        current.value = 1
        assertThat(vm.ui.first { it.tabs.any { t -> t.current && t.tabId == 1L } }.tabs.count { it.current }).isEqualTo(1)
    }

    @Test fun entriesMergeAllKindsNewestFirst() = runTest(main.dispatcher) {
        sessions.tabs.value = listOf(tab(9, "a", "Owls", openedAt = 250))
        sessions.searchRows.value = listOf(RecentSearch("aspirin", 300), RecentSearch("hamlet", 100))
        chats.chats.value = listOf(ChatSummary(1, "Why tides?", 200), ChatSummary(2, "Who was Emma?", 400))
        plays.value = listOf(RecentPlay("album:artist|abbey road|music/", "Abbey Road", 350))
        val ui = vm().ui.first { it.entries.size == 6 }
        assertThat(ui.entries.map { it.key }).containsExactly(
            "chat:2", "play:album:artist|abbey road|music/", "search:aspirin", "tab:9", "chat:1", "search:hamlet",
        ).inOrder()
        assertThat(ui.entries.none { it.pinned }).isTrue()
        assertThat(ui.entries[1]).isEqualTo(RailEntry.Play("album:artist|abbey road|music/", "Abbey Road", 350))
    }

    @Test fun unpinnedListIsCappedAtTwelve() = runTest(main.dispatcher) {
        sessions.searchRows.value = (1..10).map { RecentSearch("q$it", it * 10L) }
        chats.chats.value = (1..10).map { ChatSummary(it.toLong(), "c$it", it * 10L + 5) }
        val ui = vm().ui.first { it.entries.isNotEmpty() }
        assertThat(ui.entries).hasSize(RailViewModel.UNPINNED)
        assertThat(ui.entries.map { it.at }).containsExactly(105L, 100L, 95L, 90L, 85L, 80L, 75L, 70L, 65L, 60L, 55L, 50L).inOrder()
    }

    @Test fun pinnedComeFirstInPinOrder() = runTest(main.dispatcher) {
        sessions.tabs.value = listOf(tab(1, "a", "Owls", openedAt = 10))
        sessions.searchRows.value = listOf(RecentSearch("cats", 30))
        chats.chats.value = listOf(ChatSummary(4, "Tides", 20))
        val vm = vm()
        val tabEntry = vm.ui.first { it.entries.size == 3 }.entries.single { it is RailEntry.Tab }
        assertThat(vm.pin(tabEntry)).isNull()
        assertThat(vm.pin(RailEntry.Chat(4, "Tides", 20))).isNull()
        val ui = vm.ui.first { it.entries.count { e -> e.pinned } == 2 }
        // Newest pin on top, then the rest newest first.
        assertThat(ui.entries.map { it.key to it.pinned }).containsExactly(
            "chat:4" to true, "tab:1" to true, "search:cats" to false,
        ).inOrder()

        vm.unpin(ui.entries.first())
        assertThat(vm.ui.first { it.entries.count { e -> e.pinned } == 1 }.entries.map { it.key })
            .containsExactly("tab:1", "search:cats", "chat:4").inOrder()
    }

    @Test fun pinnedItemSurvivesNewerItems() = runTest(main.dispatcher) {
        sessions.searchRows.value = listOf(RecentSearch("holst", 1))
        val vm = vm()
        vm.ui.first { it.entries.isNotEmpty() }
        vm.pin(RailEntry.Search("holst", 1))
        sessions.searchRows.value = (2..20).map { RecentSearch("q$it", it.toLong()) } + RecentSearch("holst", 1)
        val ui = vm.ui.first { it.entries.size == 1 + RailViewModel.UNPINNED }
        assertThat(ui.entries.first()).isEqualTo(RailEntry.Search("holst", 1, pinned = true))
        assertThat(pins.dropped).isEmpty()
    }

    @Test fun pinnedItemsOlderThanTheWatchedListsAreLookedUp() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("a"))
        sessions.tabs.value = listOf(tab(1, "a", "Oldest"))
        chats.chats.value = listOf(ChatSummary(1, "First chat", 1))
        playRows["songs"] = RecentPlay("songs", "", 1)
        plays.value = listOf(playRows.getValue("songs"))
        val vm = vm()
        val first = vm.ui.first { it.entries.size == 3 }.entries
        first.forEach { vm.pin(it) }
        // Far more newer ones than the rail watches of each kind.
        sessions.tabs.value += (2L..80L).map { tab(it, "a", "T$it") }
        chats.chats.value += (2L..80L).map { ChatSummary(it, "c$it", it) }
        plays.value = (2..80).map { RecentPlay("album:$it", "A$it", it.toLong()) }
        val ui = vm.ui.first { it.entries.size == 3 + RailViewModel.UNPINNED && it.entries.any { e -> e.key == "tab:80" } }
        assertThat(ui.entries.take(3).map { it.key }).containsExactly("tab:1", "chat:1", "play:songs")
        assertThat(ui.entries.take(3).all { it.pinned }).isTrue()
        assertThat(pins.dropped).isEmpty()
    }

    @Test fun goneItemsDropTheirPins() = runTest(main.dispatcher) {
        sessions.tabs.value = listOf(tab(1, "a", "Owls"), tab(2, "a", "Bats"))
        sessions.searchRows.value = listOf(RecentSearch("cats", 5))
        chats.chats.value = listOf(ChatSummary(3, "Tides", 6))
        plays.value = listOf(RecentPlay("songs", "", 7))
        playRows["songs"] = plays.value.single()
        val vm = vm()
        vm.ui.first { it.entries.size == 5 }.entries.forEach { vm.pin(it) }
        vm.ui.first { it.entries.all { e -> e.pinned } }

        sessions.closeTab(1) // closed elsewhere, e.g. its pack was removed
        sessions.forgetSearch("cats")
        chats.chats.value = emptyList()
        plays.value = emptyList()
        playRows.clear()
        val ui = vm.ui.first { it.entries.size == 1 }
        assertThat(ui.entries.single().key).isEqualTo("tab:2")
        testScheduler.advanceUntilIdle()
        assertThat(pins.dropped.toSet()).containsExactly("tab:1", "search:cats", "chat:3", "play:songs")
        assertThat(pins.pinList.value.map { it.key }).containsExactly("tab:2")
    }

    @Test fun pinThatCantBeReadNowIsKept() = runTest(main.dispatcher) {
        chats.chats.value = listOf(ChatSummary(1, "Tides", 1))
        val vm = vm()
        vm.ui.first { it.entries.isNotEmpty() }
        vm.pin(RailEntry.Chat(1, "Tides", 1))
        chats.failure = IllegalStateException("database closed")
        chats.chats.value = (2L..60L).map { ChatSummary(it, "c$it", it) } + ChatSummary(1, "Tides", 1)
        vm.ui.first { it.entries.size == RailViewModel.UNPINNED && it.entries.none { e -> e.pinned } }
        testScheduler.advanceUntilIdle()
        assertThat(pins.dropped).isEmpty()
        assertThat(pins.pinList.value.map { it.key }).containsExactly("chat:1")
    }

    @Test fun seventhPinShowsTheLimitNotice() = runTest(main.dispatcher) {
        sessions.searchRows.value = (1..7).map { RecentSearch("q$it", it.toLong()) }
        val vm = vm()
        vm.ui.first { it.entries.size == 7 }
        for (i in 1..6) assertThat(vm.pin(RailEntry.Search("q$i", i.toLong()))).isNull()
        assertThat(vm.pin(RailEntry.Search("q7", 7))).isEqualTo(RailViewModel.PINS_FULL)
        assertThat(RailViewModel.PINS_FULL).contains("Unpin one first")
        assertThat(vm.ui.first { it.entries.count { e -> e.pinned } == 6 }.entries.last()).isEqualTo(RailEntry.Search("q7", 7))
    }

    @Test fun removeTabClosesItButHistoryKeepsIt() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("a"))
        val id = sessions.openTab("a", "Owl", "Owl")
        val vm = vm()
        val entry = vm.ui.first { it.entries.isNotEmpty() }.entries.single()
        vm.pin(entry)
        vm.remove(entry)
        testScheduler.advanceUntilIdle()
        assertThat(sessions.closed).containsExactly(id)
        assertThat(sessions.historyList.value.map { it.path }).containsExactly("Owl")
        assertThat(pins.pinList.value).isEmpty()
        assertThat(vm.ui.first { it.entries.isEmpty() }.entries).isEmpty()
    }

    @Test fun removeSearchDeletesTheRecentSearch() = runTest(main.dispatcher) {
        sessions.searchRows.value = listOf(RecentSearch("cats", 2), RecentSearch("dogs", 1))
        val vm = vm()
        vm.ui.first { it.entries.size == 2 }
        vm.remove(RailEntry.Search("cats", 2))
        assertThat(vm.ui.first { it.entries.size == 1 }.entries.single().key).isEqualTo("search:dogs")
        assertThat(sessions.searchRows.value.map { it.query }).containsExactly("dogs")
    }

    @Test fun removeChatOrPlayHidesItUntilUsedAgain() = runTest(main.dispatcher) {
        chats.chats.value = listOf(ChatSummary(1, "Tides", 10))
        plays.value = listOf(RecentPlay("songs", "", 20))
        val vm = vm()
        vm.ui.first { it.entries.size == 2 }.entries.forEach(vm::remove)
        vm.ui.first { it.entries.isEmpty() }
        // The chat is still there for Research.
        assertThat(chats.chats.value).hasSize(1)
        // Answered again: back on the rail.
        chats.chats.value = listOf(ChatSummary(1, "Tides", 30))
        assertThat(vm.ui.first { it.entries.isNotEmpty() }.entries.map { it.key }).containsExactly("chat:1")
    }

    @Test fun bookTabShowsCoverChapterAndPercent() = runTest(main.dispatcher) {
        bookshelf.bookList.value = listOf(book("book:granger:1", "Emma", author = "Jane Austen", coverPath = "/covers/a.jpg"))
        bookshelf.positionMap.value = mapOf("book:granger:1" to BookPosition("book:granger:1", "{}", 0.426, "Chapter 3", 1L))
        sessions.tabs.value = listOf(tab(4, "book:granger:1", "Emma").copy(path = ""))
        val ui = vm().ui.first { it.entries.isNotEmpty() }
        assertThat(ui.tabs.single()).isEqualTo(
            RailTabUi(4, "Emma", "Chapter 3 · 43%", 'E', available = true, current = false, coverPath = "/covers/a.jpg"),
        )
    }

    @Test fun bookTabWithoutPositionShowsAuthor() = runTest(main.dispatcher) {
        bookshelf.bookList.value = listOf(book("book:granger:1", "Emma", author = "Jane Austen"))
        sessions.tabs.value = listOf(tab(4, "book:granger:1", "Emma"))
        val ui = vm().ui.first { it.entries.isNotEmpty() }
        assertThat(ui.tabs.single().subtitle).isEqualTo("Jane Austen")
    }

    @Test fun missingBookTabIsUnavailable() = runTest(main.dispatcher) {
        bookshelf.bookList.value = listOf(book("book:granger:1", "Emma", status = PackStatus.MISSING))
        sessions.tabs.value = listOf(tab(4, "book:granger:1", "Emma"), tab(5, "book:granger:gone", "Lost"))
        val ui = vm().ui.first { it.entries.size == 2 }
        assertThat(ui.tabs.map { it.available }).containsExactly(false, false)
    }

    @Test fun recentPlayForRemovedSourceOpensLibraryWithNotice() = runTest(main.dispatcher) {
        val vm = vm()
        // The album is gone, the playlist was deleted, and the folder's only song is MISSING.
        media.trackList.value = listOf(song("a", folder = "Old/", status = PackStatus.MISSING))
        for (key in listOf("album:nobody|gone|music/", "playlist:42", "folder:Old/", "artist:Nobody", "audiobook:phone:gone", "nonsense")) {
            val notice = vm.resume(RailEntry.Play(key, "Gone thing", 1))
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
        val vm = vm()
        assertThat(vm.resume(RailEntry.Play("album:${one.albumKey}", "Help", 1))).isNull()
        assertThat(hub.plays.single()).isEqualTo(
            FakePlayerHub.Play(listOf(one.id, three.id), 0, false, app.granger.core.player.PlaySource("album:${one.albumKey}", "Help")),
        )
        val id = playlists.create("Mix", listOf(three.id, two.id, one.id))
        assertThat(vm.resume(RailEntry.Play("playlist:$id", "Mix", 1))).isNull()
        assertThat(hub.plays.last().trackIds).containsExactly(three.id, one.id).inOrder()
        assertThat(vm.resume(RailEntry.Play("songs", "All music", 1))).isNull()
        assertThat(hub.plays.last().trackIds).containsExactly(one.id, three.id).inOrder()
        assertThat(vm.resume(RailEntry.Play("folder:Music/", "Music", 1))).isNull()
        assertThat(hub.plays.last().trackIds).containsExactly(one.id, three.id).inOrder()
        assertThat(vm.resume(RailEntry.Play("artist:artist", "Artist", 1))).isNull()
        assertThat(hub.plays.last().trackIds).containsExactly(one.id, three.id).inOrder()
    }

    @Test fun recentAudiobookResumesThroughTheHub() = runTest(main.dispatcher) {
        media.bookList.value = listOf(
            Audiobook("audiobook:phone:e", MediaOrigin.PHONE, "e", "Emma", "Jane Austen", "", "", null, 1_000L, PackStatus.OK, 0L),
        )
        val vm = vm()
        assertThat(vm.resume(RailEntry.Play("audiobook:phone:e", "Emma", 1))).isNull()
        assertThat(hub.bookPlays).containsExactly("audiobook:phone:e" to null)
    }
}
