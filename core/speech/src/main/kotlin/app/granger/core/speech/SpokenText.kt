package app.granger.core.speech

/**
 * Text as a Piper voice should hear it. espeak-ng, Piper's phonemizer, reads "1799" as "one thousand seven hundred
 * ninety nine", "1960s" as "nineteen hundred sixty z", drops the dash in "1914–1918" so the years run together,
 * reads "c. 1850" as "see …" and "km" as "K M" (checked with `espeak-ng -v en-us -q -x`, 2026-10-22). This pass
 * writes those out in words, in this order: ranges, circa, decades, years, degrees, units after a number.
 *
 * A bare four-digit number from 1000 to 2099 is a year unless a unit, a per-cent sign or an ordinal suffix follows
 * it, or a currency sign, "#" or "No." precedes it; "1500 people" becomes "fifteen hundred people", which is how it is
 * said anyway. Digit runs of other lengths, decimals, thousands with commas and times are left alone.
 *
 * It runs on one sentence at a time inside the engine, after splitting, so the reader's highlight offsets (the
 * utterance's own text) are never touched. The system engine gets the original text: it normalises for itself.
 */
object SpokenText {
    fun forPiper(sentence: String): String {
        if (sentence.none { it.isDigit() }) return sentence
        var s = sentence
        s = RANGE.replace(s) { "${it.groupValues[1]} to ${it.groupValues[2]}" }
        s = CIRCA.replace(s, "circa ")
        s = DECADE.replace(s) { m -> decade(m.groupValues[1].toInt()) ?: m.value }
        s = YEAR.replace(s) { m -> year(m.value.toInt()) ?: m.value }
        s = DEGREES_C.replace(s) { "${it.groupValues[1]} degrees Celsius" }
        s = DEGREES_F.replace(s) { "${it.groupValues[1]} degrees Fahrenheit" }
        s = DEGREES.replace(s) { "${it.groupValues[1]} degrees" }
        s = UNIT.replace(s) { m -> "${m.groupValues[1]} ${unit(m.groupValues[2], one = m.groupValues[1] == "1")}" }
        return s
    }

    /** "1799" → "seventeen ninety-nine", "1805" → "eighteen oh five", "2005" → "two thousand five"; null outside 1000–2099. */
    fun year(n: Int): String? {
        if (n !in 1000..2099) return null
        val h = n / 100
        val r = n % 100
        return when {
            h == 10 || h == 20 -> {
                val thousand = if (h == 10) "one thousand" else "two thousand"
                when {
                    r == 0 -> thousand
                    r < 10 -> "$thousand ${ONES[r]}"
                    else -> "${TENS_WORD[h / 10]} ${twoDigits(r)}"
                }
            }
            r == 0 -> "${twoDigits(h)} hundred"
            r < 10 -> "${twoDigits(h)} oh ${ONES[r]}"
            else -> "${twoDigits(h)} ${twoDigits(r)}"
        }
    }

    /** "1960" → "nineteen sixties", "1700" → "seventeen hundreds", "2010" → "twenty tens"; null unless a decade year. */
    private fun decade(n: Int): String? {
        if (n !in 1000..2099 || n % 10 != 0) return null
        val h = n / 100
        val r = n % 100
        return when {
            r == 0 -> if (h == 10) "one thousands" else if (h == 20) "two thousands" else "${twoDigits(h)} hundreds"
            else -> "${twoDigits(if (h == 20) 20 else h)} ${TENS_PLURAL[r / 10]}"
        }
    }

    private fun twoDigits(n: Int): String = when {
        n < 20 -> ONES[n]
        n % 10 == 0 -> TENS[n / 10]
        else -> "${TENS[n / 10]}-${ONES[n % 10]}"
    }

    private fun unit(abbrev: String, one: Boolean): String = when (abbrev) {
        "km" -> if (one) "kilometer" else "kilometers"
        "kg" -> if (one) "kilogram" else "kilograms"
        "cm" -> if (one) "centimeter" else "centimeters"
        "mm" -> if (one) "millimeter" else "millimeters"
        "mi" -> if (one) "mile" else "miles"
        "ft" -> if (one) "foot" else "feet"
        "lb", "lbs" -> if (one) "pound" else "pounds"
        "mph" -> "miles per hour"
        "km/h", "kph" -> "kilometers per hour"
        else -> abbrev
    }

    private val ONES = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val TENS = listOf("", "ten", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val TENS_PLURAL = listOf("", "tens", "twenties", "thirties", "forties", "fifties", "sixties", "seventies", "eighties", "nineties")
    private val TENS_WORD = TENS   // "ten sixty-six", "twenty ten"

    private const val UNITS = "km/h|kph|mph|km|kg|cm|mm|mi|ft|lbs|lb|m|g|s|h|min|kcal|cal|mg|ml|l|oz|in|yd"

    /** Two numbers joined by an en or em dash, or a spaced hyphen: "1914–1918", "12 - 15". A bare hyphen is an ISBN's or a phone number's. */
    private val RANGE = Regex("(\\d[\\d,.]*)\\s*(?:[–—]|\\s-\\s)\\s*(\\d)")

    private val CIRCA = Regex("\\b(?:c|ca)\\.\\s*(?=\\d{3,4}\\b)")

    private val DECADE = Regex("(?<![\\d,.$£€#-])\\b((?:1\\d|20)\\d0)s\\b")

    /**
     * 1000–2099 as a whole token: no digit, comma-digit or point-digit on either side; no currency sign, "#" or "No."
     * before; no per-cent sign, ordinal suffix or unit after.
     */
    private val YEAR = Regex(
        "(?<!(?:No|Nos)\\.\\s{0,3})(?<![\\d$£€#])(?<![\\d][,.])\\b(1\\d{3}|20\\d{2})\\b(?![,.]\\d)(?!\\s*%)(?!\\s*(?:th|st|nd|rd|$UNITS)\\b)",
    )

    private val DEGREES_C = Regex("(\\d)\\s*°\\s*C\\b")
    private val DEGREES_F = Regex("(\\d)\\s*°\\s*F\\b")
    private val DEGREES = Regex("(\\d)\\s*°(?![CF])")

    /** A number and a unit this pass spells out; the rest of [UNITS] only keeps a year from being read as one. */
    private val UNIT = Regex("(\\d+(?:[.,]\\d+)*)\\s*(km/h|kph|mph|km|kg|cm|mm|mi|ft|lbs|lb)\\b")
}
