package app.granger.ebook

import android.os.Parcel
import android.os.Parcelable
import app.granger.core.library.BookMention
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

/** Reference links in the Classics (Plan LL1 Decision 5): none, the Light set (the default), or More. */
enum class LinkLevel(val maxTier: Int) { OFF(0), LIGHT(1), MORE(2) }

/**
 * What a tapped link opens (plan Decision 7): the article's title, first sentence and picture, and where it is; [book]
 * when the link is a work whose book is on the phone ("In your library").
 */
data class LinkPeek(
    val qid: Long, val packId: String, val path: String, val title: String, val sentence: String, val thumbnail: String?,
    val book: LibraryBook? = null,
)

/** Where the reader gets a Classic's links and what a tapped one opens; the app's is [app.granger.ReferenceLinksSource]. */
interface BookLinkSource {
    /** The open book's mentions up to [level]'s tier that an installed Wikipedia can open; empty when it has no links file. */
    suspend fun mentions(bookId: String, level: LinkLevel): List<BookMention>

    /** The peek card for [qid]; null when no installed pack has the article any more. */
    suspend fun peek(qid: Long): LinkPeek?
}

/** The group of decorations drawn for the reference links, apart from the highlights, the search hit and the voice. */
const val LINKS_GROUP = "links"

/**
 * A reference link's look (Decision 7): a thin dotted underline in the book's link tone, under the words only, so the
 * read-aloud tint and the highlights draw over it unchanged. Parcelable by hand: Readium keeps styles in its state.
 */
data class ReferenceLinkStyle(val tint: Int) : Decoration.Style {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeInt(tint)

    companion object {
        @JvmField val CREATOR = object : Parcelable.Creator<ReferenceLinkStyle> {
            override fun createFromParcel(source: Parcel) = ReferenceLinkStyle(source.readInt())
            override fun newArray(size: Int) = arrayOfNulls<ReferenceLinkStyle>(size)
        }

        /** Readium's template for it: one box per line of the words, with a dotted bottom border. */
        fun template(): HtmlDecorationTemplate = HtmlDecorationTemplate(
            layout = HtmlDecorationTemplate.Layout.BOXES,
            width = HtmlDecorationTemplate.Width.WRAP,
            element = { decoration ->
                val tint = (decoration.style as? ReferenceLinkStyle)?.tint ?: BookLink
                """<div class="granger-ref" style="border-bottom-color: ${cssColor(tint)}"></div>"""
            },
            stylesheet = ".granger-ref { border-bottom: 1.5px dotted; box-sizing: border-box; pointer-events: none; }",
        )

        private fun cssColor(argb: Int) =
            "rgba(${(argb shr 16) and 0xFF}, ${(argb shr 8) and 0xFF}, ${argb and 0xFF}, ${"%.2f".format(java.util.Locale.ROOT, ((argb ushr 24) and 0xFF) / 255.0)})"
    }
}

/** Readium's templates with the reference link's added. */
fun readerDecorationTemplates(): HtmlDecorationTemplates =
    HtmlDecorationTemplates.defaultTemplates().apply { set(ReferenceLinkStyle::class, ReferenceLinkStyle.template()) }

internal object LinkDecorations {
    /** How much of the words before a mention Readium gets to tell it from the same name earlier (as its own quotes keep). */
    const val CONTEXT = 32

    /**
     * The mentions as decorations, found in the page by their quote and the words before it in the same paragraph (the
     * builder joined paragraphs with a newline; the page's whitespace there differs). A mention in a chapter this edition
     * doesn't have is left out; one Readium can't find on the page is simply not drawn.
     */
    fun place(publication: Publication?, mentions: List<BookMention>, tint: Int = BookLink): List<Decoration> {
        if (publication == null) return emptyList()
        val chapters = HashMap<String, Pair<Url, MediaType>?>()
        return mentions.mapNotNull { m ->
            val (href, type) = chapters.getOrPut(m.href) {
                Url.fromDecodedPath(m.href)?.let { publication.chapterFor(it) }?.let { it.url() to (it.mediaType ?: MediaType.XHTML) }
            } ?: return@mapNotNull null
            Decoration(
                id = idOf(m),
                locator = Locator(href = href, mediaType = type, text = Locator.Text(before = contextOf(m.before), highlight = m.quote)),
                style = ReferenceLinkStyle(tint),
            )
        }
    }

    fun contextOf(before: String): String? = before.substringAfterLast('\n').takeLast(CONTEXT).ifEmpty { null }

    fun idOf(m: BookMention) = "${m.qid}@${m.href}#${m.start}"

    /** The item a link decoration points at; null for any other id. */
    fun qidOf(decorationId: String): Long? = decorationId.substringBefore('@').toLongOrNull()
}
