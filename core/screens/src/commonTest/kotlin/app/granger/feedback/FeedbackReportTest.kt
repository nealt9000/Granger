package app.granger.feedback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared email's rules (Plan I7 Task 1); Android's `FeedbackTest` covers the same through its wrappers. */
class FeedbackReportTest {
    private val build = BuildFacts("0.1.850", 850, "Google Pixel 7", "16 (API 36)", "Android")
    private val iphone = BuildFacts("0.1.900", 900, "iPhone 16", "18.2", "iOS")

    private fun facts(crashes: List<CrashReport> = emptyList(), b: BuildFacts = build) =
        FeedbackFacts(b, "Library and Ask", freeBytes = 63_000_000_000L, packs = 3, books = 1, crashes = crashes)

    @Test fun theEmailCarriesTheBuildAndCountsOnly() {
        assertEquals("Granger feedback · 0.1.850 (850)", FeedbackReport.subject(build))
        assertEquals(
            listOf(
                "Granger 0.1.850 (850)",
                "Google Pixel 7, Android 16 (API 36)",
                "This phone: Library and Ask",
                "63 GB free",
                "3 packs and 1 book (counts only, no titles)",
            ),
            FeedbackReport.included(facts(), withCrashes = true),
        )
        assertTrue(FeedbackReport.body(facts(), withCrashes = true).startsWith("\n\n\n—\nGranger 0.1.850 (850)\n"))
        assertNull(FeedbackReport.attachment(facts(), withCrashes = true) { "" })
    }

    @Test fun theIphoneNamesItsSystem() {
        assertEquals("iPhone 16, iOS 18.2", FeedbackReport.included(facts(b = iphone), withCrashes = false)[1])
        assertEquals("Granger 0.1.900 (900) · iPhone 16 · iOS 18.2", iphone.header())
    }

    @Test fun crashReportsAreAttachedNewestFirstUnlessLeftOut() {
        val texts = mapOf("/c/2" to "second crash", "/c/1" to "first crash")
        val withCrashes = facts(listOf(CrashReport("/c/2", 2, CrashKind.NATIVE, "second"), CrashReport("/c/1", 1, CrashKind.ANR, "first")))

        val attachment = FeedbackReport.attachment(withCrashes, withCrashes = true) { texts.getValue(it.path) }!!
        assertTrue("2 crash reports, attached" in attachment)
        assertTrue(attachment.indexOf("second crash") < attachment.indexOf("first crash"))
        assertNull(FeedbackReport.attachment(withCrashes, withCrashes = false) { texts.getValue(it.path) })
        assertFalse("crash" in FeedbackReport.included(withCrashes, withCrashes = false).joinToString())
    }

    @Test fun anAskReportCarriesTheChatAndTheBuild() {
        val report = AskReport(AskReport.Reason.WRONG, " too sure ", "Who is Mr. Darcy?", "A banker.", false, listOf("Pride and Prejudice"), build)
        assertEquals("Granger Ask report · 0.1.850 (850)", report.subject())
        assertEquals(
            "too sure\n\n—\nReason: Wrong or made up\nQuestion: Who is Mr. Darcy?\n\nAnswer:\nA banker.\n\n" +
                "Sources:\n1. Pride and Prejudice\n\nGranger 0.1.850 (850) · Google Pixel 7 · Android 16 (API 36)\n",
            report.body(),
        )
    }
}
