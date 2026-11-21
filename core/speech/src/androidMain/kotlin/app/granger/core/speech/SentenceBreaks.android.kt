package app.granger.core.speech

import android.icu.text.BreakIterator
import java.util.Locale

internal actual fun icuSentenceBreaks(text: String, languageTag: String): List<Pair<Int, Int>>? {
    val icu = runCatching { BreakIterator.getSentenceInstance(Locale.forLanguageTag(languageTag)) }.getOrNull() ?: return null
    icu.setText(text)
    val out = mutableListOf<Pair<Int, Int>>()
    var start = icu.first()
    var end = icu.next()
    while (end != BreakIterator.DONE) { out += start to end; start = end; end = icu.next() }
    return out
}
