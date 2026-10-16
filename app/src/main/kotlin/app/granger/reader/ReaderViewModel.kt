package app.granger.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.library.Library
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
 * One reader session inside one pack. Writes (positions, bookmarks) go to [appScope] so they finish
 * even when the user leaves the screen before they complete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel(
    private val packId: String,
    private val requestedPath: String?,
    private val library: Library,
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    data class Page(val path: String, val title: String)

    data class State(
        /** The URL a newly created WebView should load: the start page, then the current page. */
        val url: String? = null,
        val page: Page? = null,
        val bookmarked: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val page = MutableStateFlow<Page?>(null)

    init {
        viewModelScope.launch {
            val archive = withContext(io) { library.archive(packId) }
            if (archive == null) {
                _state.update { it.copy(error = PACK_UNAVAILABLE) }
                return@launch
            }
            // Search hits, bookmarks and last-read entries may be redirects; open the article itself so
            // relative links resolve and positions/bookmarks are keyed by its real path.
            val path = (requestedPath ?: library.startPath(packId))
                ?.let { p -> withContext(io) { archive.resolvePath(p) } ?: p }
            if (path == null) {
                _state.update { it.copy(error = NO_MAIN_PAGE) }
                return@launch
            }
            _state.update { it.copy(url = ZimUrls.forEntry(packId, path)) }
        }
        viewModelScope.launch {
            page.flatMapLatest { p -> if (p == null) flowOf(false) else library.isBookmarked(packId, p.path) }
                .collect { bookmarked -> _state.update { it.copy(bookmarked = bookmarked) } }
        }
    }

    /**
     * The WebView finished loading [url]. [documentTitle] is null when the page has none.
     * Returns the scroll fraction to restore, or null to stay at the top (or at the URL's #fragment).
     */
    suspend fun onPageShown(url: String, documentTitle: String?): Float? {
        val zim = ZimUrls.parse(url)?.takeIf { it.packId == packId } ?: return null
        val title = documentTitle?.takeIf { it.isNotBlank() }
            ?: zim.path.substringAfterLast('/').replace('_', ' ')
        val shown = Page(zim.path, title)
        page.value = shown
        _state.update { it.copy(url = url, page = shown) }
        return if (zim.fragment != null) null else library.position(packId, zim.path)
    }

    fun savePosition(url: String?, scrollFraction: Float) {
        val zim = url?.let(ZimUrls::parse)?.takeIf { it.packId == packId } ?: return
        appScope.launch { library.savePosition(packId, zim.path, scrollFraction) }
    }

    fun toggleBookmark() {
        val current = page.value ?: return
        val add = !_state.value.bookmarked
        appScope.launch { library.setBookmarked(packId, current.path, current.title, add) }
    }

    companion object {
        const val PACK_UNAVAILABLE =
            "This pack is missing or damaged. Check that its file is still on the phone or SD card."
        const val NO_MAIN_PAGE = "This pack has no main page. Use Search to open an article."
    }
}
