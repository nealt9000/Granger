package app.granger.reader

import java.nio.charset.Charset

internal actual fun decodeHtml(bytes: ByteArray, mimeType: String): String {
    val name = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(mimeType)?.groupValues?.get(1)
    val charset = name?.let { runCatching { Charset.forName(it.trim('"')) }.getOrNull() } ?: Charsets.UTF_8
    return String(bytes, charset)
}
