package app.granger.downloads

import app.granger.FakeBookshelf
import app.granger.FakeDownloads
import app.granger.FakeLibrary
import app.granger.FakeReadingSessions
import app.granger.MainDispatcherRule
import app.granger.book
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.ItemKind
import app.granger.pack
import app.granger.record
import com.google.common.truth.Truth.assertThat
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

    private fun TestScope.vm() = DownloadsViewModel(downloads, library, bookshelf, sessions, appScope = this, today = { 0L })

    @Test fun cancelAndRetryActOnTheGroupAndItsIndexes() = runTest(main.dispatcher) {
        val vm = vm()
        val row = DownloadRow("group:g", "Setting up", RowState.FAILED, 0, 10, groupId = "g", itemIds = listOf("b.vectors"))
        vm.retry(row)
        vm.cancel(row)
        testScheduler.advanceUntilIdle()
        assertThat(downloads.calls).containsExactly("retryGroup:g", "retry:b.vectors", "cancelGroup:g", "cancel:b.vectors").inOrder()
    }

    @Test fun theSectionsFollowTheRecords() = runTest(main.dispatcher) {
        val vm = vm()
        backgroundScope.launch { vm.ui.collect {} }
        downloads.list.value = listOf(record("a", DownloadState.QUEUED), record("b", DownloadState.FAILED))
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.now.map { it.key }).containsExactly("item:a")
        assertThat(vm.ui.value.stopped.map { it.key }).containsExactly("item:b")
    }

    @Test fun openShowsAPackAtItsLastReadPageAndABookFromItsPlace() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("wiki", "Wikipedia"))
        library.lastRead["wiki"] = "A/Ada"
        val vm = vm()
        val packTab = vm.open(DownloadRow("item:wiki", "Wikipedia", RowState.DONE, 1, 1, open = OpenTarget.Pack("wiki")))!!
        assertThat(sessions.tab(packTab)!!.path).isEqualTo("A/Ada")
        bookshelf.bookList.value = listOf(book("book:granger:emma", "Emma"))
        val bookTab = vm.open(DownloadRow("item:emma", "Emma", RowState.DONE, 1, 1, open = OpenTarget.Book("book:granger:emma")))!!
        assertThat(sessions.tab(bookTab)!!.packId).isEqualTo("book:granger:emma")
        assertThat(vm.open(DownloadRow("item:m", "M", RowState.DONE, 1, 1))).isNull()
    }

    @Test fun aRecordFinishedTodayIsDoneToday() = runTest(main.dispatcher) {
        val vm = vm()
        backgroundScope.launch { vm.ui.collect {} }
        downloads.list.value = listOf(record("m", DownloadState.INSTALLED, kind = ItemKind.MODEL))
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.doneToday.map { it.key }).containsExactly("item:m")
    }
}
