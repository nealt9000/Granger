package app.granger.core.text

import java.security.MessageDigest
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

actual fun decompose(s: String, compat: Boolean): String = Normalizer.normalize(s, if (compat) Normalizer.Form.NFKD else Normalizer.Form.NFD)

actual fun sentenceBreaks(paragraph: String): List<Int> {
    val it = BreakIterator.getSentenceInstance(Locale.ENGLISH)
    it.setText(paragraph)
    val out = mutableListOf(it.first())
    while (true) { val b = it.next(); if (b == BreakIterator.DONE) break; out += b }
    return out
}

actual fun sha1(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(bytes)
