package app.granger.downloads

import app.granger.FakeBookshelf
import app.granger.FakeDownloads
import app.granger.FakeLibrary
import app.granger.FakeReadingSessions
import app.granger.MainDispatcherRule
import app.granger.book
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.GoneFile
import app.granger.core.downloads.ItemKind
import app.granger.record
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class DownloadsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val downloads = FakeDownloads()
    private val library = FakeLibrary()
    private val bookshelf = FakeBookshelf()
    private val sessions = FakeReadingSessions()

    private fun TestScope.vm() = DownloadsViewModel(
        downloads, library, bookshelf, sessions, appScope = this, today = { 0L }, clock = { 5L }, tick = flowOf(Unit), work = main.dispatcher,
    )

    private fun TestScope.watching(vm: DownloadsViewModel) = backgroundScope.launch { vm.ui.collect {} }

    @Test fun pauseAllAndResume() = runTest(main.dispatcher) {
        val vm = vm()
        vm.pause()
        vm.resume()
        testScheduler.advanceUntilIdle()
        assertThat(downloads.calls).containsExactly("pause", "resume").inOrder()
    }

    @Test fun downloadNextMovesARowToTheTopOfUpNext() = runTest(main.dispatcher) {
        val vm = vm()
        watching(vm)
        downloads.list.value = listOf("a", "b", "c", "d").map { record(it, if (it == "a") DownloadState.RUNNING else DownloadState.QUEUED) }
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.upNext.map { it.key }).containsExactly("item:b", "item:c", "item:d").inOrder()
        vm.downloadNext(vm.ui.value.upNext.last())
        testScheduler.advanceUntilIdle()
        assertThat(downloads.calls).containsExactly("next:d")
        assertThat(vm.ui.value.now!!.key).isEqualTo("item:a")
        assertThat(vm.ui.value.upNext.map { it.key }).containsExactly("item:d", "item:b", "item:c").inOrder()
    }

    @Test fun retryAllQueuesWhatCanBeFetchedAgain() = runTest(main.dispatcher) {
        val vm = vm()
        watching(vm)
        downloads.list.value = listOf(
            record("a", DownloadState.FAILED, error = "x answered 503"),
            record("gone", DownloadState.FAILED, error = GoneFile.KIWIX),
            record("b", DownloadState.FAILED, error = "x answered 503"),
        )
        testScheduler.advanceUntilIdle()
        vm.retryAll(vm.ui.value.stopped)
        testScheduler.advanceUntilIdle()
        assertThat(downloads.calls).containsExactly("retry:a", "retry:b").inOrder()
    }

    @Test fun cancelRetryAndRemoveActOnTheItemAndItsIndex() = runTest(main.dispatcher) {
        val vm = vm()
        val row = DownloadRow("item:b", "B", RowState.FAILED, 0, 10, groupId = "g", itemIds = listOf("b", "b.vectors"))
        vm.retry(row)
        vm.cancel(row)
        vm.cancelGroup(row)
        vm.remove(row)
        testScheduler.advanceUntilIdle()
        assertThat(downloads.calls).containsExactly(
            "retry:b", "retry:b.vectors", "cancel:b", "cancel:b.vectors", "cancelGroup:g", "remove:b", "remove:b.vectors",
        ).inOrder()
    }

    @Test fun theSectionsFollowTheRecords() = runTest(main.dispatcher) {
        val vm = vm()
        watching(vm)
        downloads.list.value = listOf(record("a", DownloadState.QUEUED), record("b", DownloadState.FAILED))
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.upNext.map { it.key }).containsExactly("item:a")
        assertThat(vm.ui.value.stopped.map { it.key }).containsExactly("item:b")
        assertThat(vm.ui.value.header!!.files).isEqualTo(2)
    }

    @Test fun openBookShowsItInItsTab() = runTest(main.dispatcher) {
        bookshelf.bookList.value = listOf(book("book:granger:emma", "Emma"))
        val tab = vm().openBook(OpenTarget.Book("book:granger:emma"), "Emma")
        assertThat(sessions.tab(tab)!!.packId).isEqualTo("book:granger:emma")
        assertThat(sessions.tab(tab)!!.title).isEqualTo("Emma")
    }

    @Test fun aRecordFinishedTodayIsDoneToday() = runTest(main.dispatcher) {
        val vm = vm()
        watching(vm)
        downloads.list.value = listOf(record("m", DownloadState.INSTALLED, kind = ItemKind.MODEL))
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.doneToday.map { it.key }).containsExactly("item:m")
    }

    /** Plan WF Decision 5: Downloads offers the same tidy-up as Home's card. */
    @Test fun downloadsOffersToRemoveTheSmallerSlice() = runTest(main.dispatcher) {
        val top = app.granger.packEntry("wikipedia_en_100", zimName = "wikipedia_en_100", size = 333_000_000)
            .copy(setId = "wikipedia_en", setRank = 1, setLabel = "100 essential articles")
        val best = app.granger.packEntry("wikipedia_en_top_mini", zimName = "wikipedia_en_top", size = 300_000_000)
            .copy(setId = "wikipedia_en", setRank = 2, setLabel = "Best 50,000 articles")
        val catalog = app.granger.FakeCatalogSource(app.granger.catalogOf(listOf(top, best)))
        library.packList.value = listOf(
            app.granger.pack(top.zimUuid).copy(name = top.zimName, sizeBytes = 333_000_000),
            app.granger.pack(best.zimUuid).copy(name = best.zimName),
        )
        val vm = DownloadsViewModel(
            downloads, library, bookshelf, sessions, appScope = this, catalog = catalog, today = { 0L }, clock = { 5L },
            tick = flowOf(Unit), work = main.dispatcher,
        )
        backgroundScope.launch { vm.tidy.collect {} }
        testScheduler.advanceUntilIdle()
        val offer = vm.tidy.value.single()
        assertThat(offer.text).isEqualTo("Best 50,000 is in. Remove 100 essential (333 MB)? Your places and bookmarks move across.")
        vm.removeSmaller(offer)
        testScheduler.advanceUntilIdle()
        assertThat(library.supersededAcross).containsExactly(top.zimUuid to best.zimUuid)
        assertThat(downloads.calls).contains("remove:wikipedia_en_100")
    }
}
