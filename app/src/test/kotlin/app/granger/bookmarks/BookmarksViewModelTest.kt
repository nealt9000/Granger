package app.granger.bookmarks

import app.granger.FakeLibrary
import app.granger.MainDispatcherRule
import app.granger.core.library.Bookmark
import app.granger.core.library.PackStatus
import app.granger.pack
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class BookmarksViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val library = FakeLibrary()

    @Test fun rowsCarryPackTitleAndAvailability() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("a", "Classics"), pack("b", "WikiMed", PackStatus.MISSING))
        library.bookmarkList.value = listOf(
            Bookmark("a", "Hamlet", "Hamlet", 3L),
            Bookmark("b", "Aspirin", "Aspirin", 2L),
            Bookmark("gone", "X", "X", 1L),
        )
        val rows = BookmarksViewModel(library, this).rows.filterNotNull().first()
        assertThat(rows.map { Triple(it.bookmark.path, it.packTitle, it.available) }).containsExactly(
            Triple("Hamlet", "Classics", true),
            Triple("Aspirin", "WikiMed", false),
            Triple("X", BookmarksViewModel.REMOVED_PACK, false),
        ).inOrder()
    }

    @Test fun removeDeletesTheBookmark() = runTest(main.dispatcher) {
        val bookmark = Bookmark("a", "Hamlet", "Hamlet", 3L)
        library.bookmarkList.value = listOf(bookmark)
        BookmarksViewModel(library, this).remove(bookmark)
        testScheduler.advanceUntilIdle()
        assertThat(library.bookmarkList.value).isEmpty()
    }
}
