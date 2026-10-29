package app.granger.ebook

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What Contents lists, and what it says when there is nothing to list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContentsPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aPdfWithNoOutlineSaysSo() {
        compose.setContent { ContentsPanel(EbookUi(isPdf = true), onGoTo = {}, onMark = {}, onDismiss = {}) }
        compose.onNodeWithText(NO_PDF_CONTENTS).assertIsDisplayed()
    }

    @Test fun aBookWithNoContentsSaysSo() {
        compose.setContent { ContentsPanel(EbookUi(), onGoTo = {}, onMark = {}, onDismiss = {}) }
        compose.onNodeWithText("This book has no contents list.").assertIsDisplayed()
    }

    @Test fun aPdfsOutlineIsListedAndARowGoesToItsPage() {
        val gone = mutableListOf<String>()
        val toc = listOf(TocRow("Water", "doc.pdf#page=1", 0), TocRow("Filters", "doc.pdf#page=3", 1))
        compose.setContent { ContentsPanel(EbookUi(isPdf = true, toc = toc), onGoTo = { gone += it }, onMark = {}, onDismiss = {}) }
        compose.onNodeWithText("Filters").performClick()
        assertThat(gone).containsExactly("doc.pdf#page=3")
    }
}
