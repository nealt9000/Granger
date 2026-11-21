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

    @Test fun theVoiceHearsOneDifference() {
        val spoken = SentenceBreakCases.cases.filter { it.iosSentences != null }.map { it.text }
        assertEquals(listOf("La Dra. López dijo: «Es elemental». Luego se fue... Nadie la siguió."), spoken)
    }

    /** Plan I5 Task 8: ICU's break after "a.m."/"p.m." before a capital, which `CFStringTokenizer` leaves out. */
    @Test fun aMeridiemBeforeACapitalEndsTheSentenceAsOnAndroid() {
        assertEquals(listOf("We met at 10 a.m.", "Then we left."), Sentences.split("We met at 10 a.m. Then we left."))
        // Upper case breaks ICU's way too (the voice then joins it again, as on Android: "M." reads as an initial).
        assertEquals(listOf(0 to 19, 19 to 33), splitAfterMeridiem("It ended at 9 P.M. Nobody stayed.", listOf(0 to 33)))
        assertEquals(listOf("He arrived at 3.5 p.m. on Tuesday."), Sentences.split("He arrived at 3.5 p.m. on Tuesday."))
        assertEquals(listOf(0 to 18, 18 to 31), splitAfterMeridiem("We met at 10 a.m. Then we left.", listOf(0 to 31)))
        assertEquals(listOf(0 to 14), splitAfterMeridiem("Seen at 4 p.m.", listOf(0 to 14)))
    }
}
