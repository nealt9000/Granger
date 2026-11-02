package app.granger.search

import app.granger.MainDispatcherRule
import app.granger.FakeReadingSessions
import app.granger.core.library.LibrarySearch
import app.granger.core.library.SearchHit
import app.granger.core.library.TitleResults
import app.granger.core.speech.DictationEvent
import app.granger.voiceinput.FakeSpeech
import app.granger.voiceinput.fakeVoiceInput
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Speaking a search (Plan A1): the words fill the field, the search runs at once, "Did you mean" for misheard names. */
class SearchVoiceTest {
    @get:Rule val main = MainDispatcherRule()

    private class FakeSearch : LibrarySearch {
        val titleQueries = mutableListOf<String>()
        override suspend fun titles(query: String): TitleResults { titleQueries += query; return TitleResults(emptyList(), false) }
        override suspend fun fullText(query: String): List<SearchHit> = emptyList()
        override suspend fun books(query: String) = emptyList<app.granger.core.library.BookHit>()
    }

    private val search = FakeSearch()
    private val speech = FakeSpeech()
    private val names = listOf("The Dhammapada", "Crito", "Peter Pan", "Hector Berlioz")

    private fun TestScope.vm(): SearchViewModel {
        val vm = SearchViewModel(search, FakeReadingSessions(), "", voice = fakeVoiceInput(backgroundScope, speech), spokenNames = flowOf(names), matching = main.dispatcher)
        backgroundScope.launch { vm.micShown.collect {} }
        testScheduler.advanceUntilIdle()
        return vm
    }

    @Test fun aMisheardNameIsSearchedAsHeardAndOffered() = runTest(main.dispatcher) {
        val vm = vm()
        assertThat(vm.micShown.value).isTrue()
        vm.speak()
        testScheduler.advanceUntilIdle()
        speech.send(DictationEvent.Partial("Open the"))
        testScheduler.advanceUntilIdle()
        assertThat(vm.searchText).isEqualTo("Open the")
        speech.end(DictationEvent.Final("Open the domapada"))
        testScheduler.advanceUntilIdle()
        assertThat(vm.searchText).isEqualTo("Open the domapada")
        assertThat(search.titleQueries.last()).isEqualTo("Open the domapada")
        assertThat(vm.state.value.didYouMean).isEqualTo("The Dhammapada")
    }

    @Test fun tappingTheSuggestionSearchesTheName() = runTest(main.dispatcher) {
        val vm = vm()
        vm.speak()
        testScheduler.advanceUntilIdle()
        speech.end(DictationEvent.Final("Hector berleos"))
        testScheduler.advanceUntilIdle()
        vm.acceptSuggestion()
        testScheduler.advanceUntilIdle()
        assertThat(vm.searchText).isEqualTo("Hector Berlioz")
        assertThat(search.titleQueries.last()).isEqualTo("Hector Berlioz")
        assertThat(vm.state.value.didYouMean).isNull()
    }

    @Test fun aNameHeardRightNeedsNoSuggestion() = runTest(main.dispatcher) {
        val vm = vm()
        vm.speak()
        testScheduler.advanceUntilIdle()
        speech.end(DictationEvent.Final("Open Peter Pan"))
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.didYouMean).isNull()
    }

    @Test fun typingClearsTheSuggestionAndTypedSearchesNeverGetOne() = runTest(main.dispatcher) {
        val vm = vm()
        vm.speak()
        testScheduler.advanceUntilIdle()
        speech.end(DictationEvent.Final("the domapada"))
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.didYouMean).isNotNull()
        vm.onQueryChange("the domapada!")
        assertThat(vm.state.value.didYouMean).isNull()
        vm.onQueryChange("the domapada")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.didYouMean).isNull()
    }

    @Test fun withoutVoiceInputThereIsNoMic() = runTest(main.dispatcher) {
        val vm = SearchViewModel(search, FakeReadingSessions(), "")
        assertThat(vm.dictation).isNull()
        assertThat(vm.micShown.value).isFalse()
        vm.speak()
    }
}
