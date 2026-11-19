package app.granger.online

import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * The one place that talks to Wikimedia (Plan O1 Task 2), on the app's shared client, so the network gate and the
 * User-Agent apply. Every call blocks: call it off the main thread ([onMainThread] catches a slip in debug, O0's
 * main-thread link check). At most [PER_HOST] requests go to one host at a time (builder's call 5). A call that fails
 * for any reason (no signal, Local only, a missing page) answers null or empty, never throws.
 */
class WikimediaClient(
    private val http: OkHttpClient,
    private val onMainThread: () -> Boolean = { false },
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

    private val gates = ConcurrentHashMap<String, Semaphore>()

    /** The article's Parsoid HTML (O0 Task 1's pick), following a redirect to its target's title. */
    fun html(lang: String, title: String): Page? =
        call(restUrl(lang, "w/rest.php/v1/page/${enc(title)}/html")) { r ->
            Page(titleFromPath(r.request.url, "html") ?: title, r.body.string())
        }

    fun summary(lang: String, title: String): Summary? =
        call(restUrl(lang, "api/rest_v1/page/summary/${enc(title)}")) { r -> summaryOf(JSONObject(r.body.string())) }

    /**
     * Summaries of up to [BATCH] articles in one request (`action=query`, extracts + page images + QIDs), by the asked
     * title: a broad topic's cards in one round trip instead of one each (Plan O1 Pixel pass). Redirects resolve to their
     * target; a missing page is left out. The extract is the plain lead, as the summary endpoint's.
     */
    fun summaries(lang: String, titles: List<String>): Map<String, Summary> {
        val out = HashMap<String, Summary>()
        for (chunk in titles.distinct().chunked(BATCH)) {
            val url = "https://$lang.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "query").addQueryParameter("format", "json").addQueryParameter("formatversion", "2")
                .addQueryParameter("redirects", "1").addQueryParameter("prop", "extracts|pageimages|pageprops")
                .addQueryParameter("exintro", "1").addQueryParameter("explaintext", "1").addQueryParameter("exlimit", "max")
                .addQueryParameter("piprop", "thumbnail").addQueryParameter("pithumbsize", "330").addQueryParameter("pilimit", "max")
                .addQueryParameter("ppprop", "wikibase_item").addQueryParameter("titles", chunk.joinToString("|") { it.replace('_', ' ') })
                .build()
            call(url) { r -> batchOf(r.body.string(), chunk) }?.let(out::putAll)
        }
        return out
    }

    /** A random article's summary (Wander's fallback when no index is on the phone). */
    fun random(lang: String): Summary? =
        call(restUrl(lang, "api/rest_v1/page/random/summary"), noCache = true) { r -> summaryOf(JSONObject(r.body.string())) }

    /** Titles that start with or closely match [query] (Search's titles group). */
    fun titles(lang: String, query: String, limit: Int): List<Hit> = search(lang, "title", query, limit)

    /** Full-text hits with their excerpt (Search's pages group). */
    fun pages(lang: String, query: String, limit: Int): List<Hit> = search(lang, "page", query, limit)

    /** Articles like [title] (`morelike:`), best first, each with its QID for the adult-content filter. */
    fun related(lang: String, title: String, limit: Int): List<RelatedPage> {
        val url = "https://$lang.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("format", "json").addQueryParameter("formatversion", "2")
            .addQueryParameter("generator", "search").addQueryParameter("gsrsearch", "morelike:${title.replace('_', ' ')}")
            .addQueryParameter("gsrnamespace", "0").addQueryParameter("gsrlimit", limit.coerceIn(1, 50).toString())
            .addQueryParameter("prop", "pageprops").addQueryParameter("ppprop", "wikibase_item").build()
        return call(url) { r -> relatedOf(r.body.string()) }.orEmpty()
    }

    /** A picture's bytes and type, from `upload.`/`thumb.wikimedia.org` or Commons' `Special:FilePath`. */
    fun picture(url: String): Pair<ByteArray, String>? {
        val u = url.toHttpUrl()
        if (!isWikimedia(u.host)) return null
        return call(u) { r -> r.body.bytes() to (r.header("Content-Type")?.substringBefore(';') ?: "image/jpeg") }
    }

    private fun search(lang: String, kind: String, query: String, limit: Int): List<Hit> {
        if (query.isBlank()) return emptyList()
        val url = restUrl(lang, "w/rest.php/v1/search/$kind").newBuilder()
            .addQueryParameter("q", query).addQueryParameter("limit", limit.coerceIn(1, 50).toString()).build()
        return call(url) { r -> hitsOf(r.body.string()) }.orEmpty()
    }

    private fun <T> call(url: HttpUrl, noCache: Boolean = false, read: (Response) -> T?): T? {
        check(!onMainThread()) { "Wikimedia called on the main thread: $url" }
        val gate = gates.computeIfAbsent(url.host) { Semaphore(PER_HOST) }
        gate.acquire()
        return try {
            val request = Request.Builder().url(url).apply { if (noCache) cacheControl(CacheControl.FORCE_NETWORK) }.build()
            http.newCall(request).execute().use { r -> if (r.isSuccessful) read(r) else null }
        } catch (e: Exception) {
            null
        } finally {
            gate.release()
        }
    }

    private fun restUrl(lang: String, path: String): HttpUrl = "https://$lang.wikipedia.org/$path".toHttpUrl()

    companion object {
        const val PER_HOST = 4
        /** Titles per batched summaries request: the extracts module's limit with exintro. */
        const val BATCH = 20

        /** Wikimedia's own hosts: the sites, `upload.`, `thumb.` and Commons. */
        fun isWikimedia(host: String) = host.endsWith(".wikipedia.org") || host.endsWith(".wikimedia.org")

        /** A title as one path segment: spaces as underscores, everything else percent-encoded. */
        fun enc(title: String): String = URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")

        /** The title the response's URL ends in (after a redirect), as a key with underscores. */
        internal fun titleFromPath(url: HttpUrl, last: String): String? {
            val segs = url.pathSegments
            val i = segs.lastIndexOf(last).takeIf { it > 0 } ?: return null
            return segs[i - 1].replace(' ', '_')
        }

        internal fun summaryOf(o: JSONObject): Summary? {
            val title = o.optString("title").takeIf { it.isNotBlank() } ?: return null
            if (o.optString("type") == "disambiguation" && o.optString("extract").isBlank()) return null
            val key = o.optJSONObject("titles")?.optString("canonical")?.takeIf { it.isNotBlank() } ?: title.replace(' ', '_')
            return Summary(
                title = o.optJSONObject("titles")?.optString("normalized")?.takeIf { it.isNotBlank() } ?: title,
                key = key,
                extract = o.optString("extract"),
                description = o.optString("description").takeIf { it.isNotBlank() },
                thumbnail = o.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.isNotBlank() }?.let(::withoutQuery),
                qid = qidOf(o.optString("wikibase_item")),
                revision = o.optString("revision").toLongOrNull(),
            )
        }

        /** A batch answer as summaries by the titles asked for ([chunk]), through its normalisations and redirects. */
        internal fun batchOf(json: String, chunk: List<String>): Map<String, Summary> {
            val q = JSONObject(json).optJSONObject("query") ?: return emptyMap()
            fun moves(name: String): Map<String, String> {
                val a = q.optJSONArray(name) ?: return emptyMap()
                return (0 until a.length()).map { a.getJSONObject(it) }.associate { it.getString("from") to it.getString("to") }
            }
            val normalized = moves("normalized")
            val redirects = moves("redirects")
            val pages = q.optJSONArray("pages") ?: return emptyMap()
            val byTitle = (0 until pages.length()).map { pages.getJSONObject(it) }.filter { !it.optBoolean("missing") && !it.has("invalid") }.associate { p ->
                val title = p.getString("title")
                title to Summary(
                    title = title, key = title.replace(' ', '_'), extract = tidy(p.optString("extract")), description = null,
                    thumbnail = p.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.isNotBlank() }?.let(::withoutQuery),
                    qid = qidOf(p.optJSONObject("pageprops")?.optString("wikibase_item").orEmpty()), revision = null,
                )
            }
            return chunk.mapNotNull { asked ->
                val spaced = asked.replace('_', ' ')
                val n = normalized[spaced] ?: normalized[asked] ?: spaced
                byTitle[redirects[n] ?: n]?.let { asked to it }
            }.toMap()
        }

        /** A plain-text lead without the husks its removed pronunciations leave: "Knowth (; Irish: …)" → "Knowth (Irish: …)". */
        internal fun tidy(text: String): String =
            text.replace(Regex("\\(\\s*[;,]\\s*"), "(").replace(Regex("\\s*\\(\\s*\\)"), "").replace(Regex("\\n+"), " ").trim()

        internal fun hitsOf(json: String): List<Hit> {
            val pages = JSONObject(json).optJSONArray("pages") ?: return emptyList()
            return (0 until pages.length()).map { pages.getJSONObject(it) }.map { p ->
                Hit(
                    key = p.getString("key"),
                    title = p.getString("title"),
                    description = p.optString("description").takeIf { it.isNotBlank() && it != "null" },
                    excerpt = p.optString("excerpt").takeIf { it.isNotBlank() && it != "null" }?.let(::plain),
                )
            }
        }

        internal fun relatedOf(json: String): List<RelatedPage> {
            val pages = JSONObject(json).optJSONObject("query")?.optJSONArray("pages") ?: return emptyList()
            return (0 until pages.length()).map { pages.getJSONObject(it) }
                .sortedBy { it.optInt("index", Int.MAX_VALUE) }
                .map { p ->
                    val title = p.getString("title")
                    RelatedPage(title.replace(' ', '_'), title, qidOf(p.optJSONObject("pageprops")?.optString("wikibase_item").orEmpty()))
                }
        }

        fun qidOf(item: String): Long? = item.takeIf { it.startsWith("Q") }?.drop(1)?.toLongOrNull()

        /** Wikimedia's thumbnails carry tracking parameters (2026); the picture is the same without them. */
        fun withoutQuery(url: String): String = url.substringBefore('?').let { if (it.startsWith("//")) "https:$it" else it }

        /** A search excerpt's text: its `searchmatch` spans and entities undone. */
        internal fun plain(html: String): String = org.jsoup.Jsoup.parse(html).text()

        /** A key back to the words a title shows. */
        fun titleOfKey(key: String): String = runCatching { URLDecoder.decode(key, "UTF-8") }.getOrDefault(key).replace('_', ' ')
    }
}
