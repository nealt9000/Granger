package app.granger.core.books

/** What a book file says about itself: from an EPUB's package document or a PDF's info dictionary. */
class BookMeta(
    val title: String,
    val authors: List<String>,
    val translators: List<String>,
    val date: String,
    val language: String,
    val subjects: List<String>,
    val description: String,
    val identifier: String,
    val collection: String?,
    val cover: ByteArray?,
    val coverExtension: String?,
    /** Zip paths in reading order; empty for a PDF. */
    val spine: List<String>,
    /** A PDF's page count; null for an EPUB. */
    val pageCount: Int? = null,
    /** A PDF's outline, flattened depth-first; empty for an EPUB. */
    val outline: List<OutlineEntry> = emptyList(),
)

/** A PDF outline entry and the 1-based page it opens at. */
data class OutlineEntry(val title: String, val page: Int)

sealed interface Inspection {
    data class Ok(val meta: BookMeta) : Inspection
    data class Protected(val kind: DrmKind) : Inspection
    data class Invalid(val reason: String) : Inspection
}
