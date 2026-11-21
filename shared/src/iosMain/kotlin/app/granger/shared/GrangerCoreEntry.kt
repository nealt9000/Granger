package app.granger.shared

import app.granger.core.catalog.Catalog
import app.granger.core.catalog.CatalogParser
import app.granger.core.catalog.CatalogSignature
import app.granger.core.catalog.Ed25519Verifier
import app.granger.core.catalog.ParseResult
import app.granger.core.online.OnlineWikipediaArchive
import app.granger.core.online.UrlSessionFetch
import app.granger.core.online.ReaderPage
import app.granger.core.online.WikimediaClient
import app.granger.core.research.ArticleText
import app.granger.core.zim.ZimUrl
import app.granger.core.zim.ZimUrls
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

    /**
     * The client with the app's gate (Plan O5/O3's rule): nothing is asked of Wikimedia while [allowed] says no (before
     * the privacy line is seen, or in Local only).
     */
    fun wikimedia(version: String, allowed: () -> Boolean): WikimediaClient =
        WikimediaClient(UrlSessionFetch(userAgent = userAgent(version)), allowed = allowed)

    /** Where reader URLs start on this phone (`granger-zim://zim/`): set once at start, before any page. */
    fun setZimBase(base: String) {
        ZimUrls.base = base
    }

    /** The reader URL of [path] in [packId] (`granger-zim://zim/<pack>/<path>`), and back. */
    fun zimUrl(packId: String, path: String, fragment: String?): String = ZimUrls.forEntry(packId, path, fragment)
    fun parseZimUrl(url: String): ZimUrl? = ZimUrls.parse(url)

    /** Wikipedia online's stylesheet and the reader's tap/link script, the same as Android's (Plan I2 Task 2). */
    val onlineCss: String get() = ReaderPage.ONLINE_CSS
    val tapScript: String get() = ReaderPage.TAP_SCRIPT
    val appPack: String get() = ReaderPage.APP_PACK
    val cssName: String get() = ReaderPage.CSS_NAME

    /** The online source's pack id for [lang] (`wikipedia_en_online`) and back. */
    fun onlinePackId(lang: String): String = OnlineWikipediaArchive.packName(lang)
    fun languageOfPack(packId: String): String? = OnlineWikipediaArchive.languageOf(packId)

    /** The article's sections for Contents: its h2 and h3 headings with their anchors, in order. */
    fun headings(html: String): List<Heading> =
        ArticleText.blocks(html).filter { it.tag == "h2" || it.tag == "h3" }.map { Heading(it.id, it.text, if (it.tag == "h2") 2 else 3) }

    /** A key back to the words its title shows ("Albert_Einstein" → "Albert Einstein"). */
    fun titleOfKey(key: String): String = WikimediaClient.titleOfKey(key)

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

    /** [data] as Kotlin bytes, copied in one go (Plan I4: CoreKiwix's reads, for shared code). */
    fun byteArray(of: NSData): ByteArray = of.toByteArray()

    /** A pack entry read by CoreKiwix, for the shared [app.granger.core.zim.ZimArchive] (Plan I4 Task 5). */
    fun zimItem(path: String, mimeType: String, data: NSData): app.granger.core.zim.ZimItem =
        app.granger.core.zim.ZimItem(path, mimeType, data.toByteArray())
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

/** One section of an article for Contents: its anchor, its title and its level (2 or 3). */
data class Heading(val anchor: String, val title: String, val level: Int)
