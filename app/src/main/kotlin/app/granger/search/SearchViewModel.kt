package app.granger.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.BookTarget
import app.granger.core.library.BookHit
import app.granger.core.library.LibrarySearch
import app.granger.core.library.ReadingSessions
import app.granger.core.library.SearchHit
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Search over titles then article text. [initialQuery] (for example text selected in the reader, or a
 * recent search) is searched straight away; opening a hit remembers the query and opens a rail tab.
 */
@OptIn(FlowPreview::class)
class SearchViewModel(
    private val search: LibrarySearch,
    private val sessions: ReadingSessions,
    initialQuery: String = "",
) : ViewModel() {
    data class State(
        val query: String = "",
        val titles: List<SearchHit> = emptyList(),
        /** Full-text hits whose article is not already in [titles]. */
        val fullText: List<SearchHit> = emptyList(),
        /** Hits inside books, after the article sections. */
        val books: List<BookHit> = emptyList(),
        val searching: Boolean = false,
        /** Recent searches, newest first; shown while the field is empty. */
        val recent: List<String> = emptyList(),
    )

    private val query = MutableStateFlow(initialQuery.trim())
    private val _state = MutableStateFlow(State(query = initialQuery))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sessions.recentSearches().collect { recent -> _state.update { it.copy(recent = recent) } }
        }
        viewModelScope.launch {
            // The seed query is searched at once; anything typed afterwards is debounced.
            var seed = true
            query.debounce {
                val wait = if (seed && it.isNotBlank()) 0L else DEBOUNCE_MS
                seed = false
                wait
            }.distinctUntilChanged().collectLatest { q ->
                if (q.isBlank()) {
                    _state.update { it.copy(titles = emptyList(), fullText = emptyList(), books = emptyList(), searching = false) }
                    return@collectLatest
                }
                _state.update { it.copy(searching = true) }
                val titles = search.titles(q)
                _state.update { it.copy(titles = titles) }
                val shown = titles.map { it.packId to it.path }.toSet()
                val fullText = search.fullText(q).filterNot { (it.packId to it.path) in shown }
                _state.update { it.copy(fullText = fullText) }
                val books = search.books(q)
                _state.update { it.copy(books = books, searching = false) }
            }
        }
    }

    fun onQueryChange(text: String) {
        query.value = text.trim()
        _state.update { it.copy(query = text) }
    }

    /** Remembers the current query as a recent search, opens the hit in a new rail tab and returns its id. */
    suspend fun open(hit: SearchHit): Long {
        sessions.recordSearch(query.value)
        return sessions.openTab(hit.packId, hit.path, hit.title)
    }

    /** Remembers the current query, opens (or reuses) the book's tab, and returns it with the place to open at. */
    suspend fun openBook(hit: BookHit): Pair<Long, BookTarget> {
        sessions.recordSearch(query.value)
        return sessions.openTab(hit.bookId, "", hit.bookTitle) to BookTarget(hit.href, hit.progression, hit.quote)
    }

    companion object {
        const val DEBOUNCE_MS = 250L
    }
}
