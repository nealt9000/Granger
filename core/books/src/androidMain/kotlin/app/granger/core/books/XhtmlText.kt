package app.granger.core.books

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** Reads chapters out of an EPUB and chunks them with [XhtmlChunks]. */
object XhtmlText {
    /** Empty for a missing, corrupt or oversized resource. */
    fun chunks(zip: ZipFile, href: String): List<TextChunk> {
        val entry = zip.getEntry(href) ?: return emptyList()
        return try {
            XhtmlChunks.of(href, readCapped(zip, entry, XHTML_LIMIT))
        } catch (_: IOException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    /** Chunks of every readable spine resource; missing or corrupt ones are skipped. */
    fun all(file: File, spine: List<String>): Sequence<TextChunk> = sequence {
        val zip = try { ZipFile(file) } catch (_: IOException) { null } ?: return@sequence
        zip.use { for (href in spine) yieldAll(chunks(it, href)) }
    }
}
