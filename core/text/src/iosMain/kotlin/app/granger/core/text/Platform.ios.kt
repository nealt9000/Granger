package app.granger.core.text

import kotlinx.cinterop.*
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.*

@Suppress("CAST_NEVER_SUCCEEDS")
private fun String.ns() = this as NSString

actual fun decompose(s: String, compat: Boolean): String =
    if (compat) s.ns().decomposedStringWithCompatibilityMapping else s.ns().decomposedStringWithCanonicalMapping

actual fun compose(s: String): String = s.ns().precomposedStringWithCanonicalMapping

@OptIn(ExperimentalForeignApi::class)
actual fun sha1(bytes: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA1_DIGEST_LENGTH)
    bytes.usePinned { b -> digest.usePinned { d -> CC_SHA1(if (bytes.isEmpty()) null else b.addressOf(0), bytes.size.convert(), d.addressOf(0)) } }
    return digest.asByteArray()
}

@OptIn(ExperimentalForeignApi::class)
actual fun sha256(bytes: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    bytes.usePinned { b -> digest.usePinned { d -> CC_SHA256(if (bytes.isEmpty()) null else b.addressOf(0), bytes.size.convert(), d.addressOf(0)) } }
    return digest.asByteArray()
}

private val ALPHA2: Set<String> by lazy { NSLocale.ISOLanguageCodes.map { it as String }.filter { it.length == 2 }.toSet() }

actual fun iso639Alpha2(code: String): String? = when (code.length) {
    2 -> code.takeIf { it in ALPHA2 }
    // ICU canonicalises ISO 639-2/3 codes to the 639-1 code where one exists ("spa" → "es").
    3 -> NSLocale.canonicalLanguageIdentifierFromString(code).substringBefore('-').substringBefore('_').takeIf { it.length == 2 && it in ALPHA2 }
    else -> null
}
