package app.granger.core.speech

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SentenceBreakCases] with the iPhone's ICU (`CFStringTokenizer`): Android's breaks, except the cases recorded as
 * different (titles and "a.m." aren't sentence ends here). A new difference, either way, fails.
 */
class SentenceBreaksIosTest {
    @Test fun theSameParagraphsBreakAsOnAndroidExceptWhereRecorded() {
        val differences = SentenceBreakCases.differences(ios = true)
        differences.forEach { println("iOS sentence breaks differ: $it") }
        assertEquals(emptyList(), differences)
    }

    @Test fun theVoiceHearsTwoDifferences() {
        val spoken = SentenceBreakCases.cases.filter { it.iosSentences != null }.map { it.text }
        assertEquals(
            listOf(
                "Mrs. Jones met St. John at 10 a.m. They talked for 2.5 hours. Then they parted.",
                "La Dra. López dijo: «Es elemental». Luego se fue... Nadie la siguió.",
            ),
            spoken,
        )
    }
}
