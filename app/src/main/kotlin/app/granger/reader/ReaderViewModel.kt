package app.granger.reader

import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.library.Library
import app.granger.core.library.ReadingSessions
import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimHit
import app.granger.core.zim.ZimUrls
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One reading tab: the article it shows and the pages reached by following links from it. Writes (positions,
 * bookmarks, the tab's current page) go to [appScope] so they finish even when the user leaves the screen first.
 * [fragment] scrolls the first page to that section (a cited passage's heading).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel(
    private val tabId: Long,
    private val library: Library,
    private val sessions: ReadingSessions,
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val fragment: String? = null,
) : ViewModel() {
    data class Page(val path: String, val title: String)

    /**
     * Search inside the pack: [hits] are title suggestions while typing, then those plus full-text results once
     * submitted. [searched] is true once [hits] answers the current [query], so an empty list means no matches.
     */
    data class PackSearch(
        val query: String = "",
        val hits: List<ZimHit> = emptyList(),
        val searched: Boolean = false,
    )

    data class State(
        /** The URL a newly created WebView should load: the start page, then the current page. */
        val url: String? = null,
        val page: Page? = null,
        val bookmarked: Boolean = false,
        val error: String? = null,
        /** The top bar and the side rail. Hidden while reading; an error keeps them up so the user can leave. */
        val chromeVisible: Boolean = false,
        /** Find in page: null when the find bar is closed. */
        val findQuery: String? = null,
        /** Search this pack: null when closed. */
        val packSearch: PackSearch? = null,
        /** The pack's title for the search placeholder; null when unknown. */
        val packTitle: String? = null,
        /** Whether the pack has a main page, so the overflow menu can offer Home. */
        val hasHome: Boolean = false,
    )

    /**
     * The WebView's back/forward history, saved when the screen's WebView is released (Search opened,
     * rotation) so the next WebView restores it instead of starting a new history.
     */
    var webViewState: Bundle? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val page = MutableStateFlow<Page?>(null)

    /** The tab's pack, known once the tab is read; null before then or when the tab is gone. */
    var packId: String? = null
        private set

    private var archive: ZimArchive? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            val tab = sessions.tab(tabId) ?: return@launch fail(TAB_GONE)
            val archive = withContext(io) { library.archive(tab.packId) } ?: return@launch fail(PACK_UNAVAILABLE)
            // Search hits, bookmarks and last-read entries may be redirects; open the article itself so
            // relative links resolve and positions/bookmarks are keyed by its real path. An empty path (a pack
            // opened from the Library without an article) falls back to the pack's start.
            val path = (tab.path.ifEmpty { null } ?: library.startPath(tab.packId))
                ?.let { p -> withContext(io) { archive.resolvePath(p) } ?: p }
                ?: return@launch fail(NO_MAIN_PAGE)
            packId = tab.packId
            this@ReaderViewModel.archive = archive
            _state.update {
                it.copy(
                    url = ZimUrls.forEntry(tab.packId, path, fragment),
                    packTitle = archive.metadata.title.takeIf { t -> t.isNotBlank() },
                    hasHome = archive.mainPath != null,
                )
            }
        }
        viewModelScope.launch {
            page.flatMapLatest { p ->
                val pack = packId
                if (p == null || pack == null) flowOf(false) else library.isBookmarked(pack, p.path)
            }.collect { bookmarked -> _state.update { it.copy(bookmarked = bookmarked) } }
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(error = message, chromeVisible = true) }
    }

    /**
     * The WebView finished loading [url]. [documentTitle] is null when the page has none. The tab now shows this
     * page. Returns the scroll fraction to restore, or null to stay at the top (or at the URL's #fragment).
     */
    suspend fun onPageShown(url: String, documentTitle: String?): Float? {
        val pack = packId ?: return null
        val zim = ZimUrls.parse(url)?.takeIf { it.packId == pack } ?: return null
        val title = documentTitle?.takeIf { it.isNotBlank() }
            ?: zim.path.substringAfterLast('/').replace('_', ' ')
        val shown = Page(zim.path, title)
        page.value = shown
        _state.update { it.copy(url = url, page = shown) }
        appScope.launch { sessions.showInTab(tabId, pack, zim.path, title) }
        return if (zim.fragment != null) null else library.position(pack, zim.path)
    }

    fun savePosition(url: String?, scrollFraction: Float) {
        val pack = packId ?: return
        val zim = url?.let(ZimUrls::parse)?.takeIf { it.packId == pack } ?: return
        appScope.launch { library.savePosition(pack, zim.path, scrollFraction) }
    }

    fun toggleBookmark() {
        val pack = packId ?: return
        val current = page.value ?: return
        val add = !_state.value.bookmarked
        appScope.launch { library.setBookmarked(pack, current.path, current.title, add) }
    }

    /** A middle tap. Hiding the chrome also closes the find bar. Errors keep the chrome up. */
    fun toggleChrome() {
        if (_state.value.chromeVisible) searchJob?.cancel()
        _state.update {
            when {
                it.error != null -> it
                it.chromeVisible -> it.copy(chromeVisible = false, findQuery = null, packSearch = null)
                else -> it.copy(chromeVisible = true)
            }
        }
    }

    /** The left-edge swipe: always brings the chrome up (the way back in when a page has no plain content to tap). */
    fun showChrome() {
        _state.update { it.copy(chromeVisible = true) }
    }

    fun startFind() {
        searchJob?.cancel()
        _state.update { it.copy(chromeVisible = true, findQuery = it.findQuery ?: "", packSearch = null) }
    }

    fun setFindQuery(query: String) {
        _state.update { if (it.findQuery == null) it else it.copy(findQuery = query) }
    }

    fun endFind() {
        _state.update { it.copy(findQuery = null) }
    }

    /** The pack's main page (not the last-read page), or null when the pack has none. */
    suspend fun homeUrl(): String? {
        val pack = packId ?: return null
        val archive = archive ?: return null
        val main = withContext(io) { archive.mainPath } ?: return null
        return ZimUrls.forEntry(pack, main, null)
    }

    /** A random article of the pack, or null when it has none to pick from. */
    suspend fun randomUrl(): String? {
        val pack = packId ?: return null
        val archive = archive ?: return null
        val path = withContext(io) { archive.randomPath() } ?: return null
        return ZimUrls.forEntry(pack, path, null)
    }

    fun startPackSearch() {
        if (archive == null) return
        _state.update { it.copy(chromeVisible = true, findQuery = null, packSearch = it.packSearch ?: PackSearch()) }
    }

    /** Typing: after a short pause, shows title suggestions. A blank query clears the results. */
    fun setPackQuery(query: String) {
        if (_state.value.packSearch == null) return
        searchJob?.cancel()
        _state.update { it.copy(packSearch = PackSearch(query)) }
        val archive = archive ?: return
        if (query.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(SUGGEST_DEBOUNCE_MS)
            val hits = withContext(io) { resolved(archive, archive.suggest(query, SUGGESTION_LIMIT)) }
            setHits(query, hits)
        }
    }

    /** The keyboard's Search action: the suggestions first, then full-text results not already among them. */
    fun submitPackSearch() {
        val query = _state.value.packSearch?.query?.takeIf { it.isNotBlank() } ?: return
        val archive = archive ?: return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val hits = withContext(io) {
                resolved(archive, archive.suggest(query, SUGGESTION_LIMIT) + archive.search(query, FULLTEXT_LIMIT))
            }
            setHits(query, hits)
        }
    }

    /** [hits] with redirects followed, so a result opens its real article; the first hit per path stays. */
    private fun resolved(archive: ZimArchive, hits: List<ZimHit>): List<ZimHit> =
        hits.map { it.copy(path = archive.resolvePath(it.path) ?: it.path) }.distinctBy { it.path }

    private fun setHits(query: String, hits: List<ZimHit>) {
        _state.update {
            if (it.packSearch?.query == query) it.copy(packSearch = PackSearch(query, hits, searched = true)) else it
        }
    }

    fun endPackSearch() {
        searchJob?.cancel()
        _state.update { it.copy(packSearch = null) }
    }

    companion object {
        private const val SUGGEST_DEBOUNCE_MS = 200L
        private const val SUGGESTION_LIMIT = 10
        private const val FULLTEXT_LIMIT = 25

        const val PACK_UNAVAILABLE =
            "This pack is missing or damaged. Check that its file is still on the phone or SD card."
        const val NO_MAIN_PAGE = "This pack has no main page. Use Search to open an article."
        const val TAB_GONE = "This bookmark was removed."
    }
}
