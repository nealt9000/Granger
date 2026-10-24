package app.granger.archive

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The 100 MB prompt: a tap on "Don't ask again" itself ticks it (in the walkthrough only the box did). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LargeDownloadDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tappingTheLabelTicksDontAskAgain() {
        var answer: Boolean? = null
        compose.setContent {
            LargeDownloadDialog(ConfirmLarge("id", pdf = true, bytes = 109_000_000, title = "PDF"), onDownload = { answer = it }, onDismiss = {})
        }
        compose.onNodeWithText("Don't ask again").performClick()
        compose.onNodeWithText("Download").performClick()
        assertThat(answer).isTrue()
    }

    @Test fun leftAloneItAsksAgain() {
        var answer: Boolean? = null
        compose.setContent {
            LargeDownloadDialog(ConfirmLarge("id", pdf = true, bytes = 109_000_000, title = "PDF"), onDownload = { answer = it }, onDismiss = {})
        }
        compose.onNodeWithText("Download").performClick()
        assertThat(answer).isFalse()
    }
}
