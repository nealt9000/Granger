package app.granger.search

import app.granger.FakeBookshelf
import app.granger.FakeLibrary
import app.granger.FakeReadingSessions
import app.granger.FakeZimArchive
import app.granger.book
import app.granger.pack
import app.granger.MainDispatcherRule
import app.granger.BookTarget
import app.granger.core.books.TextQuote
import app.granger.core.library.BookHit
import app.granger.core.library.BookIds
import app.granger.core.library.LibrarySearch
import app.granger.core.library.ArtistHit
import app.granger.core.library.MediaResults
import app.granger.core.library.SearchHit
import app.granger.core.library.TitleResults
import app.granger.core.library.ZimLibrarySearch
import app.granger.core.zim.ZimHit
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

        /** Set where the search says some pack had more titles than it was asked for. */
        var titlesMore = false
        override suspend fun titles(query: String): TitleResults { titleQueries += query; return TitleResults(titles, titlesMore) }
        /** Completed by a test that wants to look at the state while full text is still being searched. */
        var fullTextGate: CompletableDeferred<Unit>? = null
        override suspend fun fullText(query: String): List<SearchHit> { fullTextGate?.await(); return fullText }
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

    @Test fun aSnippetWithoutTheQueryIsDroppedButTheHitStays() = runTest(main.dispatcher) {
        search.fullText = listOf(
            SearchHit("p", "Pack", "Leaf", "Leaf", "<b>photo</b>synthesis in leaves"),
            SearchHit("se", "Parenting", "questions/1/x", "Night feeds", "Reputation 1,204 · Badges · Member for 6 years"),
            SearchHit("p", "Pack", "Café", "Café", "A <i>PHÓTO</i> of the café"),
        )
        val vm = SearchViewModel(search, sessions, "")
        vm.onQueryChange("photo")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.fullText.map { it.title to it.snippet }).containsExactly(
            "Leaf" to "<b>photo</b>synthesis in leaves",
            "Night feeds" to null,
            "Café" to "A <i>PHÓTO</i> of the café",
        ).inOrder()
    }

    @Test fun bookHitsGroupByBookKeepingTheFirst() = runTest(main.dispatcher) {
        fun hit(id: String, title: String, href: String, matches: Int) =
            BookHit(id, title, "", href, 0.0, "<b>photo</b> $href", matches = matches)
        search.bookHits = listOf(
            hit("b:emma", "Emma", "ch2", 3), hit("b:emma", "Emma", "ch5", 3), hit("b:idiot", "The Idiot", "p1", 1), hit("b:emma", "Emma", "ch9", 3),
        )
        val vm = SearchViewModel(search, sessions, "")
        vm.onQueryChange("photo")
        testScheduler.advanceUntilIdle()
        val books = vm.state.value.books
        assertThat(books.map { it.bookTitle to it.href }).containsExactly("Emma" to "ch2", "The Idiot" to "p1").inOrder()
        assertThat(books.map { it.matches }).containsExactly(3, 1).inOrder()
        // The tap opens the book at its first match.
        assertThat(vm.openBook(books.first()).second.href).isEqualTo("ch2")
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

    private val holst = MediaResults(artists = listOf(ArtistHit("Gustav Holst", 2, null)))

    @Test fun mediaResultsArriveBeforeArticleText() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>().also { search.fullTextGate = it }
        val vm = SearchViewModel(search, sessions, "", MediaSearch { holst })
        vm.onQueryChange("holst")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.media).isEqualTo(holst)
        assertThat(vm.state.value.fullText).isEmpty()
        assertThat(vm.state.value.searching).isTrue()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.fullText.map { it.path }).containsExactly("Leaf")
        assertThat(vm.state.value.media).isEqualTo(holst)
    }

    @Test fun peterPanPutsTheBookFirstInTitlesAndOpensIt() = runTest(main.dispatcher) {
        val library = FakeLibrary().apply {
            packList.value = listOf(pack("wiki", "Wikipedia"))
            archives["wiki"] = FakeZimArchive(
                "wiki",
                suggestions = listOf(ZimHit("Peter_Pan", "Peter Pan"), ZimHit("Peter_Pan_(2003_film)", "Peter Pan (2003 film)")),
            )
        }
        val shelf = FakeBookshelf().apply { bookList.value = listOf(book("book:granger:peter_pan", "Peter Pan", "J. M. Barrie")) }
        val vm = SearchViewModel(ZimLibrarySearch(library, io = main.dispatcher, bookshelf = shelf), sessions, "")
        vm.onQueryChange("peter pan")
        testScheduler.advanceUntilIdle()

        val titles = vm.state.value.titles
        assertThat(titles.map { it.packId to it.title }).containsExactly(
            "book:granger:peter_pan" to "Peter Pan", "wiki" to "Peter Pan", "wiki" to "Peter Pan (2003 film)",
        ).inOrder()
        assertThat(titles.first().author).isEqualTo("J. M. Barrie")
        // A book id, so the tab opens in the book reader.
        assertThat(BookIds.isBook(titles.first().packId)).isTrue()
        val id = vm.open(titles.first())
        val tab = sessions.tabs.value.single()
        assertThat(tab.id).isEqualTo(id)
        assertThat(listOf(tab.packId, tab.path, tab.title)).containsExactly("book:granger:peter_pan", "", "Peter Pan").inOrder()
    }

    @Test fun booksArriveBeforeArticleText() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>().also { search.fullTextGate = it }
        val vm = SearchViewModel(search, sessions, "")
        vm.onQueryChange("photo")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.books.map { it.bookTitle }).containsExactly("Emma")
        assertThat(vm.state.value.fullText).isEmpty()
        assertThat(vm.state.value.searching).isTrue()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.searching).isFalse()
    }

    @Test fun blankQueryClearsMedia() = runTest(main.dispatcher) {
        val vm = SearchViewModel(search, sessions, "", MediaSearch { holst })
        vm.onQueryChange("holst")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.media.isEmpty).isFalse()
        vm.onQueryChange("")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.media.isEmpty).isTrue()
    }

    @Test fun mediaFailureLeavesTheOtherGroups() = runTest(main.dispatcher) {
        val vm = SearchViewModel(search, sessions, "", MediaSearch { error("index broke") })
        vm.onQueryChange("photo")
        testScheduler.advanceUntilIdle()
        val state = vm.state.value
        assertThat(state.media.isEmpty).isTrue()
        assertThat(state.titles.map { it.path }).containsExactly("Photosynthesis")
        assertThat(state.fullText.map { it.path }).containsExactly("Leaf")
        assertThat(state.books.map { it.bookTitle }).containsExactly("Emma")
        assertThat(state.searching).isFalse()
    }

    @Test fun openingAMediaResultRecordsTheSearch() = runTest(main.dispatcher) {
        val vm = SearchViewModel(search, sessions, "", MediaSearch { holst })
        vm.onQueryChange(" holst ")
        testScheduler.advanceUntilIdle()
        vm.recordSearch()
        assertThat(sessions.searches.value).containsExactly("holst")
        assertThat(sessions.tabs.value).isEmpty()
    }

    private fun hits(n: Int, prefix: String = "T") = (1..n).map { SearchHit("p", "Pack", "$prefix$it", "$prefix $it") }

    @Test fun aGroupShowsItsPreviewUntilShowAll() = runTest(main.dispatcher) {
        search.titles = hits(12)
        val vm = SearchViewModel(search, sessions, "photo")
        testScheduler.advanceUntilIdle()
        val state = vm.state.value
        assertThat(state.shown(SearchGroup.TITLES, state.titles)).hasSize(SearchGroup.TITLES.preview)
        vm.toggle(SearchGroup.TITLES)
        assertThat(vm.state.value.let { it.shown(SearchGroup.TITLES, it.titles) }).hasSize(12)
        // Other groups stay at their preview.
        assertThat(vm.state.value.expanded).containsExactly(SearchGroup.TITLES)
        vm.toggle(SearchGroup.TITLES)
        assertThat(vm.state.value.let { it.shown(SearchGroup.TITLES, it.titles) }).hasSize(SearchGroup.TITLES.preview)
    }

    @Test fun aNewQueryCollapsesExpandedGroups() = runTest(main.dispatcher) {
        search.titles = hits(12)
        val vm = SearchViewModel(search, sessions, "photo")
        testScheduler.advanceUntilIdle()
        vm.toggle(SearchGroup.TITLES)
        vm.toggle(SearchGroup.SONGS)
        vm.onQueryChange("photos")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.expanded).isEmpty()
        vm.toggle(SearchGroup.TITLES)
        vm.onQueryChange("")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.expanded).isEmpty()
    }

    @Test fun groupsThatFillTheirFetchAreMarkedCapped() = runTest(main.dispatcher) {
        search.titles = hits(SearchGroup.TITLES.fetch)
        // Every full-text hit counts towards the cap, including those hidden as duplicates of a title.
        search.fullText = hits(SearchGroup.FULL_TEXT.fetch)
        val vm = SearchViewModel(search, sessions, "photo")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.fullText).isEmpty()
        assertThat(vm.state.value.capped).containsExactly(SearchGroup.TITLES, SearchGroup.FULL_TEXT)
        search.titles = hits(SearchGroup.TITLES.fetch - 1)
        vm.onQueryChange("photos")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.capped).doesNotContain(SearchGroup.TITLES)
        // A pack that filled its share may have more, though the total is short of the limit.
        search.titlesMore = true
        search.titles = hits(20)
        vm.onQueryChange("photon")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.capped).contains(SearchGroup.TITLES)
    }

    @Test fun resultsArriveWithTheirCapSoTheLabelDoesNotFlicker() = runTest(main.dispatcher) {
        search.titles = hits(SearchGroup.TITLES.fetch)
        search.fullText = hits(SearchGroup.FULL_TEXT.fetch, prefix = "F")
        val vm = SearchViewModel(search, sessions, "")
        val seen = mutableListOf<SearchViewModel.State>()
        // Unconfined, so the collector sees every value the state passes through.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { seen += it } }
        vm.onQueryChange("photo")
        testScheduler.advanceUntilIdle()
        assertThat(seen.filter { it.titles.isNotEmpty() }.map { SearchGroup.TITLES in it.capped }.distinct()).containsExactly(true)
        assertThat(seen.filter { it.fullText.isNotEmpty() }.map { SearchGroup.FULL_TEXT in it.capped }.distinct()).containsExactly(true)
    }
}
