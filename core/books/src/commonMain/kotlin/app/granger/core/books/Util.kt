package app.granger.core.books

internal const val MIMETYPE_LIMIT = 64
internal const val XML_LIMIT = 4 * 1024 * 1024
internal const val COVER_LIMIT = 10 * 1024 * 1024
internal const val XHTML_LIMIT = 16 * 1024 * 1024

internal fun esc(s: String): String {
    val sb = StringBuilder(s.length + 16)
    for (c in s) when (c) {
        '&' -> sb.append("&amp;")
        '<' -> sb.append("&lt;")
        '>' -> sb.append("&gt;")
        '"' -> sb.append("&quot;")
        else -> sb.append(c)
    }
    return sb.toString()
}

/** Removes characters that are illegal in XML 1.0: most C0 controls, U+FFFE/U+FFFF and lone surrogates. */
internal fun stripInvalidXml(s: String): String {
    var clean = true
    for (c in s) if (isIllegal(c)) { clean = false; break }
    if (clean && s.none { it.isSurrogate() }) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                sb.append(c).append(s[i + 1]); i++
            }
            c.isSurrogate() || isIllegal(c) -> {}
            else -> sb.append(c)
        }
        i++
    }
    return sb.toString()
}

private fun isIllegal(c: Char) = (c < ' ' && c != '\t' && c != '\n' && c != '\r') || c == '￾' || c == '￿'

/**
 * An XML resource's bytes as text, as jsoup reads them when told UTF-8: a byte-order mark (UTF-32, UTF-16 or UTF-8)
 * overrides that and is dropped.
 */
internal fun xmlText(bytes: ByteArray): String {
    fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
    return when {
        at(0) == 0x00 && at(1) == 0x00 && at(2) == 0xFE && at(3) == 0xFF -> utf32(bytes, bigEndian = true)
        at(0) == 0xFF && at(1) == 0xFE && at(2) == 0x00 && at(3) == 0x00 -> utf32(bytes, bigEndian = false)
        at(0) == 0xFE && at(1) == 0xFF -> utf16(bytes, 2, bigEndian = true)
        at(0) == 0xFF && at(1) == 0xFE -> utf16(bytes, 2, bigEndian = false)
        at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> bytes.decodeToString(3, bytes.size)
        else -> bytes.decodeToString()
    }
}

/** UTF-16 from [from] on, a byte pair at a time; a lone last byte is dropped. */
internal fun utf16(bytes: ByteArray, from: Int, bigEndian: Boolean): String {
    val sb = StringBuilder(bytes.size / 2)
    var i = from
    while (i + 1 < bytes.size) {
        val a = bytes[i].toInt() and 0xFF
        val b = bytes[i + 1].toInt() and 0xFF
        sb.append((if (bigEndian) (a shl 8) or b else (b shl 8) or a).toChar())
        i += 2
    }
    return sb.toString()
}

private fun utf32(bytes: ByteArray, bigEndian: Boolean): String {
    val sb = StringBuilder(bytes.size / 4)
    var i = 4
    while (i + 3 < bytes.size) {
        val b = IntArray(4) { bytes[i + it].toInt() and 0xFF }
        val cp = if (bigEndian) (b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3] else (b[3] shl 24) or (b[2] shl 16) or (b[1] shl 8) or b[0]
        when {
            cp < 0 || cp > 0x10FFFF -> sb.append('�')
            cp >= 0x10000 -> {
                val v = cp - 0x10000
                sb.append((0xD800 + (v shr 10)).toChar()).append((0xDC00 + (v and 0x3FF)).toChar())
            }
            else -> sb.append(cp.toChar())
        }
        i += 4
    }
    return sb.toString()
}
