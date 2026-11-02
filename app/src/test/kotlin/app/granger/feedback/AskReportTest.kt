package app.granger.feedback

import android.content.Intent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.granger.BuildConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Report this answer (release checklist A, Play's AI-generated content policy). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1600dp")
class AskReportTest {
    @get:Rule val compose = createComposeRule()

    private val build = BuildFacts("0.1.860", 860, "Google Pixel 7", "16 (API 36)")

    private fun report(guess: Boolean = false, note: String = "") = AskReport(
        AskReport.Reason.WRONG, note, "Who is Mr. Darcy?",
        if (guess) "Mr. Darcy is a character." else "Mr. Darcy owns Pemberley [1].",
        guess, if (guess) emptyList() else listOf("Pride and Prejudice · Jane Austen · Chapter 43"), build,
    )

    @Test fun theEmailCarriesTheNoteReasonQuestionReplyAndSources() {
        assertThat(report(note = "  It mixed him up with Bingley. ").body()).isEqualTo(
            """
            It mixed him up with Bingley.

            —
            Reason: Wrong or made up
            Question: Who is Mr. Darcy?

            Answer:
            Mr. Darcy owns Pemberley [1].

            Sources:
            1. Pride and Prejudice · Jane Austen · Chapter 43

            Granger 0.1.860 (860) · Google Pixel 7 · Android 16 (API 36)

            """.trimIndent(),
        )
        assertThat(report().subject()).isEqualTo("Granger Ask report · 0.1.860 (860)")
    }

    @Test fun aGuessIsNamedAsOneAndHasNoSources() {
        val body = report(guess = true).body()
        assertThat(body).startsWith("—\n")
        assertThat(body).contains("Best guess:\nMr. Darcy is a character.\n")
        assertThat(body).doesNotContain("Sources:")
    }

    @Test fun theIntentGoesToTheFeedbackAddress() {
        val intent = report().intent()
        assertThat(intent.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(intent.getStringArrayExtra(Intent.EXTRA_EMAIL)).asList().containsExactly(BuildConfig.FEEDBACK_EMAIL)
        assertThat(intent.getStringExtra(Intent.EXTRA_TEXT)).contains("Question: Who is Mr. Darcy?")
    }

    @Test fun theSheetSendsOnlyOnceAReasonIsPicked() {
        val sent = mutableListOf<Pair<AskReport.Reason, String>>()
        compose.setContent { ReportSheet(guess = false, sources = 2, onSend = { r, n -> sent += r to n }, onDismiss = {}) }
        compose.onNodeWithText("Report this answer").assertExists()
        compose.onNodeWithText("The titles of its 2 sources", substring = true).assertExists()
        compose.onNodeWithText("Write email").assertIsNotEnabled()
        compose.onNodeWithText("Unsafe or harmful").performClick()
        compose.onNodeWithText("Anything to add (optional)").performTextInput("Dangerous dose")
        compose.onNodeWithText("Write email").assertIsEnabled().performClick()
        assertThat(sent).containsExactly(AskReport.Reason.UNSAFE to "Dangerous dose")
    }
}
