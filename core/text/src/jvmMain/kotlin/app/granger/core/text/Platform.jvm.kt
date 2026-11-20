package app.granger.core.text

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

actual fun decompose(s: String, compat: Boolean): String = Normalizer.normalize(s, if (compat) Normalizer.Form.NFKD else Normalizer.Form.NFD)

actual fun compose(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

actual fun sha1(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(bytes)

actual fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

private val ALPHA2: Set<String> by lazy { Locale.getISOLanguages().toSet() }

/** ISO 639-2/3 (terminology) → ISO 639-1, from the JVM's own table. */
private val FROM_ALPHA3: Map<String, String> by lazy {
    @Suppress("DEPRECATION")
    Locale.getISOLanguages().associateBy { Locale(it).isO3Language }
}

actual fun iso639Alpha2(code: String): String? = when (code.length) {
    2 -> code.takeIf { it in ALPHA2 }
    3 -> FROM_ALPHA3[code]
    else -> null
}
