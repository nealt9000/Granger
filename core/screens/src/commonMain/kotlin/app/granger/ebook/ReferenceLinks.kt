package app.granger.ebook

import app.granger.core.library.BookMention

/**
 * What a tapped link opens (plan Decision 7): the article's title, first sentence and picture, and where it is; [book]
 * when the link is a work whose book is on the phone ("In your library").
 */
data class LinkPeek(
    val qid: Long, val packId: String, val path: String, val title: String, val sentence: String, val thumbnail: String?,
    val book: LibraryBook? = null,
)

/** Where the reader gets a Classic's links and what a tapped one opens; the app's is [ReferenceLinksSource]. */
interface BookLinkSource {
    /** The open book's mentions up to [level]'s tier that an installed Wikipedia can open; empty when it has no links file. */
    suspend fun mentions(bookId: String, level: LinkLevel): List<BookMention>

    /**
     * The peek card for [qid]; null when no installed pack has the article any more. With the book's [language], an
     * article in that language comes first (Task 10: Don Quijote's Aristóteles opened the English article).
     */
    suspend fun peek(qid: Long, language: String? = null): LinkPeek?
}

/**
 * A reference link drawn on a book's page, without the reader's own types (Plan I5 Task 2): its id, which names the
 * item it opens, and the words before it that tell it from the same name earlier in the paragraph. Android's
 * `LinkDecorations` turns mentions into Readium decorations with these; the iPhone's reader will do the same.
 */
object LinkMentions {
    /** How much of the words before a mention the reader gets to tell it from the same name earlier (as Readium's own quotes keep). */
    const val CONTEXT = 32

    /** The words before a mention in its own paragraph (the builder joined paragraphs with a newline), at most [CONTEXT]. */
    fun contextOf(before: String): String? = before.substringAfterLast('\n').takeLast(CONTEXT).ifEmpty { null }

    fun idOf(m: BookMention) = "${m.qid}@${m.href}#${m.start}"

    /** The item a link's id points at; null for any other id. */
    fun qidOf(decorationId: String): Long? = decorationId.substringBefore('@').toLongOrNull()
}
