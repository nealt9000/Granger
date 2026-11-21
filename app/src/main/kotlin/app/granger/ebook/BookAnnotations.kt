package app.granger.ebook

import app.granger.UiText
import app.granger.core.library.BookAnnotation
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url

// HighlightColors, the search panel's rows and the marks' split are shared (core:screens BookSearch, Plan I5 Task 2).

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

/** Where the row opens, as Readium's locator. */
val BookSearchRow.locator: Locator? get() = locatorJson?.let(::parseLocator)

/** A search panel row at [locator] (Android's readers and tests make them from Readium's locators). */
fun BookSearchRow(locator: Locator?, chapter: UiText, before: String, match: String, after: String, markId: Long?): BookSearchRow =
    BookSearchRow(locator?.toJSON()?.toString(), chapter, before, match, after, markId)

/** Marks whose text or note holds [query] first, then the text [hits] ([SearchRows] is shared; Readium's files compared). */
internal fun SearchRows.build(query: String, marks: List<BookAnnotation>, hits: List<Locator>, toc: List<TocRow>): List<BookSearchRow> =
    build(query, marks, hits.map { it.toTextHit() }, toc, ::sameHrefFile)

/** Whether two hrefs name the same file, as Readium's Urls. */
internal fun sameHrefFile(a: String, b: String): Boolean {
    val ua = Url(a) ?: return false
    val ub = Url(b) ?: return false
    return sameFile(ua, ub)
}
