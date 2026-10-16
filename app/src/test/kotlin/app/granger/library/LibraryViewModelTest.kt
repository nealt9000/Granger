package app.granger.library

import app.granger.FakeLibrary
import app.granger.MainDispatcherRule
import app.granger.pack
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class LibraryViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val library = FakeLibrary()

    @Test fun refreshRescansTheLibrary() = runTest(main.dispatcher) {
        val vm = LibraryViewModel(library)
        vm.refresh()
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertThat(library.refreshes).isEqualTo(2)
    }

    @Test fun exposesPacks() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("a", "Classics"), pack("b", "WikiMed"))
        val vm = LibraryViewModel(library)
        assertThat(vm.packs.filterNotNull().first().map { it.title }).containsExactly("Classics", "WikiMed")
    }
}
