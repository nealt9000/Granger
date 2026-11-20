package app.granger.core.text

/**
 * How a page's `src` or `href` becomes a ZIM path; images (`ArticleThumbnails`) and links (`LeadLinks`) share it.
 * Shared since Plan I3: what `java.net.URI` did on Android, written out (`ZimPathsJvmTest` holds the two equal).
 */
object ZimPaths {
    /**
     * [ref] resolved against [articlePath]: `../I/x.jpg` from `A/Cat` and `I/x.jpg` from `Cat` both give `I/x.jpg`;
     * percent-escapes are decoded (`x%20y` → `x y`). A query or fragment is dropped.
     */
    fun resolve(ref: String, articlePath: String): String {
        val clean = ref.substringBefore('#').substringBefore('?')
        val path = resolved(clean.replace(" ", "%20"), articlePath) ?: clean.removePrefix("./")
        return path.replace("%20", " ")
    }

    /** The resolved path, decoded and without its leading slash; null where `URI` would refuse [ref]. */
    private fun resolved(ref: String, articlePath: String): String? {
        if (!parses(ref)) return null
        // "Halo:_Reach" would parse as a scheme; a page's link is always relative, so read it as "./Halo:_Reach".
        val rel = if (hasScheme(ref)) "./$ref" else ref
        val raw = when {
            rel.startsWith("//") -> rel.drop(2).let { a -> a.indexOf('/').let { if (it < 0) "" else a.substring(it) } }
            rel.startsWith("/") -> rel
            else -> normalize(encode("/$articlePath").substringBeforeLast('/') + "/" + rel)
        }
        return decode(raw)?.removePrefix("/")
    }

    private fun hasScheme(s: String): Boolean {
        val colon = s.indexOf(':')
        if (colon <= 0) return false
        val stop = s.indexOfAny(charArrayOf('/', '?', '#'))
        if (stop in 0 until colon) return false
        return s[0].isAsciiLetter() && (1 until colon).all { s[it].isAsciiLetter() || s[it].isAsciiDigitChar() || s[it] in "+-." }
    }

    /**
     * `URI.normalize` on an absolute path: redundant slashes go, "." goes, and "x/.." goes where x isn't itself ".."
     * (a ".." with nothing before it stays). A path that ended on a removed segment keeps its trailing slash.
     */
    private fun normalize(path: String): String {
        val segs = path.split('/').filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var trailing = path.endsWith("/")
        for ((i, seg) in segs.withIndex()) {
            val last = i == segs.lastIndex
            when {
                seg == "." -> if (last) trailing = true
                seg == ".." && out.isNotEmpty() && out.last() != ".." -> { out.removeAt(out.lastIndex); if (last) trailing = true }
                else -> { out += seg; if (last) trailing = path.endsWith("/") }
            }
        }
        if (out.isEmpty()) return "/"
        return "/" + out.joinToString("/") + if (trailing) "/" else ""
    }

    /** Whether `URI(ref)` parses: every escape is %XX, and no character URIs forbid (non-ASCII letters are allowed). */
    private fun parses(ref: String): Boolean {
        // A colon in the first segment makes a scheme; one that isn't a valid scheme ("1:2") is refused.
        val colon = ref.indexOf(':')
        if (colon >= 0 && ref.indexOf('/').let { it < 0 || colon < it } && !hasScheme(ref)) return false
        var i = 0
        while (i < ref.length) {
            val c = ref[i]
            when {
                c == '%' -> {
                    if (i + 2 >= ref.length || !ref[i + 1].isHex() || !ref[i + 2].isHex()) return false
                    i += 2
                }
                c.code < 0x80 && (c.code <= 0x20 || c.code == 0x7f || c in "\"<>\\^`{|}[]") -> return false
                c.code >= 0x80 && (c.isISOControl() || c.isWhitespace()) -> return false
            }
            i++
        }
        return true
    }

    /** The multi-argument `URI` constructor's quoting of a path: characters a path can't hold become escapes. */
    private fun encode(path: String): String = buildString {
        for (c in path) {
            if (c.code < 0x80 && (c.code <= 0x20 || c.code == 0x7f || c in "\"<>\\^`{|}[]%#?")) {
                append('%'); append(HEX[c.code shr 4]); append(HEX[c.code and 15])
            } else append(c)
        }
    }

    private fun decode(s: String): String? = percentDecoded(s)

    private fun Char.isHex() = isHexDigit()
    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isAsciiDigitChar() = this in '0'..'9'
    private const val HEX = "0123456789ABCDEF"
}

/**
 * %XX escapes decoded as UTF-8 (`+` stays `+`), as `URI.getPath` does, and `URLDecoder` on text whose "+" was escaped
 * first; null for a broken escape.
 */
fun percentDecoded(s: String): String? {
    if ('%' !in s) return s
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
        if (s[i] != '%') { out.append(s[i]); i++; continue }
        val bytes = ArrayList<Byte>()
        while (i < s.length && s[i] == '%') {
            if (i + 2 >= s.length || !s[i + 1].isHexDigit() || !s[i + 2].isHexDigit()) return null
            bytes += s.substring(i + 1, i + 3).toInt(16).toByte()
            i += 3
        }
        out.append(bytes.toByteArray().decodeToString())
    }
    return out.toString()
}

private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
