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
import app.granger.core.text.SpokenTitleMatcher
import app.granger.voiceinput.Dictation
import app.granger.voiceinput.VoiceInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Best matches for a query ([app.granger.core.research.BestMatches] in the app). */
fun interface BestMatchSearch { suspend fun find(query: String): app.granger.core.research.BestMatches.Result }

/** The music and audiobooks matching a query ([app.granger.core.library.MediaIndex.search] in the app). */
fun interface MediaSearch { suspend fun search(query: String): MediaResults }

/**
 * A run of results under one heading (Music has three). Each shows its first [preview] rows, and Show all for the rest;
 * [fetch] is how many are asked for, so a group that fills it may have more than Search knows of.
 */
enum class SearchGroup(val preview: Int, val fetch: Int) {
    BEST(3, app.granger.core.research.BestMatches.LIMIT),
    TITLES(8, ZimLibrarySearch.TITLE_LIMIT),
    ARTISTS(3, MEDIA_FETCH),
    ALBUMS(5, MEDIA_FETCH),
    SONGS(5, MEDIA_FETCH),
    AUDIOBOOKS(5, MEDIA_FETCH),
    FULL_TEXT(8, ZimLibrarySearch.FULL_TEXT_LIMIT),
    BOOKS(8, BookIndex.LIMIT),
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
    /** Voice input (Plan A1); null leaves Search without a mic. */
    voice: VoiceInput? = null,
    /** The library's names, for "Did you mean" after a spoken search ([app.granger.voiceinput.SpokenTitles]). */
    private val spokenNames: Flow<List<String>> = emptyFlow(),
    /** Where the title matcher runs (a few milliseconds over a large library); tests pass their own. */
    private val matching: CoroutineDispatcher = Dispatchers.Default,
    /** Best matches (Plan SQ); null leaves Search without them. */
    private val best: BestMatchSearch? = null,
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
        /** Packs whose text isn't searched, only their titles ([LibrarySearch.titleOnly]); said under the results. */
        val titleOnly: List<String> = emptyList(),
        /** Recent searches, newest first; shown while the field is empty. */
        val recent: List<String> = emptyList(),
        /** Groups opened with Show all; closed again by a new query. */
        val expanded: Set<SearchGroup> = emptySet(),
        /** Groups whose search returned as many as it was asked for, so more may match than are listed. */
        val capped: Set<SearchGroup> = emptySet(),
        /**
         * After a spoken search, the library name the words most likely meant, when it isn't what was searched
         * (Plan A1: the recognizer misspells names, *the domapada*). Typing clears it.
         */
        val didYouMean: String? = null,
        /** Best matches (Plan SQ): the articles and chapters that best answer the query, each with its passage. */
        val best: List<app.granger.core.research.BestMatches.Match> = emptyList(),
        /** Best matches are being looked for. */
        val bestLoading: Boolean = false,
    ) {
        /** The rows of [group] on screen: its preview, or all of [all] once expanded. */
        fun <T> shown(group: SearchGroup, all: List<T>): List<T> = if (group in expanded) all else all.take(group.preview)
    }

    /** The trimmed query, and how many times [refresh] has asked for it again. */
    private data class Request(val text: String, val generation: Int = 0)

    private val query = MutableStateFlow(Request(initialQuery.trim()))

    /** The running search for titles a slip away ([LibrarySearch.nearTitles]); a new query cancels it. */
    private var nearJob: kotlinx.coroutines.Job? = null

    /** The running Best matches search; a new query cancels it. */
    private var bestJob: kotlinx.coroutines.Job? = null
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
            // The seed query and a refresh are searched at once; anything typed afterwards is debounced.
            var seed = true
            var generation = 0
            var lastText = ""
            query.debounce {
                val wait = if ((seed || it.generation != generation) && it.text.isNotBlank()) 0L else DEBOUNCE_MS
                seed = false
                generation = it.generation
                wait
            }.distinctUntilChanged().collectLatest { request ->
                val q = request.text
                nearJob?.cancel()
                bestJob?.cancel()
                // A refresh keeps the groups opened with Show all.
                val again = q == lastText
                lastText = q
                if (q.isBlank()) {
                    _state.update {
                        it.copy(
                            titles = emptyList(), media = MediaResults(), fullText = emptyList(), books = emptyList(), searching = false,
                            best = emptyList(), bestLoading = false,
                            expanded = emptySet(), capped = emptySet(),
                        )
                    }
                    return@collectLatest
                }
                _state.update { it.copy(searching = true, expanded = if (again) it.expanded else emptySet(), capped = emptySet()) }
                // Best matches search by meaning too: they wait for a longer pause than the keystroke debounce, unless the
                // query was submitted (a refresh), and the next query cancels them.
                best?.let { source ->
                    val submitted = request.generation > 0
                    bestJob = viewModelScope.launch {
                        if (!submitted) kotlinx.coroutines.delay(BEST_PAUSE_MS)
                        _state.update { it.copy(bestLoading = true) }
                        val found = try {
                            source.find(q).matches
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            emptyList()
                        }
                        if (query.value.text == q) _state.update { it.copy(best = found, bestLoading = false) }
                    }
                }
                // Each group's results land with its cap, so its Show all label is right from the first frame.
                val titles = search.titles(q)
                _state.update {
                    it.copy(titles = titles.hits, capped = it.capped.cap(SearchGroup.TITLES, titles.hits.size, titles.more))
                }
                // Titles a slip away (Plan SQ: typos) take longer: they arrive beside the other groups, placed after the
                // ranked titles. The next query cancels them.
                nearJob = viewModelScope.launch {
                    val found = try {
                        search.nearTitles(q, titles.hits)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                    if (found.isNotEmpty() && query.value.text == q) _state.update { it.copy(titles = titles.withNear(found)) }
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
                val titleOnly = search.titleOnly()
                _state.update {
                    it.copy(
                        fullText = allText.filterNot { hit -> (hit.packId to hit.path) in shown }
                            .map { hit -> hit.copy(snippet = hit.snippet?.takeIf { s -> SearchRows.snippetMatches(s, q) }) },
                        searching = false,
                        titleOnly = titleOnly,
                        capped = it.capped.cap(SearchGroup.FULL_TEXT, allText.size),
                    )
                }
            }
        }
    }

    fun onQueryChange(text: String) {
        setQuery(text)
        _state.update { it.copy(didYouMean = null) }
    }

    private fun setQuery(text: String) {
        searchText = text
        query.update { it.copy(text = text.trim()) }
        _state.update { it.copy(query = text) }
    }

    /** Speaking a search (Plan A1): null when there's no mic. The words fill the field as they come. */
    val dictation: Dictation? = voice?.let { Dictation(it, viewModelScope) }

    /** Whether the mic shows: voice input is set up and the phone can listen in the app's language now. */
    val micShown: StateFlow<Boolean> = voice?.status?.map { it.micShown }?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
        ?: MutableStateFlow(false)

    /** Read from the first spoken search on, so the names are ready by the time the speaker stops. */
    private val names: StateFlow<List<String>> by lazy { spokenNames.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList()) }

    /** The mic: listens, fills the field as the words come, then searches at once and offers "Did you mean". */
    fun speak() {
        val dictation = dictation ?: return
        names.value // starts reading the library's names
        _state.update { it.copy(didYouMean = null) }
        dictation.start(onText = ::setQuery) { heard ->
            setQuery(heard)
            refresh()
            viewModelScope.launch { suggest(heard) }
        }
    }

    private suspend fun suggest(heard: String) {
        val library = withTimeoutOrNull(NAMES_WAIT_MS) { names.first { it.isNotEmpty() } } ?: names.value
        val match = withContext(matching) { SpokenTitleMatcher.best(heard, library) } ?: return
        val said = SpokenTitleMatcher.stripVerb(SpokenTitleMatcher.normalize(heard))
        if (SpokenTitleMatcher.normalize(match.name) == said) return
        // Only if the field still holds what was heard: typing since then has moved on.
        if (searchText == heard) _state.update { it.copy(didYouMean = match.name) }
    }

    /** "Did you mean …?" tapped: searches that name instead. */
    fun acceptSuggestion() {
        val name = state.value.didYouMean ?: return
        onQueryChange(name)
        refresh()
    }

    /**
     * Searches the current query again: on submit, and when Search is shown again. Results are otherwise kept for an
     * unchanged query, so coming Back from a book opened (and so indexed) since would show the old ones.
     */
    fun refresh() {
        if (query.value.text.isNotBlank()) query.update { it.copy(generation = it.generation + 1) }
    }

    private var shown = false

    /** Search is on screen (resumed); every time after the first, the query is searched again ([refresh]). */
    fun onShown() {
        if (shown) refresh() else shown = true
    }

    /** Show all, or Show fewer, for [group]. */
    fun toggle(group: SearchGroup) {
        _state.update { it.copy(expanded = if (group in it.expanded) it.expanded - group else it.expanded + group) }
    }

    /** Remembers the current query as a recent search; called when a music or audiobook result is opened or played. */
    suspend fun recordSearch() {
        sessions.recordSearch(query.value.text)
    }

    /** Remembers the current query as a recent search, opens the hit in a new rail tab and returns its id. */
    suspend fun open(hit: SearchHit): Long {
        sessions.recordSearch(query.value.text)
        return sessions.openTab(hit.packId, hit.path, hit.title)
    }

    /** Remembers the current query, opens (or reuses) the book's tab, and returns it with the place to open at. */
    suspend fun openBook(hit: BookHit): Pair<Long, BookTarget> {
        sessions.recordSearch(query.value.text)
        return sessions.openTab(hit.bookId, "", hit.bookTitle) to BookTarget(hit.href, hit.progression, hit.quote)
    }

    /** These groups, with [group] added when its [count] reaches its fetch or its search said it had [more]. */
    private fun Set<SearchGroup>.cap(group: SearchGroup, count: Int, more: Boolean = false) =
        if (more || count >= group.fetch) this + group else this

    companion object {
        const val DEBOUNCE_MS = 250L
        /** How much longer than [DEBOUNCE_MS] typing must pause before Best matches are looked for (Plan SQ Decision 5). */
        const val BEST_PAUSE_MS = 350L
        /** How long "Did you mean" waits for the library's names on the very first spoken search. */
        const val NAMES_WAIT_MS = 2_000L
    }
}
