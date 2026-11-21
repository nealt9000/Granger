package app.granger.librivox

// Shared since Plan I6 Task 3: a LibriVox book's manifest (MediaDownloads) carries plain text on both phones.

private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")

private val NAMED = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "sbquo" to "‚", "bdquo" to "„",
    "ndash" to "–", "mdash" to "—", "hellip" to "…", "laquo" to "«", "raquo" to "»",
    "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "middot" to "·", "bull" to "•",
    "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "euml" to "ë", "aacute" to "á", "agrave" to "à", "acirc" to "â",
    "auml" to "ä", "aring" to "å", "ccedil" to "ç", "iacute" to "í", "icirc" to "î", "iuml" to "ï", "oacute" to "ó",
    "ocirc" to "ô", "ouml" to "ö", "uacute" to "ú", "ucirc" to "û", "uuml" to "ü", "ntilde" to "ñ", "szlig" to "ß",
    "Eacute" to "É", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "aelig" to "æ", "oelig" to "œ",
)

/**
 * LibriVox text with its HTML entities decoded, once ("&amp;lt;" is "&lt;"). The client strips the tags but not the
 * entities. An unknown name, or a number that isn't a character, stays as written.
 */
fun decodeEntities(text: String): String {
    if ('&' !in text) return text
    return ENTITY.replace(text) { match ->
        val body = match.groupValues[1]
        val decoded = when {
            body.startsWith("#x") || body.startsWith("#X") -> body.drop(2).toIntOrNull(16)?.let(::codePoint)
            body.startsWith("#") -> body.drop(1).toIntOrNull()?.let(::codePoint)
            else -> NAMED[body]
        }
        decoded ?: match.value
    }
}

private fun codePoint(cp: Int): String? = when {
    cp <= 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF -> null
    cp < 0x10000 -> cp.toChar().toString()
    else -> {
        val v = cp - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }
}
