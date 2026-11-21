package app.granger.ebook

import app.granger.UiText
import app.granger.core.library.BookAnnotation
import app.granger.core.library.MarkKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The four highlight colours: muted tints that read on the Day, Sepia and Night pages alike. */
object HighlightColors {
    val names = listOf("yellow", "green", "blue", "pink")

    /** [name]'s tint as an ARGB int; yellow for a name it doesn't know. */
    fun argb(name: String): Int = when (name) {
        "green" -> 0xFF7BC47F.toInt()
        "blue" -> 0xFF6FA8DC.toInt()
        "pink" -> 0xFFE88FB4.toInt()
        else -> 0xFFE8C94A.toInt()
    }
}

/**
 * One place in a book where a search found the words (Plan I5 Task 2): its locator as the reader's JSON (Readium's on
 * both phones), the chapter file [href], the chapter's [title] when the locator has one, and the words around the match.
 */
data class BookTextHit(
    val locatorJson: String,
    val href: String,
    val title: String?,
    val before: String?,
    val highlight: String?,
    val after: String?,
)

/**
 * Search in the open book. The search itself is the reader's (Readium's search service on Android, `ReadiumBookSearcher`;
 * Readium Swift's on the iPhone); the results panel, with the reader's own marks first, is shared ([SearchRows]).
 */
interface BookSearcher {
    /** Up to [max] places where [query] occurs, in book order; empty when the book can't be searched. */
    suspend fun search(query: String, max: Int = 200): List<BookTextHit>
}

/** One line in the search panel: a hit in context, or one of the reader's own marks. [locatorJson] is where it opens. */
data class BookSearchRow(
    val locatorJson: String?,
    /** The chapter, or for the reader's own mark "Your highlight · chapter". */
    val chapter: UiText,
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

/** A book's bookmarks, newest first, and its highlights, as the reader lists them. */
data class ReaderMarks(val bookmarks: List<BookAnnotation>, val highlights: List<BookAnnotation>) {
    companion object {
        fun of(marks: List<BookAnnotation>) = ReaderMarks(
            marks.filter { m -> m.kind == MarkKind.BOOKMARK }.sortedWith(compareByDescending<BookAnnotation> { m -> m.createdAt }.thenByDescending { m -> m.id }),
            marks.filter { m -> m.kind == MarkKind.HIGHLIGHT },
        )
    }
}

object SearchRows {
    /**
     * Marks whose text or note holds [query] (ignoring case) first, then the text [hits]. A hit with no chapter title
     * takes the last contents row in its file; [sameFile] says whether two hrefs name the same file (Android compares
     * them as Readium's Urls; by default, as written, without their #fragments).
     */
    fun build(
        query: String, marks: List<BookAnnotation>, hits: List<BookTextHit>, toc: List<TocRow>,
        sameFile: (String, String) -> Boolean = { a, b -> a.substringBefore('#') == b.substringBefore('#') },
    ): List<BookSearchRow> {
        val own = marks.mapNotNull { mark ->
            val inNote = mark.note.contains(query, ignoreCase = true)
            val inText = mark.text.contains(query, ignoreCase = true)
            if (!inNote && !inText) return@mapNotNull null
            val locator = parse(mark.locatorJson)
            val where = locator?.get("title")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.trim().orEmpty()
            BookSearchRow(
                locatorJson = mark.locatorJson.takeIf { locator != null },
                chapter = when {
                    where.isEmpty() -> UiText.Res(if (inText) "ebook_your_highlight" else "ebook_your_note")
                    else -> UiText.Res(if (inText) "ebook_your_highlight_in" else "ebook_your_note_in", where)
                },
                before = "",
                match = if (inText) mark.text else mark.note,
                after = "",
                markId = mark.id,
            )
        }
        val text = hits.map { hit ->
            BookSearchRow(
                locatorJson = hit.locatorJson,
                chapter = UiText.Raw(hit.title?.trim().orEmpty().ifEmpty { chapterTitle(hit.href, toc, sameFile) }),
                before = lead(hit.before.orEmpty()),
                match = hit.highlight.orEmpty(),
                after = hit.after.orEmpty(),
                markId = null,
            )
        }
        return own + text
    }

    private fun parse(json: String) = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()

    /**
     * The words before a hit, cut to about [LEAD_CHARS] at a word break: the reader's search gives a long run of text
     * before the match, and the row's four lines then ended before the match was shown (Pixel check, I5a).
     */
    fun lead(before: String): String {
        if (before.length <= LEAD_CHARS) return before
        val tail = before.takeLast(LEAD_CHARS)
        val space = tail.indexOf(' ')
        val words = if (space in 0 until tail.length - 1) tail.substring(space + 1) else tail
        return "…$words"
    }

    const val LEAD_CHARS = 60

    private fun chapterTitle(href: String, toc: List<TocRow>, sameFile: (String, String) -> Boolean): String =
        toc.lastOrNull { row -> sameFile(row.href, href) }?.title.orEmpty()
}
