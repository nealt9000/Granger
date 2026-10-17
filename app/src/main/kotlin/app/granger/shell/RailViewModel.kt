package app.granger.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.library.Book
import app.granger.core.library.BookIds
import app.granger.core.library.BookPosition
import app.granger.core.library.Bookshelf
import app.granger.core.library.Library
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.core.library.ReadingSessions
import app.granger.core.library.ResearchChats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One bookmark tab on the rail. [initial] fills the letter tile, since ZIM articles have no covers. */
data class RailTabUi(
    val tabId: Long,
    val title: String,
    /** The pack's title. */
    val subtitle: String,
    val initial: Char,
    /** False when the pack is missing, damaged or gone; the tile is greyed out. */
    val available: Boolean,
    /** The tab the reader is showing; outlined in flame. */
    val current: Boolean,
    /** A book's cover image file, drawn instead of the letter tile when it can be decoded. */
    val coverPath: String? = null,
)

/** A row in the rail's Recent section; [at] is when it was last searched or answered. */
sealed interface RecentItem {
    val at: Long

    data class Search(val query: String, override val at: Long) : RecentItem

    data class Chat(val chatId: Long, val title: String, override val at: Long) : RecentItem
}

/** [recent] holds searches and Research chats together, newest first. */
data class RailUi(val tabs: List<RailTabUi>, val recent: List<RecentItem>)

/** The rail's Bookmarks and Recent sections. [currentTabId] is the tab on screen, null off the reader. */
class RailViewModel(
    private val sessions: ReadingSessions,
    library: Library,
    bookshelf: Bookshelf,
    chats: ResearchChats,
    currentTabId: StateFlow<Long?>,
) : ViewModel() {
    private class Shelf(val packs: Map<String, Pack>, val books: Map<String, Book>, val positions: Map<String, BookPosition>)

    private val recent = combine(sessions.recentSearchRows(RECENT), chats.recent(RECENT)) { searches, chats ->
        (searches.map { RecentItem.Search(it.query, it.searchedAt) } + chats.map { RecentItem.Chat(it.id, it.title, it.updatedAt) })
            .sortedByDescending { it.at }
            .take(RECENT)
    }

    private val shelf = combine(library.packs, bookshelf.books, bookshelf.positions) { packs, books, positions ->
        Shelf(packs.associateBy { it.id }, books.associateBy { it.id }, positions)
    }

    val ui: StateFlow<RailUi> = combine(
        sessions.railTabs(),
        shelf,
        currentTabId,
        recent,
    ) { tabs, shelf, current, recent ->
        RailUi(
            tabs = tabs.map { tab ->
                if (BookIds.isBook(tab.packId)) {
                    val book = shelf.books[tab.packId]
                    RailTabUi(
                        tabId = tab.id,
                        title = book?.title ?: tab.title,
                        subtitle = book?.let { bookSubtitle(it, shelf.positions[it.id]) } ?: HistoryViewModel.REMOVED_BOOK,
                        initial = initialOf(book?.title ?: tab.title),
                        available = book?.status == PackStatus.OK,
                        current = tab.id == current,
                        coverPath = book?.coverPath,
                    )
                } else {
                    val pack = shelf.packs[tab.packId]
                    RailTabUi(
                        tabId = tab.id,
                        title = tab.title,
                        subtitle = pack?.title ?: HistoryViewModel.REMOVED_PACK,
                        initial = initialOf(tab.title),
                        available = pack?.status == PackStatus.OK,
                        current = tab.id == current,
                    )
                }
            },
            recent = recent,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RailUi(emptyList(), emptyList()))

    /** "Chapter 3 · 43%" once the book has been opened; its author before that. */
    private fun bookSubtitle(book: Book, position: BookPosition?): String {
        if (position == null) return book.author
        val percent = "${(position.totalProgression * 100).roundToInt().coerceIn(0, 100)}%"
        return if (position.chapterTitle.isBlank()) percent else "${position.chapterTitle} \u00b7 $percent"
    }

    /** Long-press "Remove from bookmarks": off the rail, still in History. */
    fun closeTab(tabId: Long) {
        viewModelScope.launch { sessions.closeTab(tabId) }
    }

    private fun initialOf(title: String): Char = title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '#'

    private companion object {
        const val RECENT = 5
    }
}
