package app.granger.core.online

/**
 * The URL encoding the online code used from the JVM (`URLEncoder` / `URLDecoder`, OkHttp's query builder), in
 * common Kotlin (Plan I1).
 */
internal object UrlCodec {
    private const val HEX = "0123456789ABCDEF"

    /** `URLEncoder.encode(s, "UTF-8")`: letters, digits and `.-*_` kept, a space as `+`, the rest `%XX`. */
    fun formEncode(s: String): String = buildString {
        for (byte in s.encodeToByteArray()) {
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            when {
                b < 0x80 && (c.isLetterOrDigit() || c in ".-*_") -> append(c)
                c == ' ' -> append('+')
                else -> append('%').append(HEX[b shr 4]).append(HEX[b and 0xF])
            }
        }
    }

    /** `URLDecoder.decode(s, "UTF-8")`: `+` is a space, `%XX` a byte; throws on a broken escape, as it does. */
    fun formDecode(s: String): String = percentDecode(s, plusIsSpace = true)

    /** A path segment's escapes undone (`+` stays a plus), as OkHttp's `pathSegments` give them. */
    fun pathDecode(s: String): String = runCatching { percentDecode(s, plusIsSpace = false) }.getOrDefault(s)

    private fun percentDecode(s: String, plusIsSpace: Boolean): String {
        if ('%' !in s && (!plusIsSpace || '+' !in s)) return s
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' -> {
                    if (i + 2 >= s.length) throw IllegalArgumentException("incomplete escape")
                    val hi = s[i + 1].digitToIntOrNull(16) ?: throw IllegalArgumentException("bad escape")
                    val lo = s[i + 2].digitToIntOrNull(16) ?: throw IllegalArgumentException("bad escape")
                    out += (hi * 16 + lo).toByte()
                    i += 3
                }
                c == '+' && plusIsSpace -> { out += ' '.code.toByte(); i++ }
                else -> {
                    val n = if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
                    for (b in s.substring(i, i + n).encodeToByteArray()) out += b
                    i += n
                }
            }
        }
        return out.toByteArray().decodeToString()
    }

    /** A query value as OkHttp's `addQueryParameter` writes it (space as `%20`, reserved characters escaped). */
    fun queryEncode(s: String): String = buildString {
        for (byte in s.encodeToByteArray()) {
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (b < 0x80 && (c.isLetterOrDigit() || c in "-._~!$'()*,;:@/?")) append(c)
            else append('%').append(HEX[b shr 4]).append(HEX[b and 0xF])
        }
    }

    /** [base] with [params] as its query, in order. */
    fun withQuery(base: String, vararg params: Pair<String, String>): String =
        base + "?" + params.joinToString("&") { (k, v) -> queryEncode(k) + "=" + queryEncode(v) }

    /** The host of an `https://host/…` URL; null for anything else. */
    fun hostOf(url: String): String? =
        url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
            .substringBefore(':').lowercase().takeIf { url.contains("://") && it.isNotEmpty() }

    /** An URL's path segments, decoded. */
    fun pathSegments(url: String): List<String> =
        url.substringAfter("://", "").substringBefore('?').substringBefore('#').substringAfter('/', "")
            .split('/').map(::pathDecode)
}
