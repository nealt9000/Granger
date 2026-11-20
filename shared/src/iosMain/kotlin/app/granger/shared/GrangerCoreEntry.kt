package app.granger.shared

import app.granger.core.catalog.Catalog
import app.granger.core.catalog.CatalogParser
import app.granger.core.catalog.CatalogSignature
import app.granger.core.catalog.Ed25519Verifier
import app.granger.core.catalog.ParseResult
import app.granger.core.online.OnlineWikipediaArchive
import app.granger.core.online.UrlSessionFetch
import app.granger.core.online.WikimediaClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.posix.memcpy

/**
 * Where the iPhone app starts with the shared core (Plan I1 Task 7): one place for Swift, so it needn't know the
 * modules. Bytes cross as `NSData`. Every call that reaches the network blocks: call it off the main thread.
 */
object GrangerCoreEntry {
    /** Granger's User-Agent, as Wikimedia's policy asks (the same as Android's `GrangerUserAgent.of`). */
    fun userAgent(version: String): String = "Granger/$version (https://opengranger.org; feedback@opengranger.org)"

    /** The Wikimedia client over `NSURLSession`, sending Granger's User-Agent. */
    fun wikimedia(version: String): WikimediaClient = WikimediaClient(UrlSessionFetch(userAgent = userAgent(version)))

    /** Wikipedia in [lang] read online, as the reader reads a pack (no stores yet: I3 adds keeping). */
    fun onlineWikipedia(lang: String, client: WikimediaClient): OnlineWikipediaArchive = OnlineWikipediaArchive(lang, client)

    /**
     * Gives the core the app's Ed25519 check (CryptoKit `Curve25519.Signing.PublicKey.isValidSignature`). Until it's set,
     * every catalog signature fails.
     */
    fun setCatalogVerifier(verify: (message: NSData, signature: NSData, publicKey: NSData) -> Boolean) {
        CatalogSignature.verifier = Ed25519Verifier { m, s, k -> verify(m.toNSData(), s.toNSData(), k.toNSData()) }
    }

    /** The catalog in [json], checked against [signature] (the `.sig` file) and [publicKey]; null when it doesn't verify or parse. */
    fun parseCatalog(json: NSData, signature: NSData, publicKey: NSData): Catalog? =
        (CatalogParser.parse(json.toByteArray(), signature.toByteArray(), publicKey.toByteArray()) as? ParseResult.Ok)?.catalog

    /** An item's bytes (a page or a picture) for the web view. */
    fun bytes(of: ByteArray): NSData = of.toNSData()
}

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
