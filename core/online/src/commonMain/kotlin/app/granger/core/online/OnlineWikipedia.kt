package app.granger.core.online

import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimEntry
import app.granger.core.zim.ZimHit
import app.granger.core.zim.ZimItem
import app.granger.core.zim.ZimMetadata
import app.granger.core.zim.ZimUrls
import com.fleeksoft.ksoup.Ksoup
import kotlin.time.TimeSource

/**
 * Wikipedia read live from Wikimedia, as if it were a pack (Plan O1 Task 3; O0 Task 4 showed the reader, its Contents,
 * read aloud and link handling run unchanged on it). Paths are titles with underscores, as in a Wikipedia ZIM and in our
 * QID indexes. Three kinds of path are read:
 * - an article: its Parsoid HTML, [transform]ed into a page the reader treats like a pack's;
 * - `_img/<host>/<path>`: a picture the transformed page names, fetched from Wikimedia (the WebView asks the reader's
 *   interceptor for it like any pack file, so the network gate covers it);
 * - a picture path from our indexes (`_assets_/…/Name.jpg`, `I/Name.jpg`): the same file from Commons by name
 *   ([filePathUrl]; builder's call 6).
 * It never fetches to say what a path is ([entry], [remote]), so a link tap on the main thread costs nothing.
 * Common to Android and iOS since Plan I1.
 */
class OnlineWikipediaArchive(
    val lang: String,
    private val wiki: WikimediaClient,
    /**
     * A kept copy's page, for an article that can't be fetched (no signal, Local only): spec §4's "any kept copy" after
     * online. Blocking; null when none is on the phone.
     */
    private val fallback: (lang: String, title: String) -> ZimItem? = { _, _ -> null },
    /** A random article from the index bound to this source (well known), before Wikipedia's own random (any stub). */
    private val indexRandom: () -> String? = { null },
    /** Kept articles and Saved for now (Plan O2); null reads straight from Wikimedia, as O1 did. */
    private val stores: OnlineStores? = null,
    /** Debug builds time each article's fetch and transform in the log. */
    private val timings: Boolean = false,
) : ZimArchive {
    override val uuid: String = "online-wikipedia-$lang"
    override val metadata = ZimMetadata(
        title = "Wikipedia", description = "", language = lang, date = "", name = packName(lang), // l10n-ignore: a brand
    )
    override val mainPath: String? = null
    override val hasFulltextIndex: Boolean = true
    override val remote: Boolean = true

    override fun resolvePath(path: String): String = path

    override fun entry(path: String): ZimEntry =
        ZimEntry(path, WikimediaClient.titleOfKey(if (mimeOf(path) == "text/html") path else path.substringAfterLast('/')), mimeOf(path), 0L)

    override fun read(path: String): ZimItem? = try {
        when {
            path.startsWith(IMG) -> picture(path, "https://" + path.removePrefix(IMG))
            // Commons' own thumbnail address first (one request, to the image servers); the wiki's FilePath, which also
            // finds a file kept on the wiki alone, when Commons hasn't it.
            isIndexPicture(path) -> picture(path, commonsThumbUrl(path, FIGURE_PX)) ?: picture(path, filePathUrl(lang, path, FIGURE_PX))
            else -> article(path)
        }
    } catch (e: Exception) {
        OnlineLog.warn("read $path failed: $e")
        null
    }

    /** Summaries asked for together ([prefetch]), kept for the cards that follow (a card may be made at more than one size). */
    private val early = Early()

    /**
     * The article's summary (header, card, plate): one small request instead of the whole article. Saved for now, so a
     * card still shows with no signal (Plan O2 Task 1).
     */
    fun summary(path: String): WikimediaClient.Summary? {
        early[path]?.let { return it }
        val key = OnlineStores.summaryKey(lang, path)
        val kept = stores?.saved(key)
        if (kept != null && stores.now() - kept.savedAt < OnlineStores.FRESH_MS) WikimediaClient.summaryFromJson(kept.bytes.decodeToString())?.let { return it }
        val live = wiki.summary(lang, path)
        if (live != null) {
            stores?.save(key, OnlineStores.SavedKind.SUMMARY, WikimediaClient.summaryToJson(live).encodeToByteArray(), "application/json")
            return live
        }
        return kept?.let { WikimediaClient.summaryFromJson(it.bytes.decodeToString()) }
    }

    /** Asks for many articles' summaries in a few batched requests, so the cards made next don't each ask (a broad topic's). */
    fun prefetch(paths: List<String>) {
        val wanted = paths.filter { early[it] == null }.distinct()
        if (wanted.isEmpty()) return
        if (early.size > EARLY_MAX) early.clear()
        early.putAll(wiki.summaries(lang, wanted))
    }

    /**
     * An article, in spec §4's order (Plan O2 Task 1):
     * 1. kept with its pictures (a full copy beats online);
     * 2. saved for now within the hour (Back is instant);
     * 3. online, saved as it comes;
     * 4. saved for now of any age, then kept as text only (shorter copies, when online can't be had);
     * 5. a pack's shorter copy (O1's fallback).
     */
    private fun article(title: String): ZimItem? {
        val kept = stores?.keptWithPictures(lang, title)
        if (kept == true) stores.keptPage(lang, title)?.let { return html(title, it) }
        val saved = stores?.saved(OnlineStores.pageKey(lang, title))
        if (saved != null && stores.now() - saved.savedAt < OnlineStores.FRESH_MS) return html(title, saved.bytes)
        fetch(title)?.let { return it }
        saved?.let { return html(title, it.bytes) }
        if (kept != null) stores.keptPage(lang, title)?.let { return html(title, it) }
        return fallback(lang, title)?.let { ZimItem(title, it.mimeType, it.bytes) }
    }

    /** The article fetched and prepared, saved for now; null when it can't be had. */
    private fun fetch(title: String): ZimItem? {
        val t0 = TimeSource.Monotonic.markNow()
        val page = wiki.html(lang, title) ?: return null
        val t1 = TimeSource.Monotonic.markNow()
        val bytes = transform(page.html, page.title).encodeToByteArray()
        if (timings) {
            OnlineLog.info("article '$title': fetch ${(t1 - t0).inWholeMilliseconds} ms (${page.html.length / 1024} KB), transform ${t1.elapsedNow().inWholeMilliseconds} ms")
        }
        stores?.save(OnlineStores.pageKey(lang, title), OnlineStores.SavedKind.PAGE, bytes, HTML)
        stores?.pageSaved()
        return html(title, bytes)
    }

    private fun html(title: String, bytes: ByteArray) = ZimItem(title, HTML, bytes)

    /** A picture: kept, saved for now, then online (saved as it comes, unless Offline pictures is Text only). */
    private fun picture(path: String, url: String): ZimItem? {
        stores?.keptPicture(path)?.let { return ZimItem(path, mimeOf(path), it) }
        val key = OnlineStores.pictureKey(path)
        stores?.saved(key)?.let { return ZimItem(path, it.mime, it.bytes) }
        val (bytes, type) = wiki.picture(url) ?: return null
        if (stores?.savePictures() == true) stores.save(key, OnlineStores.SavedKind.PICTURE, bytes, type)
        return ZimItem(path, type, bytes)
    }

    /**
     * What keeping [title] stores (Plan O2 Task 3): its prepared page, from Saved for now when it's there, else fetched,
     * and, [withPictures], every picture the page shows, saved or fetched. Null when the page can't be had. Blocking.
     */
    fun keepContent(title: String, withPictures: Boolean): KeepContent? {
        val page = stores?.saved(OnlineStores.pageKey(lang, title))?.bytes ?: fetch(title)?.bytes ?: return null
        val pictures = LinkedHashMap<String, ByteArray>()
        if (withPictures) {
            // Several at once (the client keeps to its few per host): one by one, a long article took 13 s on the Pixel.
            val srcs = picturesOf(page.decodeToString())
            val found = parallelMap(srcs, KEEP_THREADS) { src -> src to picture(src, "https://" + src.removePrefix(IMG))?.bytes }
            for (f in found) f?.let { (src, bytes) -> if (bytes != null) pictures[src] = bytes }
        }
        return KeepContent(page, pictures)
    }

    override fun randomPath(): String? = indexRandom() ?: wiki.random(lang)?.key

    override fun suggest(query: String, limit: Int): List<ZimHit> =
        wiki.titles(lang, query, limit).map { ZimHit(it.key, it.title) }

    override fun search(query: String, limit: Int): List<ZimHit> =
        wiki.pages(lang, query, limit).map { ZimHit(it.key, it.title, it.excerpt) }

    override fun close() = Unit

    companion object {
        const val TAG = "GrangerOnline"
        const val IMG = "_img/"
        /** Summaries kept from a prefetch at most; past it they're dropped (a card not made after all). */
        private const val EARLY_MAX = 400
        private const val FIGURE_PX = 960   // one of Wikimedia's standard thumbnail widths
        private const val PREFIX = "wikipedia_"
        private const val SUFFIX = "_online"

        private const val HTML = "text/html; charset=utf-8"
        /** Pictures fetched at once while keeping an article. */
        private const val KEEP_THREADS = 4

        fun packName(lang: String) = "$PREFIX$lang$SUFFIX"

        /** The reader paths of the pictures a prepared page shows (`_img/<host>/<path>`), in order, once each. */
        fun picturesOf(html: String): List<String> =
            Ksoup.parse(html).select("img[src]").map { it.attr("src") }.filter { it.startsWith(IMG) }.distinct()

        /** The language of an online source's id (`wikipedia_es_online` → `es`); null for any other id. */
        fun languageOf(packId: String): String? =
            packId.takeIf { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }?.removePrefix(PREFIX)?.removeSuffix(SUFFIX)?.takeIf { it.length in 2..3 }

        private val PICTURE = Regex("\\.(jpe?g|png|gif|svg|webp|tiff?)$", RegexOption.IGNORE_CASE)

        /** A picture path as our indexes name it (from the ZIM they were built from), not an article. */
        fun isIndexPicture(path: String): Boolean =
            (path.startsWith("_assets_/") || path.startsWith("I/") || path.startsWith("-/")) && PICTURE.containsMatchIn(path)

        private fun mimeOf(path: String): String = when {
            path.startsWith(IMG) || isIndexPicture(path) -> when (path.substringAfterLast('.').lowercase()) {
                "png" -> "image/png"; "gif" -> "image/gif"; "svg" -> "image/svg+xml"; "webp" -> "image/webp"; else -> "image/jpeg"
            }
            else -> "text/html"
        }

        /**
         * The Commons file an index picture path names. mwoffliner encodes a file's name twice (`%252C` for a comma),
         * renders an SVG as `.svg.png` and may add `.webp`; undone here, the name is the wiki's file name (O0 Task 3:
         * 40 of 40 resolve).
         */
        fun fileName(path: String): String {
            var name = path.substringAfterLast('/')
            repeat(2) { name = runCatching { UrlCodec.formDecode(name.replace("+", "%2B")) }.getOrDefault(name) }
            if (name.endsWith(".webp", ignoreCase = true) && PICTURE.containsMatchIn(name.dropLast(5))) name = name.dropLast(5)
            if (name.endsWith(".svg.png", ignoreCase = true)) name = name.dropLast(4)
            return name.replace(' ', '_')
        }

        /**
         * [path]'s file through the wiki's `Special:FilePath`, which finds a file on Commons or on the wiki itself (a
         * local non-free file) and redirects to a thumbnail [px] wide.
         */
        fun filePathUrl(lang: String, path: String, px: Int): String =
            "https://$lang.wikipedia.org/wiki/Special:FilePath/${WikimediaClient.enc(fileName(path))}?width=$px"

        /**
         * [path]'s file as a Commons thumbnail [px] wide, at the address Commons keeps it under (the MD5 of its name picks
         * the folders); an SVG's thumbnail is a PNG. Asking the image servers directly skips FilePath's redirect.
         */
        fun commonsThumbUrl(path: String, px: Int): String {
            val name = fileName(path)
            val md5 = md5(name.encodeToByteArray()).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            val enc = WikimediaClient.enc(name)
            val thumb = if (name.endsWith(".svg", ignoreCase = true)) "$enc.png" else enc
            return "https://upload.wikimedia.org/wikipedia/commons/thumb/${md5[0]}/${md5.substring(0, 2)}/$enc/${px}px-$thumb"
        }

        private val STYLESHEET = ZimUrls.forEntry("_granger", "online.css")

        /**
         * Parsoid HTML → a page the reader treats like a pack's (O0 Task 1). Pure, so it's tested at the desk:
         * - Wikimedia's own scaffolding goes (`base`, `mw:`/`dc:` links and metadata, its stylesheets, scripts, edit links);
         * - `./Title` links become `Title`, as in a ZIM; protocol-relative links get `https:`;
         * - a picture becomes `_img/<host>/<path>` (no tracking query), at its 2x size when the screen is [dense];
         * - Granger's `online.css` takes the place of Wikipedia's skin, with `color-scheme: light dark` so Night colours
         *   it rather than the WebView's darkening, and `skin-theme-clientpref-os` so the articles' own template styles
         *   follow it too.
         */
        fun transform(html: String, title: String, dense: Boolean = true): String {
            val doc = Ksoup.parse(html)
            doc.select("base, link[rel~=mw:|dc:], link[rel=stylesheet], meta[property], script").remove()
            doc.select("span.mw-editsection").remove()
            for (a in doc.select("a[href]")) {
                val href = a.attr("href")
                when {
                    href.startsWith("./") -> a.attr("href", href.removePrefix("./"))
                    href.startsWith("//") -> a.attr("href", "https:$href")
                }
            }
            for (img in doc.select("img[src]")) {
                val src2x = if (dense) img.attr("srcset").split(',').map { it.trim() }.firstOrNull { it.endsWith(" 2x") }?.removeSuffix(" 2x")?.trim() else null
                val src = src2x?.takeIf { it.isNotBlank() } ?: img.attr("src")
                if (src.startsWith("//") && WikimediaClient.isWikimedia(src.substringAfter("//").substringBefore('/'))) {
                    img.attr("src", IMG + src.removePrefix("//").substringBefore('?'))
                }
                img.removeAttr("srcset")
            }
            doc.selectFirst("html")?.addClass("skin-theme-clientpref-os")
            doc.head().appendElement("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1")
            doc.head().appendElement("meta").attr("name", "color-scheme").attr("content", "light dark")
            doc.head().appendElement("link").attr("rel", "stylesheet").attr("href", STYLESHEET)
            if (doc.title().isBlank()) doc.title(WikimediaClient.titleOfKey(title))
            if (doc.selectFirst("h1") == null) doc.body().prependElement("h1").text(doc.title())
            return doc.outerHtml()
        }
    }
}

/** Summaries kept from a prefetch, safe from any thread. */
private class Early {
    private val map = HashMap<String, WikimediaClient.Summary>()
    private val lock = SyncLock()
    operator fun get(key: String): WikimediaClient.Summary? = lock.with { map[key] }
    val size: Int get() = lock.with { map.size }
    fun clear() = lock.with { map.clear() }
    fun putAll(all: Map<String, WikimediaClient.Summary>) = lock.with { map.putAll(all) }
}
