package app.granger.core.archive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class ArchiveOrgClient(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val baseUrl: String = "https://archive.org",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String, page: Int, rows: Int = 50, sortByDownloads: Boolean = true): SearchPage {
        val url = "$baseUrl/advancedsearch.php".toHttpUrl().newBuilder().apply {
            addQueryParameter("q", query)
            for (field in SEARCH_FIELDS) addQueryParameter("fl[]", field)
            if (sortByDownloads) addQueryParameter("sort[]", "downloads desc")
            addQueryParameter("rows", rows.toString())
            addQueryParameter("page", page.toString())
            addQueryParameter("output", "json")
        }.build()
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
        if (!ArchiveQuery.isValidIdentifier(identifier)) throw ArchiveError("Not found on archive.org")
        val root = fetch("$baseUrl/metadata/$identifier".toHttpUrl())
        if (root.isEmpty() || root["is_dark"]?.asBool() == true) throw ArchiveError("Not found on archive.org")
        return parse {
            val meta = root["metadata"]?.jsonObject ?: throw ArchiveError("Not found on archive.org")
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

    private suspend fun fetch(url: HttpUrl): JsonObject {
        val reply = client.getText(url, userAgent) { ArchiveError("Couldn't reach archive.org") }
        if (!reply.isSuccessful) throw ArchiveError("archive.org answered ${reply.code}")
        return parse { json.parseToJsonElement(reply.body).jsonObject }
    }

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: ArchiveError) {
        throw e
    } catch (e: Exception) {
        throw ArchiveError("archive.org sent something Granger couldn't read")
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
        val seconds = parts.last().toBigDecimalOrNull()?.takeIf { it.signum() >= 0 } ?: return null
        var total = seconds
        var unit = 60L
        for (part in parts.dropLast(1).reversed()) {
            if (!part.all(Char::isDigit)) return null
            total += part.toBigDecimal() * unit.toBigDecimal()
            unit *= 60
        }
        return total.movePointRight(3).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
    }

    private fun encodeSegment(segment: String): String {
        val out = StringBuilder()
        for (b in segment.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c < 128 && (c.toChar().isLetterOrDigit() || c.toChar() in "-._~")) out.append(c.toChar())
            else out.append('%').append("%02X".format(c))
        }
        return out.toString()
    }

    private companion object {
        val SEARCH_FIELDS = listOf("identifier", "title", "creator", "year", "format", "access-restricted-item", "downloads", "mediatype")
        val TRACK = Regex("(\\d{1,4})(?:/\\d+)?")
        val YEAR = Regex("(?<!\\d)\\d{4}(?!\\d)")
    }
}
