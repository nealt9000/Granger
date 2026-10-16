package app.granger.core.zim

import java.io.ByteArrayOutputStream

/** A reader URL split into its pack and the ZIM entry path inside it. `fragment` is kept raw. */
data class ZimUrl(val packId: String, val path: String, val fragment: String? = null)

/**
 * Reader URLs look like `https://zim.granger.invalid/<packId>/<path>`. The `.invalid` TLD never resolves,
 * so a request that escapes interception cannot reach the network. Path segments are percent-encoded;
 * `+` is always a literal plus (ZIM paths such as `C++` contain it), never a space.
 */
object ZimUrls {
    const val HOST = "zim.granger.invalid"
    private const val PREFIX = "https://$HOST/"
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val HEX = "0123456789ABCDEF"

    fun forEntry(packId: String, path: String, fragment: String? = null): String =
        PREFIX + encode(packId) + "/" + path.split('/').joinToString("/") { encode(it) } +
            (fragment?.let { "#$it" } ?: "")

    fun parse(url: String): ZimUrl? {
        if (!url.startsWith(PREFIX, ignoreCase = true)) return null
        val afterHost = url.substring(PREFIX.length)
        val fragment = afterHost.substringAfter('#', "").ifEmpty { null }
        val beforeQuery = afterHost.substringBefore('#').substringBefore('?')
        val slash = beforeQuery.indexOf('/')
        if (slash <= 0) return null
        return ZimUrl(decode(beforeQuery.substring(0, slash)), decode(beforeQuery.substring(slash + 1)), fragment)
    }

    private fun encode(segment: String): String = buildString {
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xFF
            if (b < 0x80 && UNRESERVED.indexOf(b.toChar()) >= 0) {
                append(b.toChar())
            } else {
                append('%').append(HEX[b shr 4]).append(HEX[b and 0xF])
            }
        }
    }

    private fun decode(s: String): String {
        if ('%' !in s) return s
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length) {
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write(hi * 16 + lo)
                    i += 3
                    continue
                }
            }
            val codePoint = s.codePointAt(i)
            out.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
            i += Character.charCount(codePoint)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
