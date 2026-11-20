package app.granger.core.online

import com.fleeksoft.ksoup.Ksoup
import kotlinx.serialization.json.JsonObject

/**
 * The one place that talks to Wikimedia (Plan O1 Task 2), on the app's shared client ([HttpFetch]), so the network
 * gate and the User-Agent apply. Common to Android and iOS since Plan I1. Every call blocks: call it off the main thread ([onMainThread] catches a slip in debug, O0's
 * main-thread link check). At most [PER_HOST] requests go to one host at a time (builder's call 5). A call that fails
 * for any reason (no signal, Local only, a missing page) answers null or empty, never throws.
 */
class WikimediaClient(
    private val http: HttpFetch,
    private val onMainThread: () -> Boolean = { false },
    /** The privacy line has been seen (Plan O5): until then nothing is asked of Wikimedia. */
    private val allowed: () -> Boolean = { true },
) {
    data class Page(val title: String, val html: String)

    /** What a topic page's header, a card and a plate need of an article (`/page/summary`). */
    data class Summary(
        val title: String,
        /** The canonical title with underscores: the article's path in the online source. */
        val key: String,
        val extract: String,
        val description: String?,
        val thumbnail: String?,
        val qid: Long?,
        val revision: Long?,
    )

    data class Hit(val key: String, val title: String, val description: String?, val excerpt: String?)

    data class RelatedPage(val key: String, val title: String, val qid: Long?)

    /** Wikimedia's Picture of the day: a curated Commons image (the feed's `image`). */
    data class DayPicture(val title: String, val thumbnail: String, val description: String?, val artist: String?, val filePage: String?)

    /** A day's front page (`/feed/featured`): the featured article where the wiki has one, and the picture of the day. */
    data class Featured(val article: Summary?, val picture: DayPicture?)

    private val gates = HostGates(PER_HOST)

    /** The article's Parsoid HTML (O0 Task 1's pick), following a redirect to its target's title. */
    fun html(lang: String, title: String): Page? =
        call(restUrl(lang, "w/rest.php/v1/page/${enc(title)}/html")) { r ->
            Page(titleFromPath(r.url, "html") ?: title, r.text())
        }

    fun summary(lang: String, title: String): Summary? =
        call(restUrl(lang, "api/rest_v1/page/summary/${enc(title)}")) { r -> parseObject(r.text())?.let(::summaryOf) }

    /**
     * Summaries of up to [BATCH] articles in one request (`action=query`, extracts + page images + QIDs), by the asked
     * title: a broad topic's cards in one round trip instead of one each (Plan O1 Pixel pass). Redirects resolve to their
     * target; a missing page is left out. The extract is the plain lead, as the summary endpoint's.
     */
    fun summaries(lang: String, titles: List<String>): Map<String, Summary> {
        val out = HashMap<String, Summary>()
        for (chunk in titles.distinct().chunked(BATCH)) {
            val url = UrlCodec.withQuery(
                "https://$lang.wikipedia.org/w/api.php",
                "action" to "query", "format" to "json", "formatversion" to "2",
                "redirects" to "1", "prop" to "extracts|pageimages|pageprops",
                "exintro" to "1", "explaintext" to "1", "exlimit" to "max",
                "piprop" to "thumbnail", "pithumbsize" to "330", "pilimit" to "max",
                "ppprop" to "wikibase_item", "titles" to chunk.joinToString("|") { it.replace('_', ' ') },
            )
            call(url) { r -> batchOf(r.text(), chunk) }?.let(out::putAll)
        }
        return out
    }

    /**
     * The live revision of up to [BATCH] articles a request, by the asked title (Plan O5 Task 4: updates for kept
     * articles). A missing page is left out.
     */
    fun revisions(lang: String, titles: List<String>): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (chunk in titles.distinct().chunked(BATCH)) {
            val url = UrlCodec.withQuery(
                "https://$lang.wikipedia.org/w/api.php",
                "action" to "query", "format" to "json", "formatversion" to "2",
                "redirects" to "1", "prop" to "revisions", "rvprop" to "ids",
                "titles" to chunk.joinToString("|") { it.replace('_', ' ') },
            )
            call(url) { r -> revisionsOf(r.text(), chunk) }?.let(out::putAll)
        }
        return out
    }

    /**
     * The featured article and the picture of the day for [year]-[month]-[day] (Plan I2 Task 2). English Wikipedia has
     * a featured article in its feed; Spanish doesn't (checked 2026-11-20), so its Home shows the picture.
     */
    fun featured(lang: String, year: Int, month: Int, day: Int): Featured? {
        val date = year.toString().padStart(4, '0') + "/" + month.toString().padStart(2, '0') + "/" + day.toString().padStart(2, '0')
        return call(restUrl(lang, "api/rest_v1/feed/featured/$date")) { r -> featuredOf(r.text()) }
    }

    /** [title]'s title in the [to] Wikipedia ("Read in Español"), following redirects; null when it has none. */
    fun languageLink(lang: String, title: String, to: String): String? {
        val url = UrlCodec.withQuery(
            "https://$lang.wikipedia.org/w/api.php",
            "action" to "query", "format" to "json", "formatversion" to "2", "redirects" to "1",
            "prop" to "langlinks", "lllang" to to, "titles" to title.replace('_', ' '),
        )
        return call(url) { r -> languageLinkOf(r.text()) }
    }

    /** A random article's summary (Wander's fallback when no index is on the phone). */
    fun random(lang: String): Summary? =
        call(restUrl(lang, "api/rest_v1/page/random/summary"), noCache = true) { r -> parseObject(r.text())?.let(::summaryOf) }

    /** Titles that start with or closely match [query] (Search's titles group). */
    fun titles(lang: String, query: String, limit: Int): List<Hit> = search(lang, "title", query, limit)

    /** Full-text hits with their excerpt (Search's pages group). */
    fun pages(lang: String, query: String, limit: Int): List<Hit> = search(lang, "page", query, limit)

    /** Articles like [title] (`morelike:`), best first, each with its QID for the adult-content filter. */
    fun related(lang: String, title: String, limit: Int): List<RelatedPage> {
        val url = UrlCodec.withQuery(
            "https://$lang.wikipedia.org/w/api.php",
            "action" to "query", "format" to "json", "formatversion" to "2",
            "generator" to "search", "gsrsearch" to "morelike:${title.replace('_', ' ')}",
            "gsrnamespace" to "0", "gsrlimit" to limit.coerceIn(1, 50).toString(),
            "prop" to "pageprops", "ppprop" to "wikibase_item",
        )
        return call(url) { r -> relatedOf(r.text()) }.orEmpty()
    }

    /** A picture's bytes and type, from `upload.`/`thumb.wikimedia.org` or Commons' `Special:FilePath`. */
    fun picture(url: String): Pair<ByteArray, String>? {
        val host = UrlCodec.hostOf(url) ?: return null
        if (!isWikimedia(host)) return null
        return call(url) { r -> r.body to (r.contentType?.substringBefore(';') ?: "image/jpeg") }
    }

    private fun search(lang: String, kind: String, query: String, limit: Int): List<Hit> {
        if (query.isBlank()) return emptyList()
        val url = UrlCodec.withQuery(restUrl(lang, "w/rest.php/v1/search/$kind"), "q" to query, "limit" to limit.coerceIn(1, 50).toString())
        return call(url) { r -> hitsOf(r.text()) }.orEmpty()
    }

    private fun <T> call(url: String, noCache: Boolean = false, read: (HttpReply) -> T?): T? {
        check(!onMainThread()) { "Wikimedia called on the main thread: $url" }
        if (!allowed()) return null
        val host = UrlCodec.hostOf(url) ?: return null
        return try {
            gates.through(host) { http.get(url, noCache)?.takeIf { it.ok }?.let(read) }
        } catch (e: Exception) {
            null
        }
    }

    private fun restUrl(lang: String, path: String): String = "https://$lang.wikipedia.org/$path"

    companion object {
        const val PER_HOST = 4
        /** Titles per batched summaries request: the extracts module's limit with exintro. */
        const val BATCH = 20

        /** Wikimedia's own hosts: the sites, `upload.`, `thumb.` and Commons. */
        fun isWikimedia(host: String) = host.endsWith(".wikipedia.org") || host.endsWith(".wikimedia.org")

        /** A title as one path segment: spaces as underscores, everything else percent-encoded. */
        fun enc(title: String): String = UrlCodec.formEncode(title.replace(' ', '_')).replace("+", "%20")

        /** The title the response's URL ends in (after a redirect), as a key with underscores. */
        internal fun titleFromPath(url: String, last: String): String? {
            val segs = UrlCodec.pathSegments(url)
            val i = segs.lastIndexOf(last).takeIf { it > 0 } ?: return null
            return segs[i - 1].replace(' ', '_')
        }

        internal fun summaryOf(o: JsonObject): Summary? {
            val title = o.str("title").takeIf { it.isNotBlank() } ?: return null
            if (o.str("type") == "disambiguation" && o.str("extract").isBlank()) return null
            val key = o.obj("titles")?.str("canonical")?.takeIf { it.isNotBlank() } ?: title.replace(' ', '_')
            return Summary(
                title = o.obj("titles")?.str("normalized")?.takeIf { it.isNotBlank() } ?: title,
                key = key,
                extract = o.str("extract"),
                description = o.str("description").takeIf { it.isNotBlank() },
                thumbnail = o.obj("thumbnail")?.str("source")?.takeIf { it.isNotBlank() }?.let(::withoutQuery),
                qid = qidOf(o.str("wikibase_item")),
                revision = o.str("revision").toLongOrNull(),
            )
        }

        /** A batch answer as summaries by the titles asked for ([chunk]), through its normalisations and redirects. */
        internal fun batchOf(json: String, chunk: List<String>): Map<String, Summary> {
            val q = parseObject(json)?.obj("query") ?: return emptyMap()
            fun moves(name: String): Map<String, String> =
                q.arr(name)?.objects()?.associate { it.str("from") to it.str("to") }.orEmpty()
            val normalized = moves("normalized")
            val redirects = moves("redirects")
            val pages = q.arr("pages") ?: return emptyMap()
            val byTitle = pages.objects().filter { !it.bool("missing") && !it.containsKey("invalid") }.associate { p ->
                val title = p.str("title")
                title to Summary(
                    title = title, key = title.replace(' ', '_'), extract = tidy(p.str("extract")), description = null,
                    thumbnail = p.obj("thumbnail")?.str("source")?.takeIf { it.isNotBlank() }?.let(::withoutQuery),
                    qid = qidOf(p.obj("pageprops")?.str("wikibase_item").orEmpty()), revision = null,
                )
            }
            return chunk.mapNotNull { asked ->
                val spaced = asked.replace('_', ' ')
                val n = normalized[spaced] ?: normalized[asked] ?: spaced
                byTitle[redirects[n] ?: n]?.let { asked to it }
            }.toMap()
        }

        internal fun revisionsOf(json: String, chunk: List<String>): Map<String, Long> {
            val q = parseObject(json)?.obj("query") ?: return emptyMap()
            fun moves(name: String): Map<String, String> =
                q.arr(name)?.objects()?.associate { it.str("from") to it.str("to") }.orEmpty()
            val normalized = moves("normalized")
            val redirects = moves("redirects")
            val pages = q.arr("pages") ?: return emptyMap()
            val byTitle = pages.objects().filter { !it.bool("missing") }.mapNotNull { p ->
                (p.arr("revisions")?.firstOrNull() as? JsonObject)?.long("revid")?.takeIf { it > 0 }?.let { p.str("title") to it }
            }.toMap()
            return chunk.mapNotNull { asked ->
                val spaced = asked.replace('_', ' ')
                val n = normalized[spaced] ?: normalized[asked] ?: spaced
                byTitle[redirects[n] ?: n]?.let { asked to it }
            }.toMap()
        }

        /** A plain-text lead without the husks its removed pronunciations leave: "Knowth (; Irish: …)" → "Knowth (Irish: …)". */
        internal fun tidy(text: String): String =
            text.replace(Regex("\\(\\s*[;,]\\s*"), "(").replace(Regex("\\s*\\(\\s*\\)"), "").replace(Regex("\\n+"), " ").trim()

        /** A summary as it's saved for now (Plan O2 Task 1), and back. */
        fun summaryToJson(s: Summary): String = jsonOf(
            "title" to s.title, "key" to s.key, "extract" to s.extract, "description" to s.description,
            "thumbnail" to s.thumbnail, "qid" to s.qid, "revision" to s.revision,
        )

        fun summaryFromJson(json: String): Summary? = runCatching {
            val o = parseObject(json)!!
            require(o.containsKey("title") && o.containsKey("key"))
            Summary(
                o.str("title"), o.str("key"), o.str("extract"), o.str("description").takeIf { it.isNotBlank() },
                o.str("thumbnail").takeIf { it.isNotBlank() }, o.long("qid").takeIf { it > 0 }, o.long("revision").takeIf { it > 0 },
            )
        }.getOrNull()

        internal fun hitsOf(json: String): List<Hit> {
            val pages = parseObject(json)?.arr("pages") ?: return emptyList()
            return pages.objects().map { p ->
                Hit(
                    key = p.str("key"),
                    title = p.str("title"),
                    description = p.str("description").takeIf { it.isNotBlank() && it != "null" },
                    excerpt = p.str("excerpt").takeIf { it.isNotBlank() && it != "null" }?.let(::plain),
                )
            }
        }

        internal fun relatedOf(json: String): List<RelatedPage> {
            val pages = parseObject(json)?.obj("query")?.arr("pages") ?: return emptyList()
            return pages.objects()
                .sortedBy { it.int("index", Int.MAX_VALUE) }
                .map { p ->
                    val title = p.str("title")
                    RelatedPage(title.replace(' ', '_'), title, qidOf(p.obj("pageprops")?.str("wikibase_item").orEmpty()))
                }
        }

        internal fun featuredOf(json: String): Featured? {
            val o = parseObject(json) ?: return null
            val article = o.obj("tfa")?.let(::summaryOf)
            val picture = o.obj("image")?.let { i ->
                val thumb = i.obj("thumbnail")?.str("source")?.takeIf { it.isNotBlank() } ?: return@let null
                DayPicture(
                    title = i.str("title").removePrefix("File:").substringBeforeLast('.').replace('_', ' '),
                    thumbnail = withoutQuery(thumb),
                    description = i.obj("description")?.str("text")?.takeIf { it.isNotBlank() },
                    artist = i.obj("artist")?.str("text")?.takeIf { it.isNotBlank() },
                    filePage = i.str("file_page").takeIf { it.isNotBlank() },
                )
            }
            return if (article == null && picture == null) null else Featured(article, picture)
        }

        internal fun languageLinkOf(json: String): String? {
            val page = parseObject(json)?.obj("query")?.arr("pages")?.objects()?.firstOrNull() ?: return null
            return (page.arr("langlinks")?.firstOrNull() as? kotlinx.serialization.json.JsonObject)?.str("title")
                ?.takeIf { it.isNotBlank() }?.replace(' ', '_')
        }

        fun qidOf(item: String): Long? = item.takeIf { it.startsWith("Q") }?.drop(1)?.toLongOrNull()

        /** Wikimedia's thumbnails carry tracking parameters (2026); the picture is the same without them. */
        fun withoutQuery(url: String): String = url.substringBefore('?').let { if (it.startsWith("//")) "https:$it" else it }

        /** A search excerpt's text: its `searchmatch` spans and entities undone. */
        internal fun plain(html: String): String = Ksoup.parse(html).text()

        /** A key back to the words a title shows. */
        fun titleOfKey(key: String): String = runCatching { UrlCodec.formDecode(key) }.getOrDefault(key).replace('_', ' ')
    }
}
