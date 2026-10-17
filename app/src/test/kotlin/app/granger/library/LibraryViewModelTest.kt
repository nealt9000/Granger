package app.granger.library

import app.granger.FakeLibrary
import app.granger.FakeShelves
import app.granger.MainDispatcherRule
import app.granger.pack
import app.granger.core.library.CollectionRow
import app.granger.core.library.MediaType
import app.granger.core.library.Shelf
import app.granger.core.library.ShelfCollection
import app.granger.sources.CatalogScope
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class LibraryViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val library = FakeLibrary()
    private val shelves = FakeShelves()

    private fun shelf(type: String, vararg collections: Pair<String, Int>) = Shelf(
        MediaType(type.lowercase(), type, builtIn = true, sortOrder = 0),
        collections.map { (name, count) ->
            CollectionRow(
                ShelfCollection("c:$name", name, type.lowercase(), userCreated = false, defaultKey = name),
                List(count) { pack("$name-$it") },
            )
        },
    )

    @Test fun refreshRescansTheLibrary() = runTest(main.dispatcher) {
        val vm = LibraryViewModel(shelves, library)
        vm.refresh()
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertThat(library.refreshes).isEqualTo(2)
    }

    @Test fun showsShelvesAndBothSources() = runTest(main.dispatcher) {
        shelves.shelfList.value = listOf(shelf("Literature", "Wikipedia" to 3, "Medicine" to 1))
        val ui = LibraryViewModel(shelves, library).ui.filterNotNull().first()
        assertThat(ui.media.map { it.mediaType.name }).containsExactly("Literature")
        assertThat(ui.media.single().collections.map { it.packs.size }).containsExactly(3, 1).inOrder()
        assertThat(ui.sources).containsExactly(CatalogScope.GRANGER, CatalogScope.KIWIX).inOrder()
        assertThat(ui.emptyLibrary).isFalse()
    }

    @Test fun emptyLibraryIsFlagged() = runTest(main.dispatcher) {
        val ui = LibraryViewModel(shelves, library).ui.filterNotNull().first()
        assertThat(ui.emptyLibrary).isTrue()
        assertThat(ui.sources).hasSize(2)
    }
}
