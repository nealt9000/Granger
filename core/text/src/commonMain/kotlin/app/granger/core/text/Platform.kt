package app.granger.core.text

/** The JVM-only text calls the cores used, as expect functions (Plan I1; I0 trial §4). */

/** Unicode decomposition: NFKD when [compat], else NFD (`java.text.Normalizer` on the JVM, NSString on iOS). */
expect fun decompose(s: String, compat: Boolean): String

/** Unicode canonical composition (NFC). */
expect fun compose(s: String): String

expect fun sha1(bytes: ByteArray): ByteArray

expect fun sha256(bytes: ByteArray): ByteArray

/** The two-letter ISO 639-1 code for a two- or three-letter ISO 639 code, or null when the platform knows none. */
expect fun iso639Alpha2(code: String): String?

private const val DIGITS = "0123456789abcdef"

fun ByteArray.hex(): String = buildString(2 * size) {
    for (b in this@hex) { val v = b.toInt() and 0xFF; append(DIGITS[v shr 4]).append(DIGITS[v and 0xF]) }
}
