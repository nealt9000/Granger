package app.granger.reader

/** UTF-8, as nearly every pack serves; a page that names Latin-1 (or Windows-1252) is read byte for byte. */
internal actual fun decodeHtml(bytes: ByteArray, mimeType: String): String {
    val name = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(mimeType)?.groupValues?.get(1)?.trim('"')?.lowercase()
    return if (name == "iso-8859-1" || name == "latin1" || name == "windows-1252") {
        buildString(bytes.size) { bytes.forEach { append((it.toInt() and 0xFF).toChar()) } }
    } else {
        bytes.decodeToString()
    }
}
