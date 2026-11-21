package app.granger.core.library

import app.granger.core.zim.ZimArchive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/** One book in a Gutenberg pack's list: its title, author and Gutenberg id (`22636`). */
data class GutenbergListing(val title: String, val author: String, val gutenbergId: String)

/**
 * A Gutenberg pack's list of books, `full_by_title.js`: `var json_data = [[title, author, "110", id, "LCC"], …];`
 * (gutenberg2zim 3.0.1, inspected 2026-10-30). Author pages and per-shelf lists sit beside it; this one lists them all.
 * Shared by both phones (Plan I4b Task 8).
 */
object GutenbergList {
    const val PATH = "full_by_title.js"

    /** The books of [js], in its order; a malformed row is skipped, and a malformed file lists nothing. */
    fun parse(js: String): List<GutenbergListing> {
        val start = js.indexOf('[')
        val end = js.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val rows = try {
            LENIENT.parseToJsonElement(js.substring(start, end + 1)).jsonArray
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<GutenbergListing>(rows.size)
        for (element in rows) {
            val row = element as? JsonArray ?: continue
            val title = row.text(0).trim()
            val id = row.text(3).trim().removeSuffix(".0")
            if (title.isEmpty() || id.isEmpty() || !id.all(Char::isDigit)) continue
            out += GutenbergListing(title, row.text(1).trim(), id)
        }
        return out
    }

    /**
     * Where [listing]'s EPUB is in [archive]: `{title}.{id}.epub`, built from the listed title exactly (titles hold `:`,
     * `'`, `(`). If that misses (the scraper changed the title for its file name), the title suggestions are asked for
     * the book's own page, `{title}.{id}` or `{title}_cover.{id}`, and its EPUB is the page's path plus `.epub`. Null
     * when the pack has no EPUB for it.
     */
    fun epubPath(archive: ZimArchive, listing: GutenbergListing): String? {
        val id = listing.gutenbergId
        val direct = "${listing.title}.$id.epub"
        if (isEpub(archive, direct)) return direct
        val suffix = ".$id"
        return archive.suggest(listing.title, SUGGESTIONS).asSequence()
            .map { it.path }
            .filter { it.endsWith(suffix) }
            .map { it.removeSuffix(suffix).removeSuffix("_cover") + "$suffix.epub" }
            .firstOrNull { isEpub(archive, it) }
    }

    /** The pack's cover image for book [gutenbergId], by the names the scraper gives them; null when it has none. */
    fun coverPath(archive: ZimArchive, gutenbergId: String): String? =
        COVER_NAMES.asSequence().map { it.replace("{id}", gutenbergId) }.firstNotNullOfOrNull { archive.resolvePath(it) }

    /** See [GutenbergPaths.idOfPage]. */
    fun idOfPage(path: String): String? = GutenbergPaths.idOfPage(path)

    /** See [GutenbergPaths.idOfEpub]. */
    fun idOfEpub(path: String): String? = GutenbergPaths.idOfEpub(path)

    private fun isEpub(archive: ZimArchive, path: String): Boolean =
        archive.entry(path)?.mimeType?.substringBefore(';')?.trim() == EPUB_MIME

    /** Element [i] of a row as text (a number as written), "" when it's missing, null or not a value (org.json's `optString`). */
    private fun JsonArray.text(i: Int): String =
        (getOrNull(i) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content.orEmpty()

    private val LENIENT = Json { isLenient = true }

    const val EPUB_MIME = "application/epub+zip"
    private const val SUGGESTIONS = 20

    /** Seen in the packs: `{id}_cover.jpg`, `{id}_{id}-cover.png`, `{id}_new-cover.jpg`; the rest are their siblings. */
    private val COVER_NAMES = listOf(
        "{id}_cover.jpg", "{id}_{id}-cover.png", "{id}_new-cover.jpg",
        "{id}_cover.png", "{id}_{id}-cover.jpg", "{id}_new-cover.png",
    )
}
