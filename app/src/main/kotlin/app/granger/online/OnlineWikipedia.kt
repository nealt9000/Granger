package app.granger.online

import android.util.Log
import app.granger.core.library.Library
import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimEntry
import app.granger.core.zim.ZimHit
import app.granger.core.zim.ZimItem
import app.granger.core.zim.ZimMetadata
import app.granger.core.zim.ZimUrls
import org.jsoup.Jsoup
import java.net.URLDecoder

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
        Log.w(TAG, "read $path failed: $e")
        null
    }

    /** Summaries asked for together ([prefetch]), kept for the cards that follow (a card may be made at more than one size). */
    private val early = java.util.concurrent.ConcurrentHashMap<String, WikimediaClient.Summary>()

    /** The article's summary (header, card, plate): one small request instead of the whole article. */
    fun summary(path: String): WikimediaClient.Summary? = early[path] ?: wiki.summary(lang, path)

    /** Asks for many articles' summaries in a few batched requests, so the cards made next don't each ask (a broad topic's). */
    fun prefetch(paths: List<String>) {
        val wanted = paths.filter { !early.containsKey(it) }.distinct()
        if (wanted.isEmpty()) return
        if (early.size > EARLY_MAX) early.clear()
        early.putAll(wiki.summaries(lang, wanted))
    }

    private fun article(title: String): ZimItem? {
        val t0 = System.nanoTime()
        val page = wiki.html(lang, title) ?: return fallback(lang, title)?.let { ZimItem(title, it.mimeType, it.bytes) }
        val t1 = System.nanoTime()
        val html = transform(page.html, page.title)
        if (app.granger.BuildConfig.DEBUG) {
            Log.i(TAG, "article '$title': fetch ${(t1 - t0) / 1_000_000} ms (${page.html.length / 1024} KB), transform ${(System.nanoTime() - t1) / 1_000_000} ms")
        }
        return ZimItem(title, "text/html; charset=utf-8", html.toByteArray())
    }

    private fun picture(path: String, url: String): ZimItem? =
        wiki.picture(url)?.let { (bytes, type) -> ZimItem(path, type, bytes) }

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

        fun packName(lang: String) = "$PREFIX$lang$SUFFIX"

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
            repeat(2) { name = runCatching { URLDecoder.decode(name.replace("+", "%2B"), "UTF-8") }.getOrDefault(name) }
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
            val md5 = java.security.MessageDigest.getInstance("MD5").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }
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
            val doc = Jsoup.parse(html)
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

/** The library, plus the online sources: `archive("wikipedia_en_online")` reads live from Wikimedia. */
class OnlineLibrary(
    private val base: Library,
    /** Debug only: hide the installed Wikipedia packs (Preview: no Wikipedia on the phone, Plan O1 Task 10). */
    hideWikipedia: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false),
    private val online: () -> Map<String, ZimArchive>,
) : Library by base {
    override val packs: kotlinx.coroutines.flow.Flow<List<app.granger.core.library.Pack>> =
        kotlinx.coroutines.flow.combine(base.packs, hideWikipedia) { packs, hide -> if (hide) packs.filterNot { it.name.startsWith("wikipedia_") } else packs }

    override fun archive(packId: String): ZimArchive? = online()[packId] ?: base.archive(packId)
    override fun cachedArchive(packId: String): ZimArchive? = online()[packId] ?: base.cachedArchive(packId)
}
