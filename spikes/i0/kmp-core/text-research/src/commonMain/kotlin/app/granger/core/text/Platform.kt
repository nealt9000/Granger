package app.granger.core.text

/** I0 trial: the JVM-only text calls the cores used, as expect functions. */

/** Unicode decomposition: NFKD when [compat], else NFD (`java.text.Normalizer` on the JVM, NSString on iOS). */
expect fun decompose(s: String, compat: Boolean): String

/** Sentence boundaries in [paragraph] (0 first, the length last), trailing spaces kept with their sentence, as `BreakIterator`. */
expect fun sentenceBreaks(paragraph: String): List<Int>

expect fun sha1(bytes: ByteArray): ByteArray

private const val DIGITS = "0123456789abcdef"
fun ByteArray.hex(): String = buildString(2 * size) { for (b in this@hex) { val v = b.toInt() and 0xFF; append(DIGITS[v shr 4]).append(DIGITS[v and 0xF]) } }
