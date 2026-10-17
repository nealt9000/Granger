package app.granger.core.books

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal const val MIMETYPE_LIMIT = 64
internal const val XML_LIMIT = 4 * 1024 * 1024
internal const val COVER_LIMIT = 10 * 1024 * 1024
internal const val XHTML_LIMIT = 16 * 1024 * 1024

/**
 * Reads an entry, enforcing [limit] on the bytes actually inflated (the declared size is not trusted).
 * Throws [IOException] when the entry is over the limit or corrupt.
 */
internal fun readCapped(zip: ZipFile, entry: ZipEntry, limit: Int): ByteArray {
    zip.getInputStream(entry).use { input ->
        val out = ByteArrayOutputStream(minOf(limit.toLong(), maxOf(entry.size, 0L) + 1).toInt().coerceAtLeast(32))
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > limit) throw IOException("Entry ${entry.name} is larger than $limit bytes")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

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

/** Cuts [text] into pieces of at most [max] characters, preferring spaces and never splitting a surrogate pair. */
internal fun cutAtSpaces(text: String, max: Int): List<String> {
    if (text.length <= max) return listOf(text)
    val out = ArrayList<String>()
    var start = 0
    while (text.length - start > max) {
        var end = text.lastIndexOf(' ', start + max)
        if (end <= start + max / 2) {
            end = start + max
            if (Character.isHighSurrogate(text[end - 1])) end--
        }
        out.add(text.substring(start, end).trim())
        start = end
        while (start < text.length && text[start] == ' ') start++
    }
    if (start < text.length) out.add(text.substring(start))
    return out.filter { it.isNotEmpty() }
}

/** Removes characters that are illegal in XML 1.0: most C0 controls, U+FFFE/U+FFFF and lone surrogates. */
internal fun stripInvalidXml(s: String): String {
    var clean = true
    for (c in s) if (isIllegal(c)) { clean = false; break }
    if (clean && s.none { Character.isSurrogate(it) }) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                sb.append(c).append(s[i + 1]); i++
            }
            Character.isSurrogate(c) || isIllegal(c) -> {}
            else -> sb.append(c)
        }
        i++
    }
    return sb.toString()
}

private fun isIllegal(c: Char) = (c < ' ' && c != '\t' && c != '\n' && c != '\r') || c == '￾' || c == '￿'
