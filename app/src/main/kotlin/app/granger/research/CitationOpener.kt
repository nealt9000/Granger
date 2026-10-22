package app.granger.research

import app.granger.BookTarget
import app.granger.core.books.TextQuote
import app.granger.core.library.Bookshelf
import app.granger.core.library.Library
import app.granger.core.library.MediaIndex
import app.granger.core.library.PackStatus
import app.granger.core.library.PassageAddress
import app.granger.core.library.ReadingSessions
import app.granger.core.research.CitedQuote
import app.granger.core.research.Passage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Turns a cited passage, or any [PassageAddress], into where it opens (research spec §7). The only place that does:
 * Research's source cards go through it, and read-aloud and plans can hand it a bare address.
 *
 * What no longer resolves opens the nearest thing that does: an article whose page is gone opens its pack's main page
 * ([CitationTarget.Article.moved]), a book whose chapter is gone opens where it was left, a song or chapter that's gone
 * opens its album or audiobook, and a missing pack, book, album or audiobook is [CitationTarget.Gone].
 */
class CitationOpener(
    private val sessions: ReadingSessions,
    private val library: Library,
    private val bookshelf: Bookshelf,
    private val media: MediaIndex,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Cited passage `[n]` of [answer]: its address, with the sentence the answer used and that sentence's context. */
    suspend fun open(passage: Passage, answer: String, n: Int): CitationTarget {
        val quote = PassageAddress.quoteOf(passage, answer, n)
        val address = PassageAddress.of(passage, quote?.sentence)
        val target = open(address, quote, passage.sourceTitle)
        // A cited song that's gone still has its album on the card.
        if (target == CitationTarget.Gone && address is PassageAddress.Track) return open(PassageAddress.of(passage.copy(location = "")))
        return target
    }

    /**
     * [context] tells a book's quote apart from a repeat of it (without it the quote alone is looked for); [title]
     * names an article's tab (else its path does).
     */
    suspend fun open(address: PassageAddress, context: CitedQuote.Quote? = null, title: String? = null): CitationTarget = when (address) {
        is PassageAddress.Article -> article(address.packId, address.path, address.anchor, address.quote, title)
        is PassageAddress.Pack -> article(address.packId, "", null, null, title)
        is PassageAddress.Book -> book(address, context)
        is PassageAddress.Album ->
            if (media.work(ALBUM_SOURCE + address.albumKey) != null) CitationTarget.Album(address.albumKey) else CitationTarget.Gone
        is PassageAddress.Track -> {
            val work = media.workWithPart(address.id)
            if (work == null) CitationTarget.Gone else CitationTarget.Album(work.id.removePrefix(ALBUM_SOURCE), address.id)
        }
        is PassageAddress.Audiobook -> {
            val work = media.work(address.id)?.takeIf { !it.isAlbum }
            when {
                work == null -> CitationTarget.Gone
                address.chapter != null && "$CHAPTER${address.chapter}" in work.partIds -> CitationTarget.Audiobook(work.id, address.chapter)
                else -> CitationTarget.Audiobook(work.id)
            }
        }
    }

    private suspend fun article(packId: String, path: String, anchor: String?, quote: String?, title: String?): CitationTarget {
        val pack = library.packs.first().firstOrNull { it.id == packId }
        if (pack == null || pack.status != PackStatus.OK) return CitationTarget.Gone
        // A page gone from the pack (a newer snapshot dropped or renamed it) opens the main page, and says so.
        val moved = path.isNotEmpty() && withContext(io) { library.archive(pack.id)?.let { it.resolvePath(path) == null } } == true
        val opened = if (moved) "" else path
        val tabTitle = if (moved) pack.title else title?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/').replace('_', ' ').ifEmpty { pack.title }
        val tabId = sessions.openTab(pack.id, opened, tabTitle)
        // The tab can vanish between opening and showing it (closed from the rail): the source is gone, say so.
        if (citationTab(sessions, tabId) == null) return CitationTarget.Gone
        if (opened.isEmpty()) return CitationTarget.Article(tabId, null, moved = moved)
        return CitationTarget.Article(tabId, anchor, quote)
    }

    private suspend fun book(address: PassageAddress.Book, context: CitedQuote.Quote?): CitationTarget {
        val book = bookshelf.book(address.bookId)
        if (book == null || book.status != PackStatus.OK) return CitationTarget.Gone
        val tabId = sessions.openTab(book.id, "", book.title)
        if (citationTab(sessions, tabId) == null) return CitationTarget.Gone
        if (address.href.isEmpty()) return CitationTarget.Book(tabId, at = null)
        val quote = address.quote?.let { q ->
            if (context != null && context.sentence == q) TextQuote(context.before, q, context.after) else TextQuote("", q, "")
        }
        return CitationTarget.Book(tabId, BookTarget(address.href, address.progression, quote))
    }

    private companion object {
        const val CHAPTER = "chapter:"
    }
}
