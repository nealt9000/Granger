package app.granger.feedback

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.granger.BuildConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1600dp")
class FeedbackTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val build = BuildFacts("0.1.850", 850, "Google Pixel 7", "16 (API 36)")

    private fun facts(crashes: List<CrashReport> = emptyList()) =
        FeedbackFacts(build, "Library and Ask", freeBytes = 63_000_000_000L, packs = 3, books = 1, crashes = crashes)

    private fun crash(time: Long, text: String): CrashReport {
        val file = tmp.newFile("crash-$time.txt").apply { writeText(text) }
        return CrashReport(file, time, CrashKind.NATIVE, text.lineSequence().first())
    }

    @Test fun theEmailCarriesTheBuildAndCountsOnly() {
        assertThat(FeedbackReport.subject(build)).isEqualTo("Granger feedback · 0.1.850 (850)")
        assertThat(FeedbackReport.included(facts(), withCrashes = true)).containsExactly(
            "Granger 0.1.850 (850)",
            "Google Pixel 7, Android 16 (API 36)",
            "This phone: Library and Ask",
            "63 GB free",
            "3 packs and 1 book (counts only, no titles)",
        ).inOrder()
        // Room for the user's words comes first.
        assertThat(FeedbackReport.body(facts(), withCrashes = true)).startsWith("\n\n\n—\nGranger 0.1.850 (850)\n")
        assertThat(FeedbackReport.attachment(facts(), withCrashes = true) { "" }).isNull()
    }

    @Test fun crashReportsAreAttachedNewestFirstUnlessLeftOut() {
        val withCrashes = facts(listOf(crash(2, "second crash"), crash(1, "first crash")))

        val attachment = FeedbackReport.attachment(withCrashes, withCrashes = true) { it.file.readText() }!!
        assertThat(attachment).contains("2 crash reports, attached")
        assertThat(attachment.indexOf("second crash")).isLessThan(attachment.indexOf("first crash"))
        assertThat(FeedbackReport.attachment(withCrashes, withCrashes = false) { it.file.readText() }).isNull()
        assertThat(FeedbackReport.included(withCrashes, withCrashes = false).joinToString()).doesNotContain("crash")
    }

    @Test fun theIntentIsAnEmailToNealWithTheReportLentReadOnly() {
        val intent = FeedbackMail.intent(context, facts(listOf(crash(1, "boom"))), withCrashes = true)

        assertThat(intent.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(intent.getStringArrayExtra(Intent.EXTRA_EMAIL)).asList().containsExactly(BuildConfig.FEEDBACK_EMAIL)
        assertThat(intent.getStringExtra(Intent.EXTRA_SUBJECT)).isEqualTo("Granger feedback · 0.1.850 (850)")
        assertThat(intent.selector?.data).isEqualTo(Uri.parse("mailto:"))
        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertThat(stream.authority).isEqualTo("${context.packageName}.reports")
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(context.contentResolver.openInputStream(stream)!!.use { String(it.readBytes()) }).contains("boom")
    }

    @Test fun withoutCrashesTheEmailHasNoAttachment() {
        val intent = FeedbackMail.intent(context, facts(), withCrashes = true)
        @Suppress("DEPRECATION")
        assertThat(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)).isNull()
    }

    @Test fun theSheetShowsWhatIsIncludedAndTheSwitchLeavesCrashesOut() {
        val sent = mutableListOf<Boolean>()
        compose.setContent { FeedbackSheet(facts(listOf(crash(1, "boom"))), onSend = { sent += it }, onClearCrashes = {}, onDismiss = {}) }

        compose.onNodeWithText("• 3 packs and 1 book (counts only, no titles)").assertExists()
        compose.onNodeWithText("• 1 crash report, attached").assertExists()
        compose.onNodeWithText("Attach crash reports").performClick()
        compose.onAllNodesWithText("• 1 crash report, attached").assertCountEquals(0)
        compose.onNodeWithText("Write email").performClick()
        assertThat(sent).containsExactly(false)
    }

    @Test fun aCrashReportCanBeReadBeforeSending() {
        compose.setContent { FeedbackSheet(facts(listOf(crash(1, "Native crash at noon\nAbort message: boom"))), onSend = {}, onClearCrashes = {}, onDismiss = {}) }

        compose.onNodeWithText("View").performClick()
        compose.onNodeWithText("Abort message: boom", substring = true).assertExists()
    }
}
