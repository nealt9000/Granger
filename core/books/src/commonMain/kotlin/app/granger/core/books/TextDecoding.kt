package app.granger.core.books

object TextDecoding {
    /** A text file's bytes as text: by its byte-order mark, else strict UTF-8, else Windows-1252. */
    fun decode(bytes: ByteArray): String = decodeText(bytes)
}
