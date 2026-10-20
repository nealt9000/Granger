package app.granger.ebook

import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The book stays on the text being read when the page is resized (the toolbar shown or hidden), not just on a page number. */
class SpotKeeperTest {
    private val restored = mutableListOf<String>()
    private var spot = "chapter 3, the line at the top"

    private fun TestScope.keeper() = SpotKeeper<String>(this, settleMs = 300) { restored += it }

    @Test fun theFirstLayoutIsNotAResize() = runTest {
        val keeper = keeper()
        keeper.onSize(IntSize(1080, 2000)) { spot }
        advanceUntilIdle()
        assertThat(restored).isEmpty()
    }

    @Test fun aResizeGoesBackToTheSpotOnceThePageHasSettled() = runTest {
        val keeper = keeper()
        keeper.onSize(IntSize(1080, 2000)) { spot }
        keeper.onSize(IntSize(1000, 1800)) { spot }
        // Readium re-paginates and reports a place by page number meanwhile; the spot taken before the resize wins.
        spot = "chapter 3, a line further on"
        advanceTimeBy(299)
        runCurrent()
        assertThat(restored).isEmpty()
        advanceTimeBy(2)
        runCurrent()
        assertThat(restored).containsExactly("chapter 3, the line at the top")
    }

    @Test fun resizesInQuickSuccessionGoBackOnceToTheSpotBeforeTheFirst() = runTest {
        val keeper = keeper()
        keeper.onSize(IntSize(1080, 2000)) { spot }
        keeper.onSize(IntSize(1000, 1900)) { spot }
        spot = "somewhere the first resize left it"
        advanceTimeBy(100)
        keeper.onSize(IntSize(1000, 1800)) { spot }
        advanceUntilIdle()
        assertThat(restored).containsExactly("chapter 3, the line at the top")
        // Settled: the next resize takes a fresh spot.
        keeper.onSize(IntSize(1080, 2000)) { spot }
        advanceUntilIdle()
        assertThat(restored).containsExactly("chapter 3, the line at the top", "somewhere the first resize left it").inOrder()
    }

    @Test fun anUnchangedSizeOrNoSpotDoesNothing() = runTest {
        val keeper = keeper()
        keeper.onSize(IntSize(1080, 2000)) { spot }
        keeper.onSize(IntSize(1080, 2000)) { spot }
        keeper.onSize(IntSize(1000, 1800)) { null }
        advanceUntilIdle()
        assertThat(restored).isEmpty()
    }
}
