package app.granger.core.research

enum class SourceKind { ARTICLE, BOOK, CARD, SELECTION }

/** Bibliographic extras that help ranking and citation; all optional. */
data class SourceMeta(
    val author: String = "",
    val date: String = "",
    val subjects: String = "",
    val collection: String = "",
)

/**
 * A quotable piece of a source. [sourceId] is the pack id or book id; [location] is the entry path (ARTICLE)
 * or the spine href (BOOK).
 */
data class Passage(
    val kind: SourceKind,
    val sourceId: String,
    val sourceTitle: String,
    val packTitle: String = "",
    val location: String = "",
    val anchor: String? = null,
    val progression: Double = 0.0,
    val heading: String = "",
    val text: String,
    val meta: SourceMeta = SourceMeta(),
    val searchScore: Double = 0.0,
)
