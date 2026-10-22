package app.granger.core.speech

import android.icu.text.BreakIterator
import java.util.Locale

/** One sentence of a block and where it sits in the block's folded text, so a reader can mark exactly that range. */
data class Sentence(val text: String, val start: Int, val end: Int)

/** Splits text into sentences the way the voice speaks them: ICU's sentence breaks, whitespace folded, fragments dropped. */
object Sentences {
    fun split(text: String, locale: Locale = Locale.US): List<String> = sentences(text, locale).map { it.text }

    /** The sentences with their offsets into [fold] of [text]. */
    fun sentences(text: String, locale: Locale = Locale.US): List<Sentence> {
        val folded = fold(text)
        if (folded.isEmpty()) return emptyList()
        val breaks = BreakIterator.getSentenceInstance(locale)
        breaks.setText(folded)
        val out = mutableListOf<Sentence>()
        var start = breaks.first()
        var end = breaks.next()
        while (end != BreakIterator.DONE) {
            var s = start
            var e = end
            while (s < e && folded[s].isWhitespace()) s++
            while (e > s && folded[e - 1].isWhitespace()) e--
            val sentence = folded.substring(s, e)
            // A run of punctuation or whitespace isn't a sentence.
            if (sentence.any { it.isLetterOrDigit() }) out += Sentence(sentence, s, e)
            start = end
            end = breaks.next()
        }
        return mergeAbbreviations(out, folded)
    }

    /**
     * ICU on Android breaks after "Dr." and after initials ("J. R. R. Tolkien"); spoken, that is a pause in the
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
        "(?:\\b(?:Dr|Mr|Mrs|Ms|Mme|Mlle|Prof|Rev|Hon|Sr|Jr|St|Mt|Ft|No|Nos|Fig|Figs|vs|cf|ca|approx|Lt|Col|Gen|Capt|Sgt|Op)|\\b[A-Z]|\\b(?:e\\.g|i\\.e))\\.$",
    )

    /** Whitespace runs become one space; the result is what the offsets in [sentences] index. */
    fun fold(text: String): String = text.replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("[\\s\\u00A0]+")
}
