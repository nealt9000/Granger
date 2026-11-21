package app.granger.core.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SpokenTextTest {
    @Test
    fun yearsAreSpokenAsYears() {
        val table = mapOf(
            1000 to "one thousand", 1005 to "one thousand five", 1066 to "ten sixty-six", 1100 to "eleven hundred",
            1215 to "twelve fifteen", 1799 to "seventeen ninety-nine", 1800 to "eighteen hundred", 1805 to "eighteen oh five",
            1900 to "nineteen hundred", 1962 to "nineteen sixty-two", 2000 to "two thousand", 2005 to "two thousand five",
            2010 to "twenty ten", 2026 to "twenty twenty-six", 2099 to "twenty ninety-nine",
        )
        for ((n, words) in table) assertEquals(words, SpokenText.year(n))
        assertNull(SpokenText.year(999))
        assertNull(SpokenText.year(2100))
        assertNull(SpokenText.year(-1962))
    }

    @Test
    fun yearsInSentences() {
        assertEquals("In seventeen ninety-nine the stone was found.", SpokenText.forPiper("In 1799 the stone was found."))
        assertEquals("He died in nineteen sixty-two.", SpokenText.forPiper("He died in 1962."))
        assertEquals("Born in ten sixty-six, died eleven hundred.", SpokenText.forPiper("Born in 1066, died 1100."))
        assertEquals("(fourteen fifty-two to fifteen nineteen)", SpokenText.forPiper("(1452–1519)"))
        assertEquals("In two thousand five and in twenty ten.", SpokenText.forPiper("In 2005 and in 2010."))
        assertEquals("fifteen hundred people came.", SpokenText.forPiper("1500 people came."))
    }

    @Test
    fun decadesAndRanges() {
        assertEquals("The nineteen sixties were loud.", SpokenText.forPiper("The 1960s were loud."))
        assertEquals("the seventeen hundreds", SpokenText.forPiper("the 1700s"))
        assertEquals("the nineteen tens and the twenty tens", SpokenText.forPiper("the 1910s and the 2010s"))
        assertEquals("the two thousands", SpokenText.forPiper("the 2000s"))
        assertEquals("The war of nineteen fourteen to nineteen eighteen.", SpokenText.forPiper("The war of 1914–1918."))
        assertEquals("Pages 12 to 15.", SpokenText.forPiper("Pages 12–15."))
        assertEquals("pages 12 to 15 and 3 to 4", SpokenText.forPiper("pages 12 - 15 and 3—4"))
    }

    @Test
    fun circaAndDegrees() {
        assertEquals("circa eighteen fifty", SpokenText.forPiper("c. 1850"))
        assertEquals("built circa twelve hundred", SpokenText.forPiper("built ca. 1200"))
        assertEquals("It was 37 degrees Celsius, or 37 degrees Celsius, and 98.6 degrees Fahrenheit.", SpokenText.forPiper("It was 37 °C, or 37°C, and 98.6°F."))
        assertEquals("turn 90 degrees left", SpokenText.forPiper("turn 90° left"))
    }

    @Test
    fun unitsAfterNumbers() {
        assertEquals("5 kilometers away", SpokenText.forPiper("5 km away"))
        assertEquals("1 kilometer, 1 mile, 1 foot", SpokenText.forPiper("1 km, 1 mi, 1 ft"))
        assertEquals("3.5 kilometers and 2,000 kilograms and 6 feet", SpokenText.forPiper("3.5 km and 2,000 kg and 6 ft"))
        assertEquals("60 miles per hour; 100 kilometers per hour; 100 kilometers per hour", SpokenText.forPiper("60 mph; 100 km/h; 100 kph"))
        assertEquals("12 centimeters, 5 millimeters, 150 pounds, 150 pounds", SpokenText.forPiper("12 cm, 5 mm, 150 lb, 150 lbs"))
        assertEquals("11 kilometers and 1,000 kilometers", SpokenText.forPiper("11 km and 1,000 km"))
    }

    @Test
    fun whatMustNotChange() {
        for (text in listOf(
            "About 1,234 people.", "The 20th century.", "It cost $1500.", "No. 1066 is the plate.", "Nos. 1800",
            "ISBN 0-306-40615-2", "call 12345", "a ratio of 1.5", "at 10:30", "Room 101", "#1984", "1.1962", "1962.5",
            "the 1962nd", "99%", "1500% more", "Henry VIII", "Chapter XII", "the 1700 m peak", "the 1800 kcal diet",
            "a Boeing 747", "11 kilometers", "mph alone", "km", "the word circa", "c. d. e.",
        )) {
            assertEquals(text, SpokenText.forPiper(text))
        }
        assertEquals("in 1500 kilometers", SpokenText.forPiper("in 1500 km"))      // the unit is spoken, the number stays a count
        assertEquals("up to 2000 kilograms", SpokenText.forPiper("up to 2000 kg"))
    }

    @Test
    fun textWithoutNumbersIsReturnedAsIs() {
        val text = "Mars, the Bringer of War, is written in five-four time."
        assertSame(text, SpokenText.forPiper(text))
    }

    @Test
    fun theReadersHighlightOffsetsAreUntouched() {
        // The pass runs inside the Piper engine on one sentence at a time; utterances keep the page's own text and offsets.
        val made = Utterances.fromBlocks(listOf(Block("p1", BlockKind.PARAGRAPH, "He died in 1962. It rained."))) { id, n -> ReadAddress.Article("w", "a", id, n) }
        assertEquals("He died in 1962.", made[0].text)
        assertEquals(0, made[0].start)
        assertEquals(16, made[0].end)
        assertEquals(17, made[1].start)
    }
}
