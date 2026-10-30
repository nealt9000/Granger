package app.granger.library.shelf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.granger.library.BookSort
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryViewPrefsTest {
    private val shared = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("library-views-test", Context.MODE_PRIVATE)

    @Test fun everyShelfStartsAsAListByTitle() {
        val prefs = LibraryViewPrefs(shared)
        for (kind in ShelfKind.entries) {
            assertThat(prefs.view(kind).value).isEqualTo(ShelfView.LIST)
            assertThat(prefs.sort(kind).value).isEqualTo("TITLE")
        }
    }

    @Test fun aChangeSurvivesANewStore() {
        val prefs = LibraryViewPrefs(shared)
        prefs.setView(ShelfKind.ALBUMS, ShelfView.GRID)
        prefs.setSort(ShelfKind.BOOKS, BookSort.AUTHOR.name)
        assertThat(prefs.view(ShelfKind.ALBUMS).value).isEqualTo(ShelfView.GRID)
        // The next launch reads them back; the other kinds are untouched.
        val next = LibraryViewPrefs(shared)
        assertThat(next.view(ShelfKind.ALBUMS).value).isEqualTo(ShelfView.GRID)
        assertThat(next.sort(ShelfKind.BOOKS).value).isEqualTo("AUTHOR")
        assertThat(next.view(ShelfKind.BOOKS).value).isEqualTo(ShelfView.LIST)
        assertThat(next.sort(ShelfKind.ALBUMS).value).isEqualTo("TITLE")
    }

    @Test fun anUnknownStoredViewReadsAsList() {
        shared.edit().putString("songs.view", "SHELF").commit()
        assertThat(LibraryViewPrefs(shared).view(ShelfKind.SONGS).value).isEqualTo(ShelfView.LIST)
    }

    @Test fun anUnknownSortNameFallsBackToTheDefault() {
        assertThat(sortNamed("AUTHOR", BookSort.TITLE)).isEqualTo(BookSort.AUTHOR)
        assertThat(sortNamed("SHOE_SIZE", BookSort.TITLE)).isEqualTo(BookSort.TITLE)
    }

    @Test fun withoutPrefsTheChoicesStayInMemory() {
        val prefs = LibraryViewPrefs()
        prefs.setView(ShelfKind.BOOKS, ShelfView.GRID)
        assertThat(prefs.view(ShelfKind.BOOKS).value).isEqualTo(ShelfView.GRID)
        assertThat(LibraryViewPrefs().view(ShelfKind.BOOKS).value).isEqualTo(ShelfView.LIST)
    }
}
