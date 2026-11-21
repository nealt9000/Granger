package app.granger.core.speech

/** One sentence of a block and where it sits in the block's folded text, so a reader can mark exactly that range. */
data class Sentence(val text: String, val start: Int, val end: Int)

/**
 * Splits text into sentences the way the voice speaks them: ICU's sentence breaks, whitespace folded, fragments dropped.
 * ICU on both phones (Plan I5, call 4): Android's `android.icu.text.BreakIterator`, the iPhone's `CFStringTokenizer`
 * (ICU underneath), through [icuSentenceBreaks]. [languageTag] is a BCP 47 tag; `en-US` is what Android always used.
 */
object Sentences {
    fun split(text: String, languageTag: String = "en-US"): List<String> = sentences(text, languageTag).map { it.text }

    /** The sentences with their offsets into [fold] of [text]. */
    fun sentences(text: String, languageTag: String = "en-US"): List<Sentence> {
        val folded = fold(text)
        if (folded.isEmpty()) return emptyList()
        val out = mutableListOf<Sentence>()
        for ((start, end) in breaks(folded, languageTag)) {
            var s = start
            var e = end
            while (s < e && folded[s].isWhitespace()) s++
            while (e > s && folded[e - 1].isWhitespace()) e--
            val sentence = folded.substring(s, e)
            // A run of punctuation or whitespace isn't a sentence.
            if (sentence.any { it.isLetterOrDigit() }) out += Sentence(sentence, s, e)
        }
        return mergeAbbreviations(out, folded)
    }

    /** ICU's sentence breaks; off the device (plain JVM tests, where the framework is a stub) a punctuation split. */
    internal fun breaks(folded: String, languageTag: String): List<Pair<Int, Int>> {
        icuSentenceBreaks(folded, languageTag)?.let { return it }
        val out = mutableListOf<Pair<Int, Int>>()
        var start = 0
        for (m in FALLBACK_BREAK.findAll(folded)) { out += start to m.range.last + 1; start = m.range.last + 1 }
        if (start < folded.length) out += start to folded.length
        return out
    }

    private val FALLBACK_BREAK = Regex("[.!?]+[\"'”’)\\]]*\\s+(?=\\S)")

    /**
     * ICU (on both phones) breaks after "Dr." and after initials ("J. R. R. Tolkien"); spoken, that is a pause in the
     * middle of a name. A sentence that ends in a title, a common abbreviation or a lone initial is joined to the next.
     */
    private fun mergeAbbreviations(sentences: List<Sentence>, folded: String): List<Sentence> {
        if (sentences.size < 2) return sentences
        val out = ArrayList<Sentence>(sentences.size)
        var current = sentences[0]
        for (next in sentences.drop(1)) {
            current = if (ABBREVIATION_END.containsMatchIn(current.text)) {
                Sentence(folded.substring(current.start, next.end), current.start, next.end)
            } else {
                out += current
                next
            }
        }
        out += current
        return out
    }

    private val ABBREVIATION_END = Regex(
        "(?:\\b(?:Dr|Mr|Mrs|Ms|Messrs|Mme|Mlle|Prof|Rev|Revd|Hon|Sr|Jr|St|Mt|Ft|No|Nos|Fig|Figs|Vol|Ch|Chap|vs|v|cf|viz|ca|approx|al|" +
            "Lt|Col|Gen|Maj|Capt|Sgt|Cpl|Adm|Cmdr|Gov|Sen|Rep|Supt|Insp|Fr|Op|p|pp)|\\b[A-Z]|\\b(?:e\\.g|i\\.e))\\.$",
    )

    /** Whitespace runs become one space; the result is what the offsets in [sentences] index. */
    fun fold(text: String): String = text.replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("[\\s\\u00A0]+")
}

/**
 * ICU's sentence breaks in [text] for [languageTag], as (start, end) pairs covering it in order; null when ICU isn't
 * there (a plain JVM test, where Android's framework is a stub). Android: `android.icu.text.BreakIterator`; the
 * iPhone: `CFStringTokenizer` with `kCFStringTokenizerUnitSentence`. Offsets are UTF-16 indices on both.
 */
internal expect fun icuSentenceBreaks(text: String, languageTag: String): List<Pair<Int, Int>>?
