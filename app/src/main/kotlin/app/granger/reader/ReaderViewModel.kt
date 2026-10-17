package app.granger.reader

import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.library.Library
import app.granger.core.library.ReadingSessions
import app.granger.core.zim.ZimUrls
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
            _state.update { it.copy(url = ZimUrls.forEntry(tab.packId, path, fragment)) }
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
        _state.update {
            when {
                it.error != null -> it
                it.chromeVisible -> it.copy(chromeVisible = false, findQuery = null)
                else -> it.copy(chromeVisible = true)
            }
        }
    }

    /** The left-edge swipe: always brings the chrome up (the way back in when a page has no plain content to tap). */
    fun showChrome() {
        _state.update { it.copy(chromeVisible = true) }
    }

    fun startFind() {
        _state.update { it.copy(chromeVisible = true, findQuery = it.findQuery ?: "") }
    }

    fun setFindQuery(query: String) {
        _state.update { if (it.findQuery == null) it else it.copy(findQuery = query) }
    }

    fun endFind() {
        _state.update { it.copy(findQuery = null) }
    }

    companion object {
        const val PACK_UNAVAILABLE =
            "This pack is missing or damaged. Check that its file is still on the phone or SD card."
        const val NO_MAIN_PAGE = "This pack has no main page. Use Search to open an article."
        const val TAB_GONE = "This bookmark was removed."
    }
}
