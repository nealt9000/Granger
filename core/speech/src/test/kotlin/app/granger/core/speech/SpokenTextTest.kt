package app.granger.core.speech

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpokenTextTest {
    @Test
    fun yearsAreSpokenAsYears() {
        val table = mapOf(
            1000 to "one thousand", 1005 to "one thousand five", 1066 to "ten sixty-six", 1100 to "eleven hundred",
            1215 to "twelve fifteen", 1799 to "seventeen ninety-nine", 1800 to "eighteen hundred", 1805 to "eighteen oh five",
            1900 to "nineteen hundred", 1962 to "nineteen sixty-two", 2000 to "two thousand", 2005 to "two thousand five",
            2010 to "twenty ten", 2026 to "twenty twenty-six", 2099 to "twenty ninety-nine",
        )
        for ((n, words) in table) assertThat(SpokenText.year(n)).isEqualTo(words)
        assertThat(SpokenText.year(999)).isNull()
        assertThat(SpokenText.year(2100)).isNull()
        assertThat(SpokenText.year(-1962)).isNull()
    }

    @Test
    fun yearsInSentences() {
        assertThat(SpokenText.forPiper("In 1799 the stone was found.")).isEqualTo("In seventeen ninety-nine the stone was found.")
        assertThat(SpokenText.forPiper("He died in 1962.")).isEqualTo("He died in nineteen sixty-two.")
        assertThat(SpokenText.forPiper("Born in 1066, died 1100.")).isEqualTo("Born in ten sixty-six, died eleven hundred.")
        assertThat(SpokenText.forPiper("(1452–1519)")).isEqualTo("(fourteen fifty-two to fifteen nineteen)")
        assertThat(SpokenText.forPiper("In 2005 and in 2010.")).isEqualTo("In two thousand five and in twenty ten.")
        assertThat(SpokenText.forPiper("1500 people came.")).isEqualTo("fifteen hundred people came.")
    }

    @Test
    fun decadesAndRanges() {
        assertThat(SpokenText.forPiper("The 1960s were loud.")).isEqualTo("The nineteen sixties were loud.")
        assertThat(SpokenText.forPiper("the 1700s")).isEqualTo("the seventeen hundreds")
        assertThat(SpokenText.forPiper("the 1910s and the 2010s")).isEqualTo("the nineteen tens and the twenty tens")
        assertThat(SpokenText.forPiper("the 2000s")).isEqualTo("the two thousands")
        assertThat(SpokenText.forPiper("The war of 1914–1918.")).isEqualTo("The war of nineteen fourteen to nineteen eighteen.")
        assertThat(SpokenText.forPiper("Pages 12–15.")).isEqualTo("Pages 12 to 15.")
        assertThat(SpokenText.forPiper("pages 12 - 15 and 3—4")).isEqualTo("pages 12 to 15 and 3 to 4")
    }

    @Test
    fun circaAndDegrees() {
        assertThat(SpokenText.forPiper("c. 1850")).isEqualTo("circa eighteen fifty")
        assertThat(SpokenText.forPiper("built ca. 1200")).isEqualTo("built circa twelve hundred")
        assertThat(SpokenText.forPiper("It was 37 °C, or 37°C, and 98.6°F.")).isEqualTo("It was 37 degrees Celsius, or 37 degrees Celsius, and 98.6 degrees Fahrenheit.")
        assertThat(SpokenText.forPiper("turn 90° left")).isEqualTo("turn 90 degrees left")
    }

    @Test
    fun unitsAfterNumbers() {
        assertThat(SpokenText.forPiper("5 km away")).isEqualTo("5 kilometers away")
        assertThat(SpokenText.forPiper("1 km, 1 mi, 1 ft")).isEqualTo("1 kilometer, 1 mile, 1 foot")
        assertThat(SpokenText.forPiper("3.5 km and 2,000 kg and 6 ft")).isEqualTo("3.5 kilometers and 2,000 kilograms and 6 feet")
        assertThat(SpokenText.forPiper("60 mph; 100 km/h; 100 kph")).isEqualTo("60 miles per hour; 100 kilometers per hour; 100 kilometers per hour")
        assertThat(SpokenText.forPiper("12 cm, 5 mm, 150 lb, 150 lbs")).isEqualTo("12 centimeters, 5 millimeters, 150 pounds, 150 pounds")
        assertThat(SpokenText.forPiper("11 km and 1,000 km")).isEqualTo("11 kilometers and 1,000 kilometers")
    }

    @Test
    fun whatMustNotChange() {
        for (text in listOf(
            "About 1,234 people.", "The 20th century.", "It cost $1500.", "No. 1066 is the plate.", "Nos. 1800",
            "ISBN 0-306-40615-2", "call 12345", "a ratio of 1.5", "at 10:30", "Room 101", "#1984", "1.1962", "1962.5",
            "the 1962nd", "99%", "1500% more", "Henry VIII", "Chapter XII", "the 1700 m peak", "the 1800 kcal diet",
            "a Boeing 747", "11 kilometers", "mph alone", "km", "the word circa", "c. d. e.",
        )) {
            assertThat(SpokenText.forPiper(text)).isEqualTo(text)
        }
        assertThat(SpokenText.forPiper("in 1500 km")).isEqualTo("in 1500 kilometers")      // the unit is spoken, the number stays a count
        assertThat(SpokenText.forPiper("up to 2000 kg")).isEqualTo("up to 2000 kilograms")
    }

    @Test
    fun textWithoutNumbersIsReturnedAsIs() {
        val text = "Mars, the Bringer of War, is written in five-four time."
        assertThat(SpokenText.forPiper(text)).isSameInstanceAs(text)
    }

    @Test
    fun theReadersHighlightOffsetsAreUntouched() {
        // The pass runs inside the Piper engine on one sentence at a time; utterances keep the page's own text and offsets.
        val made = Utterances.fromBlocks(listOf(Block("p1", BlockKind.PARAGRAPH, "He died in 1962. It rained."))) { id, n -> ReadAddress.Article("w", "a", id, n) }
        assertThat(made[0].text).isEqualTo("He died in 1962.")
        assertThat(made[0].start).isEqualTo(0)
        assertThat(made[0].end).isEqualTo(16)
        assertThat(made[1].start).isEqualTo(17)
    }
}
