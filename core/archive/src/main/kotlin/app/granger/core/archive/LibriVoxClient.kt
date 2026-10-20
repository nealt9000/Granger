package app.granger.core.archive

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * The LibriVox catalog API (`/api/feed/audiobooks/`). Requests go out one at a time, at least [minGapMs] apart
 * (spec §12); [clock] and [sleep] are injected so tests never wait. LibriVox's "no results" is HTTP 404 with an error
 * body, which reads as an empty page; a 429 is "busy".
 */
class LibriVoxClient(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val baseUrl: String = "https://librivox.org",
    private val minGapMs: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pace = Mutex()
    private var lastSentAt: Long? = null

    suspend fun search(query: LibriVoxQuery, offset: Int, limit: Int = 50): LibriVoxPage = when (query) {
        is LibriVoxQuery.Text -> {
            val text = query.text.trim()
            val title = text.replace(LEADING_ARTICLE, "").ifBlank { text }
            val byTitle = list(offset, limit) { addQueryParameter("title", title) }
            val byAuthor = text.split(SPACES).lastOrNull { it.isNotBlank() }
                ?.let { lastWord -> list(offset, limit) { addQueryParameter("author", lastWord) } }
                .orEmpty()
            LibriVoxPage(
                books = (byTitle + byAuthor).distinctBy { it.id },
                hasMore = byTitle.size >= limit || byAuthor.size >= limit,
            )
        }
        is LibriVoxQuery.Genre -> page(limit, list(offset, limit) { addQueryParameter("genre", query.name) })
        LibriVoxQuery.Recent -> page(limit, list(offset, limit) {
            addQueryParameter("sort_field", "id")
            addQueryParameter("sort_order", "desc")
        })
    }

    /** One book with its sections, readers and genres (`extended=1`). */
    suspend fun book(id: String): LibriVoxBook {
        if (!isValidId(id)) throw LibriVoxError(NOT_FOUND)
        val books = fetch(url {
            addQueryParameter("id", id)
            addQueryParameter("extended", "1")
            addQueryParameter("coverart", "1")
        })
        return books.firstOrNull { it.id == id } ?: throw LibriVoxError(NOT_FOUND)
    }

    private fun page(limit: Int, books: List<LibriVoxBook>) = LibriVoxPage(books, hasMore = books.size >= limit)

    private suspend fun list(offset: Int, limit: Int, params: HttpUrl.Builder.() -> Unit): List<LibriVoxBook> = fetch(url {
        params()
        addQueryParameter("fields", SEARCH_FIELDS)
        addQueryParameter("limit", limit.toString())
        addQueryParameter("offset", offset.toString())
    })

    private fun url(params: HttpUrl.Builder.() -> Unit): HttpUrl =
        "$baseUrl/api/feed/audiobooks/".toHttpUrl().newBuilder().apply {
            params()
            addQueryParameter("format", "json")
        }.build()

    private suspend fun fetch(url: HttpUrl): List<LibriVoxBook> {
        val reply = pace.withLock {
            lastSentAt?.let { last ->
                val wait = last + minGapMs - clock()
                if (wait > 0) sleep(wait)
            }
            lastSentAt = clock()
            client.getText(url, userAgent) { LibriVoxError("Couldn't reach LibriVox") }
        }
        when {
            reply.code == 404 && NO_RESULTS in reply.body -> return emptyList()
            reply.code == 429 -> throw LibriVoxError(BUSY)
            !reply.isSuccessful -> throw LibriVoxError("LibriVox answered ${reply.code}")
        }
        return parse {
            val root = json.parseToJsonElement(reply.body).jsonObject
            (root["books"] as? JsonArray).orEmpty().map { it.jsonObject.toBook() }
        }
    }

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: LibriVoxError) {
        throw e
    } catch (e: Exception) {
        throw LibriVoxError("LibriVox sent something Granger couldn't read")
    }

    private fun JsonObject.toBook(): LibriVoxBook {
        val seconds = this["totaltimesecs"]?.asLong()?.takeIf { it > 0 }
        return LibriVoxBook(
            id = this["id"]!!.firstString().orEmpty(),
            title = this["title"]?.firstString().orEmpty().trim(),
            authors = (this["authors"] as? JsonArray).orEmpty().mapNotNull { author ->
                val a = author as? JsonObject ?: return@mapNotNull null
                listOfNotNull(a["first_name"]?.firstString(), a["last_name"]?.firstString())
                    .joinToString(" ") { it.trim() }.trim().ifEmpty { null }
            },
            description = stripHtml(this["description"]?.firstString().orEmpty()),
            language = this["language"]?.firstString().orEmpty(),
            genres = (this["genres"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.get("name")?.firstString()?.trim()?.ifEmpty { null } },
            totalTimeMs = (seconds ?: clockSeconds(this["totaltime"]?.firstString().orEmpty()) ?: 0L) * 1000,
            copyrightYear = this["copyright_year"]?.firstString().orEmpty(),
            archiveIdentifier = archiveIdentifierOf(this["url_iarchive"]?.firstString().orEmpty()),
            pageUrl = this["url_librivox"]?.firstString().orEmpty(),
            sections = (this["sections"] as? JsonArray).orEmpty()
                .mapIndexed { index, section -> section.jsonObject.toSection(index) }
                .sortedBy { it.number },
        )
    }

    private fun JsonObject.toSection(index: Int) = LibriVoxSection(
        id = this["id"]!!.firstString().orEmpty(),
        number = this["section_number"]?.asInt() ?: index,
        title = this["title"]?.firstString().orEmpty().trim(),
        listenUrl = this["listen_url"]?.firstString().orEmpty(),
        playtimeMs = (this["playtime"]?.asLong() ?: 0L) * 1000,
        readers = (this["readers"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("display_name")?.firstString()?.trim()?.ifEmpty { null } },
    )

    companion object {
        private val ID = Regex("[0-9]+")
        private val LEADING_ARTICLE = Regex("^(the|a|an)\\s+", RegexOption.IGNORE_CASE)
        private val SPACES = Regex("\\s+")
        private const val SEARCH_FIELDS = "{id,title,authors,language,totaltime,totaltimesecs,num_sections}"
        private const val NO_RESULTS = "could not be found"
        private const val NOT_FOUND = "Not found on LibriVox"
        private const val BUSY = "LibriVox is busy right now. Try again in a minute."

        fun isValidId(id: String): Boolean = ID.matches(id)

        /** `https://www.archive.org/details/<identifier>` → the identifier, if it's a valid one on an archive.org host. */
        internal fun archiveIdentifierOf(url: String): String? {
            val parsed = url.trim().toHttpUrlOrNull() ?: return null
            val host = parsed.host
            if (host != "archive.org" && !host.endsWith(".archive.org")) return null
            val segments = parsed.pathSegments.filter { it.isNotEmpty() }
            if (segments.size != 2 || segments[0] != "details") return null
            return segments[1].takeIf(ArchiveQuery::isValidIdentifier)
        }

        /** "4:09:29" → 14 969; "" or anything else → null. */
        private fun clockSeconds(text: String): Long? {
            val parts = text.trim().split(':')
            if (parts.size !in 2..3 || parts.any { p -> p.isEmpty() || !p.all(Char::isDigit) }) return null
            return parts.fold(0L) { total, part -> total * 60 + part.toLong() }.takeIf { it > 0 }
        }
    }
}
