package app.granger.search

import app.granger.FakeReadingSessions
import app.granger.MainDispatcherRule
import app.granger.core.library.BookHit
import app.granger.core.library.LibrarySearch
import app.granger.core.library.SearchHit
import app.granger.core.library.TitleResults
import app.granger.core.research.BestMatches
import app.granger.core.research.Passage
import app.granger.core.research.SourceKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Plan SQ Phase 5: filters, the Define card, the medical banner, the empty state and a typed "Did you mean". */
class SearchResultsPageTest {
    @get:Rule val main = MainDispatcherRule()

    private class Search(
        var titles: List<SearchHit> = emptyList(),
        var fullText: List<SearchHit> = emptyList(),
        var books: List<BookHit> = emptyList(),
    ) : LibrarySearch {
        override suspend fun titles(query: String) = TitleResults(titles)
        override suspend fun fullText(query: String) = fullText
        override suspend fun books(query: String) = books
    }

    private fun match(kind: SourceKind, id: String) = BestMatches.Match(
        Passage(kind, id, "Title $id", packTitle = "Pack", location = id, text = "Some text about $id."), null,
    )

    private fun best(vararg matches: BestMatches.Match) =
        BestMatchSearch { flowOf(BestMatches.Result(matches.toList(), listOf("burn"), byMeaning = true, final = true)) }

    private val wiktionary = Passage(SourceKind.ARTICLE, "wikt", "serendipity", packTitle = "Wiktionary", location = "serendipity", text = "Noun: a happy accident")

    @Test fun theKindsFoundDecideTheChipsAndAFilterNarrowsEveryGroup() = runTest(main.dispatcher) {
        val search = Search(
            titles = listOf(SearchHit("wikipedia", "Wikipedia", "Burn", "Burn"), SearchHit("book:granger:burns", "Classics", "", "Poems of Burns")),
            fullText = listOf(SearchHit("wikipedia", "Wikipedia", "Fire", "Fire")),
            books = listOf(BookHit("book:granger:x", "A Book", "", "c1.xhtml", 0.1, "burn", null)),
        )
        val vm = SearchViewModel(search, FakeReadingSessions(), "burn", best = best(match(SourceKind.ARTICLE, "a"), match(SourceKind.BOOK, "b")), matching = main.dispatcher)
        testScheduler.advanceUntilIdle()
        val all = vm.state.value
        assertThat(all.kinds).containsExactly(SearchFilter.ARTICLES, SearchFilter.BOOKS)

        vm.setFilter(SearchFilter.BOOKS)
        val books = vm.state.value
        assertThat(books.titlesFiltered.map { it.title }).containsExactly("Poems of Burns")
        assertThat(books.bestFiltered.map { it.passage.sourceId }).containsExactly("b")
        assertThat(books.showing(SearchFilter.ARTICLES)).isFalse()
        assertThat(books.showing(SearchFilter.BOOKS)).isTrue()

        vm.setFilter(SearchFilter.ARTICLES)
        assertThat(vm.state.value.titlesFiltered.map { it.title }).containsExactly("Burn")
        assertThat(vm.state.value.bestFiltered.map { it.passage.sourceId }).containsExactly("a")
        // The filter stays for the next query.
        vm.onQueryChange("fire")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.filter).isEqualTo(SearchFilter.ARTICLES)
    }

    @Test fun aSingleWordGetsItsDictionaryEntryAndAPhraseDoesNot() = runTest(main.dispatcher) {
        val asked = mutableListOf<String>()
        val define = WordEntry { asked += it; wiktionary }
        val vm = SearchViewModel(Search(), FakeReadingSessions(), "serendipity", define = define, matching = main.dispatcher)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.define?.sourceTitle).isEqualTo("serendipity")
        assertThat(vm.state.value.nothingFound).isFalse()

        vm.onQueryChange("happy accident")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.define).isNull()
        assertThat(asked).containsExactly("serendipity")
    }

    @Test fun oneWordMeansLettersOnly() {
        assertThat(SearchViewModel.isOneWord("café")).isTrue()
        assertThat(SearchViewModel.isOneWord("x-ray")).isTrue()
        assertThat(SearchViewModel.isOneWord("a")).isFalse()
        assertThat(SearchViewModel.isOneWord("1984")).isFalse()
        assertThat(SearchViewModel.isOneWord("two words")).isFalse()
    }

    @Test fun aMedicalQueryCarriesTheBannerInEitherLanguage() = runTest(main.dispatcher) {
        val vm = SearchViewModel(Search(titles = listOf(SearchHit("p", "P", "Malaria", "Malaria"))), FakeReadingSessions(), "symptoms of malaria", matching = main.dispatcher)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.medical).isTrue()
        vm.onQueryChange("fiebre en bebés")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.medical).isTrue()
        vm.onQueryChange("battle of hastings")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.medical).isFalse()
    }

    @Test fun bestMatchesAloneAreNotNothingFound() = runTest(main.dispatcher) {
        val vm = SearchViewModel(Search(), FakeReadingSessions(), "burn", best = best(match(SourceKind.ARTICLE, "a")), matching = main.dispatcher)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.nothingFound).isFalse()
        assertThat(vm.state.value.keywordsFoundNothing).isTrue()
        assertThat(vm.state.value.bestWords).containsExactly("burn")
    }

    @Test fun aTypedSearchThatFindsNothingOffersTheNameItMeant() = runTest(main.dispatcher) {
        val vm = SearchViewModel(
            Search(), FakeReadingSessions(), "", spokenNames = flowOf(listOf("Missoula", "Moby-Dick")), matching = main.dispatcher,
        )
        vm.onQueryChange("Misoula")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.nothingFound).isTrue()
        assertThat(vm.state.value.didYouMean).isEqualTo("Missoula")
        assertThat(vm.state.value.typedSuggestion).isTrue()
        // Only offered: the query stays as typed until the suggestion is tapped.
        assertThat(vm.state.value.query).isEqualTo("Misoula")
        vm.acceptSuggestion()
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.query).isEqualTo("Missoula")
    }

    @Test fun aTypedSearchThatFindsSomethingOffersNothing() = runTest(main.dispatcher) {
        val vm = SearchViewModel(
            Search(titles = listOf(SearchHit("p", "P", "Misoula", "Misoula"))), FakeReadingSessions(), "",
            spokenNames = flowOf(listOf("Missoula")), matching = main.dispatcher,
        )
        vm.onQueryChange("Misoula")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.didYouMean).isNull()
    }

    @Test fun matchingWordsAreBoldCaseAndAccentsAside() {
        val text = SearchRows.highlight("Burns heal; the BURN of a café.", listOf("burn", "cafe"))
        val bold = text.spanStyles.map { text.text.substring(it.start, it.end) }
        assertThat(bold).containsExactly("Burns", "BURN", "café")
    }

    @Test fun shortKeywordsMatchWholeWordsOnly() {
        val text = SearchRows.highlight("An ox and an oxen.", listOf("ox"))
        assertThat(text.spanStyles.map { text.text.substring(it.start, it.end) }).containsExactly("ox")
    }

    @Test fun theDefineCardLeavesOutTheEtymology() {
        assertThat(SearchRows.defineText("Etymology: From Latin.\nNoun: a happy accident\n1. Luck."))
            .isEqualTo("Noun: a happy accident 1. Luck.")
        assertThat(SearchRows.defineText("Etimología: Del latín.")).isEqualTo("Etimología: Del latín.")
    }
}
