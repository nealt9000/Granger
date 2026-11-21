package app.granger.core.books

import kotlinx.io.IOException

/**
 * An EPUB's spine and resources, read one at a time (Plan I5 Task 8: the iPhone's read-aloud source walks a book's
 * XHTML itself, where Android's walks Readium Kotlin's content iterator). Null for a resource that's missing, corrupt or
 * over the XHTML cap; [spine] is null for a file that isn't a readable EPUB.
 */
object EpubResources {
    fun spine(path: String): List<String>? = (EpubInspector.inspect(path) as? Inspection.Ok)?.meta?.spine

    fun read(path: String, href: String): ByteArray? = try {
        openBookZip(path).use { it.readCapped(href, XHTML_LIMIT) }
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}
