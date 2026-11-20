package app.granger.core.catalog

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.getenv
import platform.posix.memcpy
import kotlinx.cinterop.toKString
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The published catalog parses on iOS as on the JVM, and its signature check fails closed until the app sets one. */
class PublishedCatalogIosTest {
    @OptIn(ExperimentalForeignApi::class)
    private val repo: String = getenv("GRANGER_REPO")?.toKString() ?: error("GRANGER_REPO not set (see build.gradle.kts)")

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun read(path: String): ByteArray {
        val data = NSData.dataWithContentsOfFile("$repo/$path") ?: error("can't read $path")
        val out = ByteArray(data.length.toInt())
        if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return out
    }

    @OptIn(ExperimentalEncodingApi::class)
    private val key get() = Base64.decode(read("catalog/public-key.txt").decodeToString().trim())

    private val original = CatalogSignature.verifier

    @AfterTest fun restore() { CatalogSignature.verifier = original }

    @Test fun failsClosedWithoutAVerifier() {
        val result = CatalogParser.parse(read("catalog/catalog.json"), read("catalog/catalog.json.sig"), key)
        assertEquals(ParseResult.Invalid(CatalogError.BAD_SIGNATURE), result)
    }

    @Test fun parsesThePublishedCatalog() {
        // The app's CryptoKit verifier stands in here as one that accepts: the parse itself is under test.
        CatalogSignature.verifier = Ed25519Verifier { _, sig, k -> sig.size == 64 && k.size == 32 }
        val result = CatalogParser.parse(read("catalog/catalog.json"), read("catalog/catalog.json.sig"), key)
        assertTrue(result is ParseResult.Ok, "$result")
        val catalog = result.catalog
        assertTrue(catalog.packs.size > 500, "packs: ${catalog.packs.size}")
        assertTrue(catalog.packs.any { Languages.has(it.language, "es") })
    }
}
