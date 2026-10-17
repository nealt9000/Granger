package app.granger.search

import app.granger.FakeReadingSessions
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
    private val sessions = FakeReadingSessions()

    @Test fun typingIsDebouncedToTheLastQuery() = runTest(main.dispatcher) {
        val vm = SearchViewModel(search, sessions, "")
        vm.onQueryChange("p")
        vm.onQueryChange("ph")
        vm.onQueryChange("pho")
        testScheduler.advanceUntilIdle()
        assertThat(search.titleQueries).containsExactly("pho")
        assertThat(vm.state.value.query).isEqualTo("pho")
    }

    @Test fun showsTitlesThenFullTextWithoutDuplicates() = runTest(main.dispatcher) {
        val vm = SearchViewModel(search, sessions, "")
        vm.onQueryChange("photo")
        testScheduler.advanceUntilIdle()
        val state = vm.state.value
        assertThat(state.titles.map { it.path }).containsExactly("Photosynthesis")
        assertThat(state.fullText.map { it.path }).containsExactly("Leaf")
        assertThat(state.searching).isFalse()
    }

    @Test fun clearingTheQueryClearsResults() = runTest(main.dispatcher) {
        val vm = SearchViewModel(search, sessions, "")
        vm.onQueryChange("photo")
        testScheduler.advanceUntilIdle()
        vm.onQueryChange("   ")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.titles).isEmpty()
        assertThat(vm.state.value.fullText).isEmpty()
        assertThat(search.titleQueries).containsExactly("photo")
    }

    @Test fun surroundingSpacesDoNotStartANewSearch() = runTest(main.dispatcher) {
        val vm = SearchViewModel(search, sessions, "")
        vm.onQueryChange("photo")
        testScheduler.advanceUntilIdle()
        vm.onQueryChange("photo ")
        testScheduler.advanceUntilIdle()
        assertThat(search.titleQueries).containsExactly("photo")
        assertThat(vm.state.value.query).isEqualTo("photo ")
    }

    @Test fun initialQueryIsSearchedImmediately() = runTest(main.dispatcher) {
        val vm = SearchViewModel(search, sessions, "photo")
        testScheduler.runCurrent()
        assertThat(vm.state.value.query).isEqualTo("photo")
        assertThat(search.titleQueries).containsExactly("photo")
        assertThat(vm.state.value.titles.map { it.path }).containsExactly("Photosynthesis")
    }

    @Test fun openingAHitRecordsTheSearchAndOpensATab() = runTest(main.dispatcher) {
        val vm = SearchViewModel(search, sessions, "")
        vm.onQueryChange(" photo ")
        testScheduler.advanceUntilIdle()
        val id = vm.open(vm.state.value.titles.single())
        assertThat(sessions.searches.value).containsExactly("photo")
        val tab = sessions.tabs.value.single()
        assertThat(tab.id).isEqualTo(id)
        assertThat(listOf(tab.packId, tab.path, tab.title)).containsExactly("p", "Photosynthesis", "Photosynthesis").inOrder()
        assertThat(tab.onRail).isTrue()
    }

    @Test fun emptyFieldShowsRecentSearches() = runTest(main.dispatcher) {
        sessions.searches.value = listOf("cats", "dogs")
        val vm = SearchViewModel(search, sessions, "")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.recent).containsExactly("cats", "dogs").inOrder()
    }

    @Test fun typedInputWaitsForTheDebounce() = runTest(main.dispatcher) {
        val vm = SearchViewModel(search, sessions, "")
        testScheduler.runCurrent()
        vm.onQueryChange("photo")
        testScheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS - 1)
        testScheduler.runCurrent()
        assertThat(search.titleQueries).isEmpty()
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertThat(search.titleQueries).containsExactly("photo")
    }
}
