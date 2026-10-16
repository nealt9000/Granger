package app.granger.search

import app.granger.MainDispatcherRule
import app.granger.core.library.LibrarySearch
import app.granger.core.library.SearchHit
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class FakeSearch : LibrarySearch {
        val titleQueries = mutableListOf<String>()
        var titles = listOf(SearchHit("p", "Pack", "Photosynthesis", "Photosynthesis"))
        var fullText = listOf(
            SearchHit("p", "Pack", "Photosynthesis", "Photosynthesis", "dup"),
            SearchHit("p", "Pack", "Leaf", "Leaf", "<b>photo</b>"),
        )

        override suspend fun titles(query: String): List<SearchHit> { titleQueries += query; return titles }
        override suspend fun fullText(query: String) = fullText
    }

    private val search = FakeSearch()

    @Test fun typingIsDebouncedToTheLastQuery() = runTest(main.dispatcher) {
        val vm = SearchViewModel(search)
        vm.onQueryChange("p")
        vm.onQueryChange("ph")
        vm.onQueryChange("pho")
        testScheduler.advanceUntilIdle()
        assertThat(search.titleQueries).containsExactly("pho")
        assertThat(vm.state.value.query).isEqualTo("pho")
    }

    @Test fun showsTitlesThenFullTextWithoutDuplicates() = runTest(main.dispatcher) {
        val vm = SearchViewModel(search)
        vm.onQueryChange("photo")
        testScheduler.advanceUntilIdle()
        val state = vm.state.value
        assertThat(state.titles.map { it.path }).containsExactly("Photosynthesis")
        assertThat(state.fullText.map { it.path }).containsExactly("Leaf")
        assertThat(state.searching).isFalse()
    }

    @Test fun clearingTheQueryClearsResults() = runTest(main.dispatcher) {
        val vm = SearchViewModel(search)
        vm.onQueryChange("photo")
        testScheduler.advanceUntilIdle()
        vm.onQueryChange("   ")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.titles).isEmpty()
        assertThat(vm.state.value.fullText).isEmpty()
        assertThat(search.titleQueries).containsExactly("photo")
    }

    @Test fun surroundingSpacesDoNotStartANewSearch() = runTest(main.dispatcher) {
        val vm = SearchViewModel(search)
        vm.onQueryChange("photo")
        testScheduler.advanceUntilIdle()
        vm.onQueryChange("photo ")
        testScheduler.advanceUntilIdle()
        assertThat(search.titleQueries).containsExactly("photo")
        assertThat(vm.state.value.query).isEqualTo("photo ")
    }
}
