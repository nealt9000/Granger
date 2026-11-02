package app.granger.voiceinput

import app.granger.core.library.LibraryNameRow
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpokenTitlesTest {
    @Test fun followsTheLibrary() = runTest(UnconfinedTestDispatcher()) {
        val rows = MutableStateFlow(listOf(LibraryNameRow(true, "Peter Pan: The Boy Who Wouldn't Grow Up")))
        val titles = SpokenTitles(rows, backgroundScope)
        val seen = mutableListOf<List<String>>()
        backgroundScope.launch { titles.names.collect { seen += it } }
        assertThat(titles.names.first { it.isNotEmpty() }).containsExactly("Peter Pan")
        rows.value = rows.value + LibraryNameRow(false, "J. M. Barrie")
        assertThat(titles.names.first { it.size == 2 }).containsExactly("Peter Pan", "J. M. Barrie").inOrder()
    }
}
