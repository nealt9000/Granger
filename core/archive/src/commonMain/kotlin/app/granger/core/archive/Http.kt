package app.granger.core.archive

/** A finished GET: its status and body, whatever the status. */
class HttpReply(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

/** No answer at all (no signal, a refused connection, the call's time ran out), after the one retry. */
class Unreachable(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One GET with Granger's User-Agent, shared by the archive.org and LibriVox clients (Plan I5: common to both phones).
 * Android's is the app's `OkHttpClient` as before ([okHttpArchiveHttp]: a new connection on a failure, 25 s a call);
 * the iPhone's is Ktor on Darwin. A reply comes back whatever its status, body included (LibriVox's "no results" is a
 * 404 with a body); no answer at all throws [Unreachable].
 */
fun interface ArchiveHttp {
    suspend fun get(url: String, userAgent: String): HttpReply
}

/** [ArchiveHttp.get], with [Unreachable] turned into the caller's own error. */
internal suspend fun ArchiveHttp.getText(url: String, userAgent: String, unreachable: () -> Exception): HttpReply =
    try {
        get(url, userAgent)
    } catch (e: Unreachable) {
        throw unreachable()
    }

/**
 * A URL with query parameters, encoded as OkHttp's `HttpUrl.Builder.addQueryParameter` encodes them, so both phones
 * send the same query strings Android sent before Plan I5.
 */
internal fun urlWithQuery(base: String, params: List<Pair<String, String>>): String =
    if (params.isEmpty()) base
    else base + "?" + params.joinToString("&") { (k, v) -> encodeQueryComponent(k) + "=" + encodeQueryComponent(v) }

/** OkHttp's QUERY_COMPONENT_ENCODE_SET, with `+` as `%2B` (plusIsSpace) and `%` always encoded. */
private const val QUERY_COMPONENT_ENCODE_SET = " !\"#$&'(),/:;<=>?@[]\\^`{|}~"

internal fun encodeQueryComponent(text: String): String {
    val out = StringBuilder()
    for (b in text.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (c < 0x20 || c >= 0x7F || ch in QUERY_COMPONENT_ENCODE_SET || ch == '%' || ch == '+') out.append(percent(c))
        else out.append(ch)
    }
    return out.toString()
}

/** `%XX`, upper-case hex. */
internal fun percent(byte: Int): String {
    val hex = "0123456789ABCDEF"
    return "%" + hex[byte shr 4] + hex[byte and 0xF]
}

/**
 * The parts of an http(s) URL the clients read (in place of OkHttp's `toHttpUrlOrNull`): its host, lower-cased, and its
 * path segments, percent-decoded ("https://x/a/b/" has "a", "b" and ""). Null for anything that isn't an http(s) URL
 * with a host.
 */
internal class WebUrl(val host: String, val pathSegments: List<String>) {
    companion object {
        private val URL = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://(?:[^@/?#\\]*@)?(\[[^\]]*]|[^:/?#\\]*)(?::(\d*))?([^?#]*)""")

        fun parse(text: String): WebUrl? {
            val m = URL.find(text) ?: return null
            val scheme = m.groupValues[1].lowercase()
            if (scheme != "http" && scheme != "https") return null
            val host = percentDecode(m.groupValues[2]).lowercase().takeIf { it.isNotEmpty() && it.none { c -> c.isWhitespace() } } ?: return null
            val path = m.groupValues[4].replace('\\', '/').ifEmpty { "/" }
            // "." and ".." resolve as OkHttp resolves them; a trailing one leaves an empty last segment.
            val raw = path.removePrefix("/").split('/')
            val segments = mutableListOf<String>()
            for ((i, s) in raw.withIndex()) {
                val last = i == raw.lastIndex
                when (s.lowercase()) {
                    ".", "%2e" -> if (last) segments += ""
                    "..", "%2e%2e", ".%2e", "%2e." -> {
                        segments.removeLastOrNull()
                        if (last) segments += ""
                    }
                    else -> segments += percentDecode(s)
                }
            }
            return WebUrl(host, segments.ifEmpty { listOf("") })
        }

        private fun percentDecode(text: String): String {
            if ('%' !in text) return text
            val bytes = ArrayList<Byte>(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%' && i + 2 <= text.lastIndex) {
                    val hi = text[i + 1].digitToIntOrNull(16)
                    val lo = text[i + 2].digitToIntOrNull(16)
                    if (hi != null && lo != null) {
                        bytes += ((hi shl 4) + lo).toByte()
                        i += 3
                        continue
                    }
                }
                bytes += c.toString().encodeToByteArray().toList()
                i++
            }
            return bytes.toByteArray().decodeToString()
        }
    }
}

/** Now, in milliseconds since 1970. */
internal expect fun nowMillis(): Long

/** This year, in the phone's time zone. */
internal expect fun currentYear(): Int

/** A warning in the phone's log, under "GrangerHttp". */
internal expect fun logWarn(message: String)
