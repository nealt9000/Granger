package app.granger.core.speech

/**
 * Words and marks as a Piper voice should hear them; [SpokenText]'s numbers come after. Checked with
 * `espeak-ng -v en-gb-x-rp -q -x` and sherpa-onnx 1.13.8's debug log (2026-10-31):
 *
 * - A full stop before a capital ends espeak's sentence, so "Mr. Smith", "J. R. R. Tolkien" and "U.S. Army" drop in
 *   pitch and pause in the middle. The app hands the voice one sentence at a time ([Sentences] joins titles and
 *   initials), so a stop inside the sentence is never its end: titles are written out, initials lose their stops.
 * - espeak spells "Ms.", "Lt.", "Sgt.", "Mt." and "Jr." as letters, and reads "i.e." as "I E".
 * - A quote mark or bracket after the sentence's last stop becomes a sentence of its own, which the voice renders as a
 *   short stutter after the line. espeak drops quote marks anyway, so they go; single quotes go unless they sit inside
 *   a word (an apostrophe).
 * - "Chapter IV" is "chapter roman four", "Chapter I" and "Chapter XL" are spelled as letters, "Henry VIII" is "Henry
 *   roman eight". After a word like Chapter or Part a numeral becomes digits ("Chapter 4"); after a name it becomes
 *   an ordinal ("Henry the Eighth"); alone at the start of a heading ("IV. The Return") it becomes digits.
 */
object SpokenWords {
    fun forPiper(sentence: String): String {
        var s = marks(sentence)
        s = romanNumerals(s)
        s = abbreviations(s)
        s = initials(s)
        return if (s == sentence) sentence else s.replace(SPACES, " ").trim()
    }

    private fun marks(s: String): String {
        if (s.none { it in MARKS }) return s
        var t = DOUBLE_QUOTES.replace(s, "")
        t = SINGLE_QUOTE.replace(t, "")
        t = CLOSING_AFTER_STOP.replace(t, "$1")
        t = EMPHASIS.replace(t, "")
        return t
    }

    private fun romanNumerals(s: String): String {
        var t = AFTER_WORD.replace(s) { m ->
            val (word, numeral) = m.destructured
            val n = roman(numeral) ?: return@replace m.value
            if (numeral.length == 1 && numeral[0] !in "IVXivx") return@replace m.value
            if (numeral.equals("I", ignoreCase = true) && (!word[0].isUpperCase() || PRONOUN_FOLLOWS.containsMatchIn(s.substring(m.range.last + 1)))) {
                return@replace m.value
            }
            "$word $n"
        }
        t = REGNAL.replace(t) { m ->
            val (name, numeral) = m.destructured
            val n = roman(numeral) ?: return@replace m.value
            if (n !in 1..ORDINALS.lastIndex || name.lowercase() in NOT_NAMES) return@replace m.value
            if (n == 1 && (name !in MONARCHS || PRONOUN_FOLLOWS.containsMatchIn(t.substring(m.range.last + 1)))) return@replace m.value
            "$name the ${ORDINALS[n]}"
        }
        t = LEADING.replace(t) { m ->
            val numeral = m.groupValues[1]
            val n = roman(numeral) ?: return@replace m.value
            if (numeral.length == 1 && m.groupValues[2] != "." && t.length > m.range.last + 1) return@replace m.value
            "$n${m.groupValues[2]}"
        }
        return t
    }

    private fun abbreviations(s: String): String {
        var t = TITLE.replace(s) { m -> TITLES.getValue(m.groupValues[1]) }
        t = STREET.replace(t) { "${it.groupValues[1]}Street" }
        t = AFTER_NAME.replace(t) { "${it.groupValues[1]}${if (it.groupValues[2] == "Jr") "Junior" else "Senior"}" }
        t = BEFORE_NUMBER.replace(t) { m -> BEFORE_NUMBERS.getValue(m.groupValues[1]) + " " }
        for ((pattern, words) in ANYWHERE) t = pattern.replace(t, words)
        return t
    }

    /** "J. R. R. Tolkien" → "J.R.R Tolkien", "U.S. Army" → "U.S Army": letters still spelled, no stop before the name. */
    private fun initials(s: String): String = INITIALS.replace(s) { m ->
        val letters = m.groupValues[1].filter { it.isUpperCase() }
        when {
            letters.length > 1 -> letters.toList().joinToString(".") + " "
            letters == "A" -> "Ay "   // a lone "A" is the article to espeak
            else -> "$letters "
        }
    }

    /** A Roman numeral's value, or null when [numeral] isn't one written the usual way (all upper or all lower case). */
    fun roman(numeral: String): Int? {
        if (numeral.isEmpty() || !(numeral.all { it.isUpperCase() } || numeral.all { it.isLowerCase() })) return null
        val upper = numeral.uppercase()
        if (!VALID_ROMAN.matches(upper)) return null
        var total = 0
        for (i in upper.indices) {
            val v = ROMAN.getValue(upper[i])
            total += if (i + 1 < upper.length && ROMAN.getValue(upper[i + 1]) > v) -v else v
        }
        return total
    }

    private val ROMAN = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
    private val VALID_ROMAN = Regex("M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})")

    private const val MARKS = "\"“”„«»'‘’)]*_"
    private val DOUBLE_QUOTES = Regex("[\"“”„«»]")
    private val SINGLE_QUOTE = Regex("(?<![\\p{L}\\p{N}])['‘’]|['‘’](?![\\p{L}\\p{N}])")
    private val CLOSING_AFTER_STOP = Regex("([.!?…])[)\\]]+")
    private val EMPHASIS = Regex("\\*+|(?<![\\p{L}\\p{N}])_+|_+(?![\\p{L}\\p{N}])")
    private val SPACES = Regex(" {2,}")

    /** Words a numeral counts after: "Chapter IV" is chapter four, never the fourth. */
    private const val COUNTED =
        "Chapter|Chap\\.|Ch\\.|Book|Bk\\.|Part|Pt\\.|Volume|Vol\\.|Act|Scene|Canto|Section|Letter|Psalm|Article|Stave|" +
            "Appendix|Lecture|Lesson|Sonnet|Epistle|Elegy|Ode|Song|Fytte|Phase|Stage|Type|Class|Mark|Title|Grade|Level|" +
            "Division|Plate|Table|Figure|Fig\\.|Number|No\\.|War|Vatican|Apollo|Gemini|Saturn|Voyager|Pioneer"
    private val AFTER_WORD = Regex("\\b((?i:$COUNTED))\\s+([IVXLCDM]+|[ivxl]+)\\b(?![\\p{L}'’])")
    private val NOT_NAMES = COUNTED.split('|').map { it.removeSuffix("\\.").lowercase() }.toSet()

    /** "Henry VIII", "Louis XIV's": a capitalised name and a numeral of I, V and X only. */
    private val REGNAL = Regex("\\b([A-Z][a-z]+)\\s+([IVX]+)\\b(?!\\p{L})(?!['’](?!s\\b))")

    /** "I" after a name is a numeral only for a monarch's name, and not when a verb follows ("Then George I said"). */
    private val MONARCHS = setOf(
        "Alexander", "Catherine", "Charles", "Constantine", "David", "Edward", "Elizabeth", "Ferdinand", "Francis",
        "Frederick", "George", "Gregory", "Henry", "Isabella", "Ivan", "James", "John", "Leo", "Leopold", "Louis",
        "Malcolm", "Mary", "Napoleon", "Nicholas", "Otto", "Paul", "Peter", "Philip", "Pius", "Richard", "Robert",
        "Rudolf", "Wilhelm", "William",
    )
    private val PRONOUN_FOLLOWS = Regex(
        "^\\s+(?:am|was|have|had|do|did|don't|think|thought|know|knew|said|say|can|could|will|would|shall|should|must|" +
            "may|might|read|wrote|see|saw|mean|feel|felt|found|want|wanted|like|love|hope|believe|remember|suppose|got|" +
            "went|came|made|took|told|also|never|always|just|really)\\b",
    )

    /**
     * A heading or list item that starts with its numeral: "IV. The Return", "XII", "ii) the second". A lone letter is
     * a numeral only if it is I, V or X with a stop and no initial after it ("C. S. Lewis", "I. M. Pei"), or the whole text.
     */
    private val LEADING = Regex("^([IVXLC]{2,}|[IVX]|[ivx]{2,})([.:)]?)(?=\\s|$)(?!\\s+[A-Z]\\.)")

    private val ORDINALS = listOf(
        "", "First", "Second", "Third", "Fourth", "Fifth", "Sixth", "Seventh", "Eighth", "Ninth", "Tenth",
        "Eleventh", "Twelfth", "Thirteenth", "Fourteenth", "Fifteenth", "Sixteenth", "Seventeenth", "Eighteenth",
        "Nineteenth", "Twentieth", "Twenty-first", "Twenty-second", "Twenty-third", "Twenty-fourth", "Twenty-fifth",
        "Twenty-sixth", "Twenty-seventh", "Twenty-eighth", "Twenty-ninth", "Thirtieth",
    )

    /** Titles before a capitalised name: with their stop before any capital, without it (British style) before a name. */
    private val TITLES = mapOf(
        "Mr" to "Mister", "Mrs" to "Missus", "Ms" to "Miz", "Messrs" to "Messieurs", "Mme" to "Madame",
        "Mlle" to "Mademoiselle", "Dr" to "Doctor", "Prof" to "Professor", "Rev" to "Reverend", "Revd" to "Reverend",
        "Hon" to "Honourable", "Capt" to "Captain", "Lt" to "Lieutenant", "Col" to "Colonel", "Gen" to "General",
        "Maj" to "Major", "Sgt" to "Sergeant", "Cpl" to "Corporal", "Adm" to "Admiral", "Cmdr" to "Commander",
        "Gov" to "Governor", "Sen" to "Senator", "Rep" to "Representative", "Pres" to "President",
        "Supt" to "Superintendent", "Insp" to "Inspector", "Fr" to "Father", "St" to "Saint", "Mt" to "Mount",
        "Ft" to "Fort",
    )
    private val TITLE = Regex("\\b(${TITLES.keys.joinToString("|")})(?:\\.(?=\\s+[A-Z])|(?=\\s+[A-Z][a-z]))")

    /** "Baker St." → "Baker Street": "St." after a name and not before one. */
    private val STREET = Regex("\\b([A-Z][a-z]+\\s+)St\\.(?!\\s+[A-Z])")

    private val AFTER_NAME = Regex("\\b([A-Z][a-z]+,?\\s+)(Jr|Sr)\\b\\.?")

    private val BEFORE_NUMBERS = mapOf(
        "No" to "number", "no" to "number", "Nos" to "numbers", "nos" to "numbers", "p" to "page", "pp" to "pages",
        "Fig" to "Figure", "Figs" to "Figures", "fig" to "figure", "Vol" to "Volume", "Vols" to "Volumes", "vol" to "volume",
        "Ch" to "Chapter", "ch" to "chapter", "Chap" to "Chapter", "Op" to "Opus", "op" to "opus",
    )
    private val BEFORE_NUMBER = Regex("\\b(${BEFORE_NUMBERS.keys.joinToString("|")})\\.\\s*(?=\\d)")

    private val ANYWHERE = listOf(
        Regex("\\be\\.\\s?g\\.") to "for example",
        Regex("\\bi\\.\\s?e\\.") to "that is",
        Regex("\\bvs?\\.(?=\\s)") to "versus",
        Regex("\\bcf\\.(?=\\s)") to "compare",
        Regex("\\bviz\\.(?=\\s)") to "namely",
        Regex("\\bapprox\\.(?=\\s)") to "approximately",
        Regex("\\bet al\\.") to "and others",
    )

    /** One to five capital letters, each with its stop, before a capitalised word or a number. */
    private val INITIALS = Regex("(?<![\\p{L}.])((?:[A-Z]\\.\\s?){0,4}[A-Z]\\.)\\s+(?=[A-Z0-9])")
}
