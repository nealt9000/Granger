package app.granger.search

import app.granger.FakeReadingSessions
import app.granger.MainDispatcherRule
import app.granger.BookTarget
import app.granger.core.books.TextQuote
import app.granger.core.library.BookHit
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
        var bookHits = listOf(BookHit("import:abc", "Emma", "Jane Austen", "OEBPS/ch2.xhtml", 0.25, "a <b>photo</b> of", TextQuote("a ", "photo", " of")))
        override suspend fun books(query: String) = bookHits
    }

    private val search = FakeSearch()
    private val sessions = FakeReadingSessions()

    @Test fun theSearchFieldTextUpdatesSynchronously() = runTest(main.dispatcher) {
        val vm = SearchViewModel(search, sessions, "pho")
        assertThat(vm.searchText).isEqualTo("pho")
        vm.onQueryChange("phot"); vm.onQueryChange("photo")
        assertThat(vm.searchText).isEqualTo("photo")
    }

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

    @Test fun bookHitsAppearInTheirOwnSection() = runTest(main.dispatcher) {
        val vm = SearchViewModel(search, sessions, "")
        vm.onQueryChange("photo")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.books.map { it.bookTitle }).containsExactly("Emma")
        assertThat(vm.state.value.titles).hasSize(1)
        assertThat(vm.state.value.searching).isFalse()
        search.bookHits = emptyList()
        vm.onQueryChange("photos")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.books).isEmpty()
    }

    @Test fun openingABookHitRecordsTheSearchAndTargetsTheHit() = runTest(main.dispatcher) {
        val vm = SearchViewModel(search, sessions, "")
        vm.onQueryChange(" photo ")
        testScheduler.advanceUntilIdle()
        val hit = vm.state.value.books.single()
        val (id, target) = vm.openBook(hit)
        val (again, _) = vm.openBook(hit)
        assertThat(again).isEqualTo(id)
        assertThat(sessions.searches.value).containsExactly("photo")
        val tab = sessions.tabs.value.single()
        assertThat(tab.id).isEqualTo(id)
        assertThat(listOf(tab.packId, tab.path, tab.title)).containsExactly("import:abc", "", "Emma").inOrder()
        assertThat(target).isEqualTo(BookTarget("OEBPS/ch2.xhtml", 0.25, TextQuote("a ", "photo", " of")))
    }
}
