package app.granger.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.library.Book
import app.granger.core.library.BookIds
import app.granger.core.library.Bookshelf
import app.granger.core.library.Library
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.core.library.ReadingSessions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The History screen: everything opened, newest first, and the starred (saved) articles. */
class HistoryViewModel(
    private val sessions: ReadingSessions,
    private val library: Library,
    bookshelf: Bookshelf,
) : ViewModel() {
    data class Row(val packId: String, val path: String, val title: String, val packTitle: String, val available: Boolean)

    data class Ui(val history: List<Row>, val saved: List<Row>)

    private val filter = MutableStateFlow("")

    /** Null until loaded. Rows in missing, damaged or removed packs stay listed but disabled. */
    val ui: StateFlow<Ui?> = combine(sessions.history, library.bookmarks, library.packs, bookshelf.books, filter) { history, bookmarks, packs, books, filter ->
        val byId = packs.associateBy { it.id }
        val booksById = books.associateBy { it.id }
        val needle = filter.trim()
        Ui(
            history = history.sortedByDescending { it.openedAt }.map { row(it.packId, it.path, it.title, byId, booksById) }
                .filter { needle.isEmpty() || it.title.contains(needle, ignoreCase = true) || it.packTitle.contains(needle, ignoreCase = true) },
            saved = bookmarks.map { row(it.packId, it.path, it.title, byId, booksById) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Narrows the History list to titles or pack titles containing [text], ignoring case. Saved stays unfiltered. */
    fun setFilter(text: String) {
        filter.value = text
    }

    /** Opens the article in a rail tab (or the tab already showing it); returns the tab id. */
    suspend fun open(row: Row): Long = sessions.openTab(row.packId, row.path, row.title)

    /** Un-stars a saved article. */
    fun unsave(row: Row) {
        viewModelScope.launch { library.setBookmarked(row.packId, row.path, row.title, false) }
    }

    private fun row(packId: String, path: String, title: String, packs: Map<String, Pack>, books: Map<String, Book>): Row {
        if (BookIds.isBook(packId)) {
            val book = books[packId]
            val byline = if (book == null) REMOVED_BOOK else book.author
            return Row(packId, path, book?.title ?: title, byline, book?.status == PackStatus.OK)
        }
        val pack = packs[packId]
        return Row(packId, path, title, pack?.title ?: REMOVED_PACK, pack?.status == PackStatus.OK)
    }

    companion object {
        const val REMOVED_PACK = "Removed pack"
        const val REMOVED_BOOK = "Removed book"
    }
}
