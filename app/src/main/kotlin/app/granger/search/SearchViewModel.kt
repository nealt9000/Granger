package app.granger.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.BookTarget
import app.granger.core.library.BookHit
import app.granger.core.library.BookIndex
import app.granger.core.library.LibrarySearch
import app.granger.core.library.MediaIndex
import app.granger.core.library.MediaResults
import app.granger.core.library.ReadingSessions
import app.granger.core.library.SearchHit
import app.granger.core.library.ZimLibrarySearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The music and audiobooks matching a query ([app.granger.core.library.MediaIndex.search] in the app). */
fun interface MediaSearch { suspend fun search(query: String): MediaResults }

/**
 * A run of results under one heading (Music has three). Each shows its first [preview] rows, and Show all for the rest;
 * [fetch] is how many are asked for, so a group that fills it may have more than Search knows of.
 */
enum class SearchGroup(val preview: Int, val fetch: Int, val noun: String) {
    TITLES(8, ZimLibrarySearch.TITLE_LIMIT, "titles"),
    ARTISTS(3, MEDIA_FETCH, "artists"),
    ALBUMS(5, MEDIA_FETCH, "albums"),
    SONGS(5, MEDIA_FETCH, "songs"),
    AUDIOBOOKS(5, MEDIA_FETCH, "audiobooks"),
    FULL_TEXT(8, ZimLibrarySearch.FULL_TEXT_LIMIT, "article matches"),
    BOOKS(8, BookIndex.LIMIT, "books"),
}

private const val MEDIA_FETCH = 25

/** [MediaIndex.search] asked for as many of each kind as Search's groups fetch. */
suspend fun MediaIndex.searchForGroups(query: String): MediaResults = search(
    query,
    artists = SearchGroup.ARTISTS.fetch, albums = SearchGroup.ALBUMS.fetch,
    songs = SearchGroup.SONGS.fetch, audiobooks = SearchGroup.AUDIOBOOKS.fetch,
)

/**
 * Search over titles (books on the shelf among them), then music and audiobooks, then books, then article text (shown
 * as Titles, Articles, Books, Music, Audiobooks). [initialQuery] (for example text selected in the reader, or a
 * recent search) is searched straight away; opening a hit remembers the query and opens a rail tab.
 */
@OptIn(FlowPreview::class)
class SearchViewModel(
    private val search: LibrarySearch,
    private val sessions: ReadingSessions,
    initialQuery: String = "",
    private val media: MediaSearch = MediaSearch { MediaResults() },
) : ViewModel() {
    data class State(
        val query: String = "",
        val titles: List<SearchHit> = emptyList(),
        /** Music and audiobooks, shown after [titles]. */
        val media: MediaResults = MediaResults(),
        /** Full-text hits whose article is not already in [titles]; a snippet without the query in it is dropped. */
        val fullText: List<SearchHit> = emptyList(),
        /** Hits inside books, one per book: its first (best) hit, which says how many places [BookHit.matches]. */
        val books: List<BookHit> = emptyList(),
        val searching: Boolean = false,
        /** Recent searches, newest first; shown while the field is empty. */
        val recent: List<String> = emptyList(),
        /** Groups opened with Show all; closed again by a new query. */
        val expanded: Set<SearchGroup> = emptySet(),
        /** Groups whose search returned as many as it was asked for, so more may match than are listed. */
        val capped: Set<SearchGroup> = emptySet(),
    ) {
        /** The rows of [group] on screen: its preview, or all of [all] once expanded. */
        fun <T> shown(group: SearchGroup, all: List<T>): List<T> = if (group in expanded) all else all.take(group.preview)
    }

    private val query = MutableStateFlow(initialQuery.trim())
    private val _state = MutableStateFlow(State(query = initialQuery))
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * The search field's text, as Compose state written synchronously on every keystroke. The field must not read its
     * value from a flow: a value that lags a dispatch behind the keyboard is pushed back into the field and scrambles
     * fast typing (seen on the Pixel 6, 2026-10-17).
     */
    var searchText by mutableStateOf(initialQuery)
        private set

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
                    _state.update {
                        it.copy(
                            titles = emptyList(), media = MediaResults(), fullText = emptyList(), books = emptyList(), searching = false,
                            expanded = emptySet(), capped = emptySet(),
                        )
                    }
                    return@collectLatest
                }
                _state.update { it.copy(searching = true, expanded = emptySet(), capped = emptySet()) }
                // Each group's results land with its cap, so its Show all label is right from the first frame.
                val titles = search.titles(q)
                _state.update {
                    it.copy(titles = titles.hits, capped = it.capped.cap(SearchGroup.TITLES, titles.hits.size, titles.more))
                }
                // The media index is in memory and quick; a failure there must not cost the article and book results.
                val found = try {
                    media.search(q)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    MediaResults()
                }
                _state.update {
                    val capped = it.capped.cap(SearchGroup.ARTISTS, found.artists.size).cap(SearchGroup.ALBUMS, found.albums.size)
                        .cap(SearchGroup.SONGS, found.songs.size).cap(SearchGroup.AUDIOBOOKS, found.audiobooks.size)
                    it.copy(media = found, capped = capped)
                }
                // Books come before article text: the book index answers in a moment, full text over big packs may not.
                // One row per book (Plan U3b Decision 10); the search's cap counts its hits, as it stopped on those.
                val books = search.books(q)
                _state.update {
                    it.copy(books = books.distinctBy { hit -> hit.bookId }, capped = it.capped.cap(SearchGroup.BOOKS, books.size))
                }
                val shown = titles.hits.map { it.packId to it.path }.toSet()
                // Counted before the titles are taken out: the search stopped at its limit either way.
                val allText = search.fullText(q)
                _state.update {
                    it.copy(
                        fullText = allText.filterNot { hit -> (hit.packId to hit.path) in shown }
                            .map { hit -> hit.copy(snippet = hit.snippet?.takeIf { s -> SearchRows.snippetMatches(s, q) }) },
                        searching = false,
                        capped = it.capped.cap(SearchGroup.FULL_TEXT, allText.size),
                    )
                }
            }
        }
    }

    fun onQueryChange(text: String) {
        searchText = text
        query.value = text.trim()
        _state.update { it.copy(query = text) }
    }

    /** Show all, or Show fewer, for [group]. */
    fun toggle(group: SearchGroup) {
        _state.update { it.copy(expanded = if (group in it.expanded) it.expanded - group else it.expanded + group) }
    }

    /** Remembers the current query as a recent search; called when a music or audiobook result is opened or played. */
    suspend fun recordSearch() {
        sessions.recordSearch(query.value)
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

    /** These groups, with [group] added when its [count] reaches its fetch or its search said it had [more]. */
    private fun Set<SearchGroup>.cap(group: SearchGroup, count: Int, more: Boolean = false) =
        if (more || count >= group.fetch) this + group else this

    companion object {
        const val DEBOUNCE_MS = 250L
    }
}
