package app.granger.core.books

import kotlinx.io.IOException

/** Reads chapters out of an EPUB and chunks them with [XhtmlChunks]. */
object XhtmlText {
    /** Empty for a missing, corrupt or oversized resource. */
    fun chunks(zip: BookZip, href: String): List<TextChunk> = try {
        zip.readCapped(href, XHTML_LIMIT)?.let { XhtmlChunks.of(href, it) }.orEmpty()
    } catch (_: IOException) {
        emptyList()
    } catch (_: RuntimeException) {
        emptyList()
    }

    /** Chunks of every readable spine resource of the EPUB at [path]; missing or corrupt ones are skipped. */
    fun all(path: String, spine: List<String>): Sequence<TextChunk> = sequence {
        val zip = try { openBookZip(path) } catch (_: IOException) { null } ?: return@sequence
        zip.use { for (href in spine) yieldAll(chunks(it, href)) }
    }
}
