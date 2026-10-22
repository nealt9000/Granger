package app.granger.player

import app.granger.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class PlayerViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun secondaryButtonCallsNextForMusicAndSkipBackForBooks() {
        val hub = FakePlayerHub(musicUi())
        val vm = PlayerViewModel(hub)
        vm.secondary()
        assertThat(hub.calls).containsExactly("next")

        val book = audiobook("b")
        hub.ui.value = bookUi(book, chapter(book, 0, 60_000L), chapterCount = 1)
        hub.calls.clear()
        vm.secondary()
        assertThat(hub.calls).containsExactly("skipBack")

        hub.ui.value = voiceUi()
        hub.calls.clear()
        vm.secondary()
        assertThat(hub.calls).containsExactly("previousSentence")
        assertThat(vm.voiceNow()?.title).isEqualTo("Rosetta Stone")
        assertThat(modeOf(hub.ui.value.now)).isNull()                 // no Player tab for the voice

        hub.ui.value = app.granger.core.player.PlayerUi.IDLE
        hub.calls.clear()
        vm.secondary()
        assertThat(hub.calls).isEmpty()
        assertThat(vm.voiceNow()).isNull()
    }

    @Test fun bookProgressAddsEarlierChaptersToThePositionInThisOne() = runTest(main.dispatcher) {
        val book = audiobook("b", durationMs = 999_000L)
        // Given out of order on purpose: the offset is by ordinal, not list position.
        val chapters = listOf(chapter(book, 2, 30_000L), chapter(book, 0, 60_000L), chapter(book, 1, 90_000L))
        fun ch(ordinal: Int) = chapters.first { it.ordinal == ordinal }
        val source = MutableStateFlow(chapters)
        val hub = FakePlayerHub(bookUi(book, ch(2), chapterCount = 3, positionMs = 10_000L))
        val vm = PlayerViewModel(hub, chapters = { id -> assertThat(id).isEqualTo(book.id); source })
        backgroundScope.launch { vm.progress.collect {} }
        testScheduler.advanceUntilIdle()

        // Chapter 3 at 0:10: chapters 1 and 2 (60 s + 90 s) before it, out of 180 s.
        assertThat(vm.progress.value).isEqualTo(BookProgress(positionMs = 160_000L, durationMs = 180_000L))
        assertThat(vm.bookChapters.value.map { it.ordinal }).containsExactly(0, 1, 2).inOrder()

        // Chapter 2 at its start: chapter 1's 60 s before it.
        hub.ui.value = bookUi(book, ch(1), chapterCount = 3, positionMs = 0L)
        testScheduler.advanceUntilIdle()
        assertThat(vm.progress.value).isEqualTo(BookProgress(60_000L, 180_000L))

        hub.ui.value = musicUi()
        testScheduler.advanceUntilIdle()
        assertThat(vm.progress.value).isNull()
    }

    @Test fun bookProgressWithoutChapterDurationsFallsBackToTheBook() {
        val book = audiobook("b", durationMs = 500_000L)
        val c = chapter(book, 0, durationMs = 0L)
        assertThat(bookProgress(emptyList(), c, 20_000L, book.durationMs)).isEqualTo(BookProgress(20_000L, 500_000L))
    }

    @Test fun chapterStepsStopAtEitherEnd() = runTest(main.dispatcher) {
        val book = audiobook("b")
        val chapters = listOf(chapter(book, 0, 60_000L), chapter(book, 1, 60_000L))
        val hub = FakePlayerHub(bookUi(book, chapters[0], chapterCount = 2))
        val vm = PlayerViewModel(hub, chapters = { MutableStateFlow(chapters) })
        backgroundScope.launch { vm.bookChapters.collect {} }
        testScheduler.advanceUntilIdle()

        vm.previousChapter()
        vm.nextChapter()
        hub.ui.value = bookUi(book, chapters[1], chapterCount = 2)
        vm.nextChapter()
        assertThat(hub.calls).containsExactly("seekToChapter:1")
    }

    @Test fun tappingAQueueRowPlaysIt() = runTest(main.dispatcher) {
        val hub = FakePlayerHub(musicUi())
        val vm = PlayerViewModel(hub)
        vm.playQueueItem(2)
        assertThat(hub.calls).containsExactly("playQueueItem:2")
    }

    @Test fun queueAndBookActionsGoToTheHub() = runTest(main.dispatcher) {
        val hub = FakePlayerHub(musicUi())
        val vm = PlayerViewModel(hub)
        vm.moveInQueue(1, 1)
        vm.moveInQueue(2, 0)
        vm.removeFromQueue(1)
        vm.toggleShuffle()
        vm.setVolumeBoost(true)
        vm.setVolumeBoost(false)
        assertThat(hub.calls).containsExactly("move:2>0", "remove:1", "shuffle:true", "boost:600", "boost:0").inOrder()

        // A bookmark only with a book loaded, titled with the chapter and the time in it.
        hub.calls.clear()
        vm.addBookmark()
        val book = audiobook("b")
        hub.ui.value = bookUi(book, chapter(book, 2, 600_000L, title = "The Storm"), chapterCount = 5, positionMs = 125_000L)
        vm.addBookmark()
        testScheduler.advanceUntilIdle()
        assertThat(hub.calls).containsExactly("bookmark:The Storm · 2:05")
    }

    @Test fun speedsAndSleepChoicesMatchTheSpec() {
        assertThat(PlayerViewModel.SPEEDS.first()).isEqualTo(0.5f)
        assertThat(PlayerViewModel.SPEEDS.last()).isEqualTo(3.0f)
        assertThat(PlayerViewModel.SPEEDS).hasSize(26)
        assertThat(snapSpeed(1.26f)).isEqualTo(1.3f)
        assertThat(snapSpeed(9f)).isEqualTo(3.0f)
        assertThat(PlayerViewModel.SLEEP_MINUTES).containsExactly(5, 10, 15, 30, 45, 60).inOrder()
        assertThat(formatTime(3_725_000L)).isEqualTo("1:02:05")
    }

    @Test fun skipLabelsFollowTheSettings() {
        var back = 15
        val vm = PlayerViewModel(FakePlayerHub(), skipBackSeconds = { back }, skipForwardSeconds = { 60 })
        assertThat(vm.skipBackS()).isEqualTo(15)
        back = 0 // unset or nonsense: the default
        assertThat(vm.skipBackS()).isEqualTo(30)
        assertThat(vm.skipForwardS()).isEqualTo(60)
    }

    @Test fun tabsFollowTheActiveModeUntilPickedAndPickingCallsNothing() {
        val hub = FakePlayerHub(musicUi())
        val vm = PlayerViewModel(hub)
        assertThat(vm.tab()).isEqualTo(app.granger.core.player.PlayerMode.MUSIC)
        vm.selectTab(app.granger.core.player.PlayerMode.AUDIOBOOK)
        assertThat(vm.tab()).isEqualTo(app.granger.core.player.PlayerMode.AUDIOBOOK)
        vm.selectTab(app.granger.core.player.PlayerMode.MUSIC)
        vm.resetTab()
        assertThat(hub.calls).isEmpty()

        // Idle: the mode last used.
        hub.ui.value = app.granger.core.player.PlayerUi.IDLE
        hub.saved.value = app.granger.core.player.SavedModes(null, null, app.granger.core.player.PlayerMode.AUDIOBOOK)
        assertThat(vm.tab()).isEqualTo(app.granger.core.player.PlayerMode.AUDIOBOOK)
        hub.saved.value = app.granger.core.player.SavedModes(null, null, null)
        assertThat(vm.tab()).isEqualTo(app.granger.core.player.PlayerMode.MUSIC)
    }

    @Test fun activatingSwitchesThenDoesThePressedControl() {
        val hub = FakePlayerHub(musicUi())
        val vm = PlayerViewModel(hub)
        vm.activate(app.granger.core.player.PlayerMode.AUDIOBOOK) { seekTo(5_000L) }
        assertThat(hub.calls).containsExactly("activate:AUDIOBOOK", "seekTo:5000").inOrder()
    }
}
