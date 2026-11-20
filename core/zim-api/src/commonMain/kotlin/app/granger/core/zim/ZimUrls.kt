package app.granger.core.zim

/** A reader URL split into its pack and the ZIM entry path inside it. `fragment` is percent-decoded (it is encoded in [forEntry]). */
data class ZimUrl(val packId: String, val path: String, val fragment: String? = null)

/**
 * Reader URLs look like `https://zim.granger.invalid/<packId>/<path>`. The `.invalid` TLD never resolves,
 * so a request that escapes interception cannot reach the network. Path segments are percent-encoded;
 * `+` is always a literal plus (ZIM paths such as `C++` contain it), never a space.
 */
object ZimUrls {
    const val HOST = "zim.granger.invalid"
    /** Android's base. The iPhone loads pages through a custom scheme instead (`WKWebView` can't intercept https). */
    const val ANDROID_BASE = "https://$HOST/"

    /**
     * Where reader URLs start: [ANDROID_BASE] unless the app sets another once at start (the iPhone sets
     * `granger-zim://zim/`; Plan I2 Task 2). Ends with a slash.
     */
    var base: String = ANDROID_BASE
        set(value) {
            require(value.endsWith("/")) { "the base ends with a slash" }
            field = value
        }

    private val PREFIX: String get() = base
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val HEX = "0123456789ABCDEF"

    fun forEntry(packId: String, path: String, fragment: String? = null): String =
        PREFIX + encode(packId) + "/" + path.split('/').joinToString("/") { encode(it) } +
            (fragment?.let { "#" + encode(it) } ?: "")

    fun parse(url: String): ZimUrl? {
        if (!url.startsWith(PREFIX, ignoreCase = true)) return null
        val afterHost = url.substring(PREFIX.length)
        val fragment = afterHost.substringAfter('#', "").ifEmpty { null }?.let(::decode)
        val beforeQuery = afterHost.substringBefore('#').substringBefore('?')
        val slash = beforeQuery.indexOf('/')
        if (slash <= 0) return null
        return ZimUrl(decode(beforeQuery.substring(0, slash)), decode(beforeQuery.substring(slash + 1)), fragment)
    }

    private fun encode(segment: String): String = buildString {
        for (byte in segment.encodeToByteArray()) {
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
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length) {
                val hi = s[i + 1].digitToIntOrNull(16) ?: -1
                val lo = s[i + 2].digitToIntOrNull(16) ?: -1
                if (hi >= 0 && lo >= 0) {
                    out += (hi * 16 + lo).toByte()
                    i += 3
                    continue
                }
            }
            // One character (a surrogate pair kept whole) as its UTF-8 bytes.
            val n = if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
            for (b in s.substring(i, i + n).encodeToByteArray()) out += b
            i += n
        }
        return out.toByteArray().decodeToString()
    }
}
