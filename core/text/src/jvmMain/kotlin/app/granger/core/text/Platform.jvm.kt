package app.granger.core.text

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

actual fun decompose(s: String, compat: Boolean): String = Normalizer.normalize(s, if (compat) Normalizer.Form.NFKD else Normalizer.Form.NFD)

actual fun compose(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

actual fun sha1(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(bytes)

actual fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

actual fun iso639Alpha2(code: String): String? = when (code.length) {
    2 -> code.takeIf { it in Locale.getISOLanguages() }
    3 -> Locale.getISOLanguages().firstOrNull { Locale(it).isO3Language == code }
    else -> null
}
