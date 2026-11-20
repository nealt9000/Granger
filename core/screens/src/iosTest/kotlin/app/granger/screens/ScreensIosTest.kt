package app.granger.screens

import app.granger.home.PlateRule
import app.granger.topic.TopicTitles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shared screen models' platform pieces and regexes, run on iOS (Plan I3 Task 4). */
class ScreensIosTest {
    @Test fun platform() {
        assertTrue(currentYear() >= 2026)
        assertTrue(todayEpochDay() > 20_000)
        assertTrue(phoneLanguage().length in 2..3)
        assertTrue(nowMillis() > 1_700_000_000_000)
    }

    @Test fun titlesSortAsAndroidDoes() {
        val c = titleComparator("es")
        assertEquals(listOf("Ávila", "avión", "Bilbao", "Zaragoza"), listOf("Zaragoza", "Ávila", "Bilbao", "avión").sortedWith(c))
    }

    /** The rule's look-behind regexes and Ksoup run natively: a series sidebar's symbol gives way to the first photo. */
    @Test fun pictureRule() {
        val html = """
            <table class="sidebar"><tr><td><img src="../I/Dharma_wheel.svg.png" width="100" height="100" data-file-type="drawing"></td></tr></table>
            <p>Lead.</p><figure><img src="../I/Monk.jpg" width="220" height="160"></figure>
        """.trimIndent()
        val ranked = PlateRule.ranked(html, "A/Buddhism")
        assertEquals("I/Monk.jpg", ranked.first().first)
        assertEquals(false, ranked.first().second)
        assertTrue(PlateRule.ranked(html, "A/Flag_of_Tibet").first().first.contains("Dharma_wheel"))
        assertEquals("Café au lait", TopicTitles.titleOf("A/Café_au_lait"))
    }
}
