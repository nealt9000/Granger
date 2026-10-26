package app.granger.ebook

import app.granger.core.library.BookAnnotation
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url

/** The four highlight colours: muted tints that read on the Day, Sepia and Night pages alike. */
object HighlightColors {
    val names = listOf("yellow", "green", "blue", "pink")

    fun argb(name: String): Int = when (name) {
        "green" -> 0xFF7BC47F.toInt()
        "blue" -> 0xFF6FA8DC.toInt()
        "pink" -> 0xFFE88FB4.toInt()
        else -> 0xFFE8C94A.toInt()
    }
}

/** The group of decorations drawn for the saved highlights, the one for the search hit being shown, and the voice's sentence. */
const val HIGHLIGHT_GROUP = "highlights"
const val SEARCH_GROUP = "search"
const val VOICE_GROUP = "voice"
internal const val SEARCH_HIT_TINT = 0xFFF28B30.toInt()
internal const val READING_TINT = 0x66F0A35E

internal fun parseLocator(json: String): Locator? = try {
    Locator.fromJSON(JSONObject(json))
} catch (e: Exception) {
    null
}

/** Highlights drawn in the open book, and the ids of those whose chapter isn't in this edition. */
internal data class Placed(val decorations: List<Decoration>, val unplaceable: Set<Long>)

internal fun place(publication: Publication?, highlights: List<BookAnnotation>): Placed {
    if (publication == null) return Placed(emptyList(), emptySet())
    val decorations = mutableListOf<Decoration>()
    val lost = mutableSetOf<Long>()
    for (mark in highlights) {
        val locator = parseLocator(mark.locatorJson)
        if (locator == null || publication.chapterFor(locator.href) == null) {
            lost += mark.id
        } else {
            decorations += Decoration(
                id = mark.id.toString(),
                locator = locator,
                style = Decoration.Style.Highlight(tint = HighlightColors.argb(mark.color)),
            )
        }
    }
    return Placed(decorations, lost)
}

/** One line in the search panel: a hit in context, or one of the reader's own marks. */
data class BookSearchRow(
    val locator: Locator?,
    val chapter: String,
    val before: String,
    val match: String,
    val after: String,
    val markId: Long?,
)

data class BookSearchUi(
    val query: String = "",
    val results: List<BookSearchRow> = emptyList(),
    val running: Boolean = false,
)

internal object SearchRows {
    /** Marks whose text or note holds [query] (ignoring case) first, then the text [hits]. */
    fun build(query: String, marks: List<BookAnnotation>, hits: List<Locator>, toc: List<TocRow>): List<BookSearchRow> {
        val own = marks.mapNotNull { mark ->
            val inNote = mark.note.contains(query, ignoreCase = true)
            val inText = mark.text.contains(query, ignoreCase = true)
            if (!inNote && !inText) return@mapNotNull null
            val locator = parseLocator(mark.locatorJson)
            val label = if (inText) "Your highlight" else "Your note"
            val where = locator?.title?.trim().orEmpty()
            BookSearchRow(
                locator = locator,
                chapter = if (where.isEmpty()) label else "$label · $where",
                before = "",
                match = if (inText) mark.text else mark.note,
                after = "",
                markId = mark.id,
            )
        }
        val text = hits.map { hit ->
            BookSearchRow(
                locator = hit,
                chapter = hit.title?.trim().orEmpty().ifEmpty { chapterTitle(hit.href, toc) },
                before = hit.text.before.orEmpty(),
                match = hit.text.highlight.orEmpty(),
                after = hit.text.after.orEmpty(),
                markId = null,
            )
        }
        return own + text
    }

    private fun chapterTitle(href: Url, toc: List<TocRow>): String =
        toc.lastOrNull { row -> Url(row.href)?.let { sameFile(it, href) } == true }?.title.orEmpty()
}
