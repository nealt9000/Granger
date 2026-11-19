package app.granger.search

import app.granger.FakeReadingSessions
import app.granger.MainDispatcherRule
import app.granger.core.library.BookHit
import app.granger.core.library.LibrarySearch
import app.granger.core.library.SearchHit
import app.granger.core.library.TitleResults
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Plan O1 Task 7: Wikipedia online's hits under the phone's, one row per article, never holding the phone's up. */
class SearchOnlineTest {
    @get:Rule val main = MainDispatcherRule()

    private val search = object : LibrarySearch {
        override suspend fun titles(query: String) = TitleResults(listOf(SearchHit("p", "Pack", "Moon", "Moon")))
        override suspend fun fullText(query: String) = listOf(SearchHit("p", "Pack", "Tide", "Tide", "the <b>moon</b>"))
        override suspend fun books(query: String) = emptyList<BookHit>()
    }

    private fun web(title: String, snippet: String? = null) = SearchHit("wikipedia_en_online", "Wikipedia", title.replace(' ', '_'), title, snippet)

    @Test fun onlineHitsGoUnderThePhonesWithoutDuplicates() = runTest(main.dispatcher) {
        val online = OnlineSearch { listOf(web("Moon"), web("Moon landing")) to listOf(web("Tide", "x"), web("Lunar phase", "y"), web("Moon landing", "z")) }
        val vm = SearchViewModel(search, FakeReadingSessions(), "moon", matching = main.dispatcher, online = online)
        testScheduler.advanceUntilIdle()
        val s = vm.state.value
        // Moon is on the phone: its online row goes.
        assertThat(s.titlesFiltered.map { it.packId to it.title }).containsExactly("p" to "Moon", "wikipedia_en_online" to "Moon landing").inOrder()
        // Tide is on the phone and Moon landing is already a title: Articles adds only Lunar phase.
        assertThat(s.articles.map { it.title }).containsExactly("Tide", "Lunar phase").inOrder()
        assertThat(s.nothingFound).isFalse()
    }

    @Test fun aSlowOnlineSearchDoesntHoldThePhonesResults() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val online = OnlineSearch { gate.await(); listOf(web("Moon landing")) to emptyList() }
        val vm = SearchViewModel(search, FakeReadingSessions(), "moon", matching = main.dispatcher, online = online)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.searching).isFalse()
        assertThat(vm.state.value.titlesFiltered.map { it.title }).containsExactly("Moon")
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.titlesFiltered.map { it.title }).containsExactly("Moon", "Moon landing").inOrder()
    }

    @Test fun noSignalSaysNothing() = runTest(main.dispatcher) {
        val vm = SearchViewModel(search, FakeReadingSessions(), "moon", matching = main.dispatcher, online = OnlineSearch { throw java.io.IOException("Local only") })
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.titlesFiltered.map { it.title }).containsExactly("Moon")
        assertThat(vm.state.value.onlineTitles).isEmpty()
    }

    @Test fun anOnlineOnlyResultIsNotNothingFound() = runTest(main.dispatcher) {
        val empty = object : LibrarySearch {
            override suspend fun titles(query: String) = TitleResults(emptyList())
            override suspend fun fullText(query: String) = emptyList<SearchHit>()
            override suspend fun books(query: String) = emptyList<BookHit>()
        }
        val vm = SearchViewModel(empty, FakeReadingSessions(), "knowth", matching = main.dispatcher, online = OnlineSearch { listOf(web("Knowth")) to emptyList() })
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.nothingFound).isFalse()
        assertThat(vm.state.value.titlesFiltered.single().title).isEqualTo("Knowth")
    }
}
