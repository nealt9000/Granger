package app.granger.voice

import android.icu.text.BreakIterator
import java.util.Locale

/** Splits text into sentences the way the voice will: ICU's sentence breaks, whitespace folded, fragments dropped. */
object Sentences {
    fun split(text: String, locale: Locale = Locale.US): List<String> {
        val folded = text.replace(WHITESPACE, " ").trim()
        if (folded.isEmpty()) return emptyList()
        val breaks = BreakIterator.getSentenceInstance(locale)
        breaks.setText(folded)
        val out = mutableListOf<String>()
        var start = breaks.first()
        var end = breaks.next()
        while (end != BreakIterator.DONE) {
            val sentence = folded.substring(start, end).trim()
            // A run of punctuation or whitespace isn't a sentence.
            if (sentence.any { it.isLetterOrDigit() }) out += sentence
            start = end
            end = breaks.next()
        }
        return out
    }

    private val WHITESPACE = Regex("[\\s\\u00A0]+")
}
