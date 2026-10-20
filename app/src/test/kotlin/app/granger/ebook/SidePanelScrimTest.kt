package app.granger.ebook

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The e-book side panels' scrim covers the whole reader, its left edge too: the shell's rail sits beside the reader
 * now, so a strip left clear there would pass taps to the page behind (and turn it) instead of closing the panel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h480dp") // Wider than the 320 dp sheet, so the left edge is scrim.
class SidePanelScrimTest {
    @get:Rule val compose = createComposeRule()

    private var dismissed = false

    private fun tapAtTheLeftEdge(panel: @Composable () -> Unit) {
        compose.setContent { Box(Modifier.fillMaxSize().testTag("reader")) { panel() } }
        compose.onNodeWithTag("reader").performTouchInput { click(Offset(8.dp.toPx(), centerY)) }
        compose.waitForIdle()
    }

    @Test fun contentsPanelClosesOnATapAtTheLeftEdge() {
        tapAtTheLeftEdge { ContentsPanel(EbookUi(), onGoTo = {}, onMark = {}, onDismiss = { dismissed = true }) }
        assertThat(dismissed).isTrue()
    }

    @Test fun searchPanelClosesOnATapAtTheLeftEdge() {
        tapAtTheLeftEdge { BookSearchPanel(BookSearchUi(query = "x"), onSearch = {}, onPick = {}, onDismiss = { dismissed = true }) }
        assertThat(dismissed).isTrue()
    }
}
