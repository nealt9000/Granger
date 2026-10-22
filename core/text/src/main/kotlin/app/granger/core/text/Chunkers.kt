package app.granger.core.text

import java.security.MessageDigest

/** What pack-time vectors are tied to: the chunkers' output version and each chunk's text hash. */
object Chunkers {
    /** Bump on any change to the output of [app.granger.core.books.XhtmlChunks] or [app.granger.core.research.HtmlPassages]. */
    const val VERSION = 1

    /**
     * Android's (ICU's) `\s`, spelled out: `[\t\n\f\r\p{Z}]`. The desktop JVM's `\s` is ASCII-only, so the chunkers use
     * this class instead, and the Mac's chunks (`tools/chunker`) match the phone's on thin and other Unicode spaces.
     */
    const val SPACE = "\\t\\n\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"

    /** The first 8 bytes of the SHA-1 of [text]'s UTF-8, big-endian, as 16 lowercase hex digits. */
    fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.copyOf(8).joinToString("") { "%02x".format(it) }
    }
}
