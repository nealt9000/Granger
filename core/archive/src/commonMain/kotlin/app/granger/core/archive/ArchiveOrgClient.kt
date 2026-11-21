package app.granger.core.archive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * archive.org's search and item metadata (shared since Plan I5). Android builds it on the app's `OkHttpClient`
 * (`ArchiveOrgClient(http, userAgent)`, androidMain); the iPhone on Ktor ([ArchiveHttp]).
 */
class ArchiveOrgClient(
    private val http: ArchiveHttp,
    private val userAgent: String,
    private val baseUrl: String = "https://archive.org",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String, page: Int, rows: Int = 50, sortByDownloads: Boolean = true): SearchPage {
        val url = urlWithQuery(
            "$baseUrl/advancedsearch.php",
            buildList {
                add("q" to query)
                for (field in SEARCH_FIELDS) add("fl[]" to field)
                if (sortByDownloads) add("sort[]" to "downloads desc")
                add("rows" to rows.toString())
                add("page" to page.toString())
                add("output" to "json")
            },
        )
        val root = fetch(url)
        return parse {
            val response = root["response"]!!.jsonObject
            SearchPage(
                total = response["numFound"]?.asInt() ?: 0,
                docs = (response["docs"] as? JsonArray).orEmpty().map { it.jsonObject.toDoc() },
            )
        }
    }

    suspend fun item(identifier: String): ArchiveItem {
        if (!ArchiveQuery.isValidIdentifier(identifier)) throw ArchiveError(ArchiveProblem.NOT_FOUND, "Not found on archive.org")
        val root = fetch("$baseUrl/metadata/$identifier")
        if (root.isEmpty() || root["is_dark"]?.asBool() == true) throw ArchiveError(ArchiveProblem.NOT_FOUND, "Not found on archive.org")
        return parse {
            val meta = root["metadata"]?.jsonObject ?: throw ArchiveError(ArchiveProblem.NOT_FOUND, "Not found on archive.org")
            val date = meta["date"]?.firstString().orEmpty()
            ArchiveItem(
                identifier = meta["identifier"]?.firstString() ?: identifier,
                title = meta["title"]?.firstString().orEmpty(),
                creator = meta["creator"]?.allStrings().orEmpty().joinToString(", "),
                date = date,
                year = yearOf(date) ?: meta["year"]?.firstString()?.let(::yearOf),
                subjects = meta["subject"]?.allStrings().orEmpty(),
                description = stripHtml(meta["description"]?.firstString().orEmpty()),
                language = meta["language"]?.firstString().orEmpty(),
                licenseUrl = meta["licenseurl"]?.firstString().orEmpty(),
                restricted = meta["access-restricted-item"]?.asBool() == true,
                files = (root["files"] as? JsonArray).orEmpty().map { it.jsonObject.toFile() },
                mediatype = meta["mediatype"]?.firstString().orEmpty(),
                collections = meta["collection"]?.allStrings().orEmpty(),
            )
        }
    }

    fun downloadUrl(identifier: String, fileName: String): String =
        "$baseUrl/download/$identifier/" + fileName.split('/').joinToString("/") { encodeSegment(it) }

    fun detailsUrl(identifier: String): String = "$baseUrl/details/$identifier"

    private suspend fun fetch(url: String): JsonObject {
        val reply = http.getText(url, userAgent) { ArchiveError(ArchiveProblem.UNREACHABLE, "Couldn't reach archive.org") }
        if (!reply.isSuccessful) throw ArchiveError(ArchiveProblem.ANSWERED, "archive.org answered ${reply.code}", reply.code)
        return parse { json.parseToJsonElement(reply.body).jsonObject }
    }

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: ArchiveError) {
        throw e
    } catch (e: Exception) {
        throw ArchiveError(ArchiveProblem.UNREADABLE, "archive.org sent something Granger couldn't read")
    }

    private fun JsonObject.toDoc() = ArchiveDoc(
        identifier = this["identifier"]!!.firstString().orEmpty(),
        title = this["title"]?.firstString().orEmpty(),
        creator = this["creator"]?.allStrings().orEmpty().joinToString(", "),
        year = this["year"]?.asInt(),
        formats = this["format"]?.allStrings().orEmpty(),
        restricted = this["access-restricted-item"]?.asBool() == true,
        downloads = this["downloads"]?.asLong() ?: 0L,
        mediatype = this["mediatype"]?.firstString().orEmpty(),
    )

    private fun JsonObject.toFile() = ArchiveFile(
        name = this["name"]!!.firstString().orEmpty(),
        format = this["format"]?.firstString().orEmpty(),
        sizeBytes = this["size"]?.asLong(),
        sha1 = this["sha1"]?.firstString()?.ifBlank { null },
        md5 = this["md5"]?.firstString()?.ifBlank { null },
        source = this["source"]?.firstString().orEmpty(),
        isPrivate = this["private"]?.asBool() == true,
        title = this["title"]?.firstString().orEmpty(),
        album = this["album"]?.firstString().orEmpty(),
        creator = this["creator"]?.firstString().orEmpty(),
        track = this["track"]?.firstString()?.let(::trackOf),
        lengthMs = this["length"]?.firstString()?.let(::lengthMsOf),
    )

    /** The first run of exactly four digits, so "c1890", "[1890]" and "May 12, 1890" all give 1890. */
    private fun yearOf(date: String): Int? = YEAR.find(date)?.value?.toInt()

    /** "01" → 1, "7" → 7, "1/13" → 1; anything else → null. */
    private fun trackOf(track: String): Int? = TRACK.matchEntire(track.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** "373.16" → 373 160, "19:32" → 1 172 000, "1:02:03" → 3 723 000; anything else → null. */
    private fun lengthMsOf(length: String): Long? {
        val parts = length.trim().split(':')
        if (parts.size > 3 || parts.any { it.isEmpty() }) return null
        var total = millisOf(parts.last()) ?: return null
        var unit = 60_000L
        for (part in parts.dropLast(1).reversed()) {
            if (!part.all(Char::isDigit)) return null
            total += part.toLong() * unit
            unit *= 60
        }
        return total
    }

    private fun encodeSegment(segment: String): String {
        val out = StringBuilder()
        for (b in segment.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            if (c < 128 && (c.toChar().isLetterOrDigit() || c.toChar() in "-._~")) out.append(c.toChar())
            else out.append(percent(c))
        }
        return out.toString()
    }

    private companion object {
        val SEARCH_FIELDS = listOf("identifier", "title", "creator", "year", "format", "access-restricted-item", "downloads", "mediatype")
        val TRACK = Regex("(\\d{1,4})(?:/\\d+)?")
        val YEAR = Regex("(?<!\\d)\\d{4}(?!\\d)")
    }
}

/**
 * A non-negative decimal number of seconds ("373.16", "5", ".5", "1e2") in milliseconds, half rounding up, as
 * `BigDecimal(text).movePointRight(3).setScale(0, HALF_UP)` gave it before Plan I5; null when it isn't one.
 */
internal fun millisOf(seconds: String): Long? {
    val m = DECIMAL.matchEntire(seconds) ?: return null
    val (sign, whole, fraction, exponent) = m.destructured
    if (whole.isEmpty() && fraction.isEmpty()) return null
    val digits = (whole + fraction).trimStart('0')
    if (digits.isEmpty()) return 0
    if (sign == "-") return null
    // value = digits × 10^(-scale); in ms, digits × 10^(3 - scale).
    val scale = fraction.length - (exponent.toIntOrNull() ?: if (exponent.isEmpty()) 0 else return null)
    val shift = 3 - scale
    return if (shift >= 0) {
        if (digits.length + shift > 18) null else (digits + "0".repeat(shift)).toLong()
    } else {
        val keep = digits.length + shift
        val kept = if (keep > 0) digits.substring(0, keep) else ""
        if (kept.length > 18) return null
        val firstDropped = if (keep >= 0) digits.getOrNull(keep) else null
        val base = kept.ifEmpty { "0" }.toLong()
        if (firstDropped != null && firstDropped >= '5') base + 1 else base
    }
}

private val DECIMAL = Regex("""([+-]?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?""")
