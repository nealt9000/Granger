package app.granger.core.text

import kotlinx.cinterop.*
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH
import platform.Foundation.*

@Suppress("CAST_NEVER_SUCCEEDS")
private fun String.ns() = this as NSString

actual fun decompose(s: String, compat: Boolean): String =
    if (compat) s.ns().decomposedStringWithCompatibilityMapping else s.ns().decomposedStringWithCanonicalMapping

@OptIn(ExperimentalForeignApi::class)
actual fun sentenceBreaks(paragraph: String): List<Int> {
    val out = mutableListOf(0)
    val ns = paragraph.ns()
    ns.enumerateSubstringsInRange(NSMakeRange(0u, ns.length), NSStringEnumerationBySentences) { _, _, enclosing, _ ->
        enclosing.useContents { out += (location + length).toInt() }
    }
    if (out.last() != paragraph.length) out += paragraph.length
    return out
}

@OptIn(ExperimentalForeignApi::class)
actual fun sha1(bytes: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA1_DIGEST_LENGTH)
    bytes.usePinned { b -> digest.usePinned { d -> CC_SHA1(if (bytes.isEmpty()) null else b.addressOf(0), bytes.size.convert(), d.addressOf(0)) } }
    return digest.asByteArray()
}
