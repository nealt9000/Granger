package app.granger.reader

import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.library.Library
import app.granger.core.library.ReadingSessions
import app.granger.core.player.NowPlaying
import app.granger.player.VoiceButton
import app.granger.core.player.PlayerHub
import app.granger.core.research.ArticleText
import app.granger.core.speech.ReadAddress
import app.granger.core.speech.ReadSourceKey
import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimHit
import app.granger.core.zim.ZimUrls
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One reading tab: the article it shows and the pages reached by following links from it. Writes (positions,
 * bookmarks, the tab's current page) go to [appScope] so they finish even when the user leaves the screen first.
 * [fragment] scrolls the first page to that section (a cited passage's heading); [quote] marks the cited sentence in
 * it, until the reader goes to another page.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel(
    private val tabId: Long,
    private val library: Library,
    private val sessions: ReadingSessions,
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val fragment: String? = null,
    /** The player, for read-aloud (Plan V1); null leaves the voice out of this reader. */
    private val player: PlayerHub? = null,
    private val quote: String? = null,
) : ViewModel() {
    data class Page(val path: String, val title: String)

    /** The sentence the voice is reading on this page: its block and character range, and whether the page follows it. */
    data class Reading(val address: ReadAddress.Article, val prefix: String, val start: Int, val end: Int, val following: Boolean)

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
        /** The top bar and the side rail. Up when the reader opens and when its tab is returned to; a middle tap hides them for full-screen reading. An error keeps them up so the user can leave. */
        val chromeVisible: Boolean = true,
        /** Find in page: null when the find bar is closed. */
        val findQuery: String? = null,
        /** Search this pack: null when closed. */
        val packSearch: PackSearch? = null,
        /** The pack's title for the search placeholder; null when unknown. */
        val packTitle: String? = null,
        /** Whether the pack has a main page, so the overflow menu can offer Home. */
        val hasHome: Boolean = false,
        /** The voice is loaded on the page shown (reading, paused or run out); Read from here moves it instead of starting it. */
        val readingThisPage: Boolean = false,
        /** The sentence being read on this page, to mark and follow; null when the voice is elsewhere or silent. */
        val reading: Reading? = null,
        /** What the ⋯ menu's voice entry does now: start, pause or resume. */
        val voice: VoiceButton = VoiceButton.IDLE,
        /** The cited quote on the first page (research spec §7), to mark; null once the reader leaves that page. */
        val cite: CiteRange? = null,
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
    private val stackWrites = Mutex()
    private var source: ArticleReadSource? = null
    private var following = true
    /** The page [State.cite] belongs to; null once it's been left. */
    private var citedPath: String? = null

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
            if (!quote.isNullOrBlank()) {
                citedPath = path
                val cite = withContext(io) { citeIn(archive, path, quote) }
                if (citedPath == path) _state.update { it.copy(cite = cite) }
            }
        }
        viewModelScope.launch {
            page.flatMapLatest { p ->
                val pack = packId
                if (p == null || pack == null) flowOf(false) else library.isBookmarked(pack, p.path)
            }.collect { bookmarked -> _state.update { it.copy(bookmarked = bookmarked) } }
        }
        player?.let { hub ->
            viewModelScope.launch {
                combine(hub.ui, hub.voiceProgress, page) { ui, progress, p -> Triple(ui, progress, p) }
                    .collect { (ui, progress, p) ->
                        val voice = ui.now as? NowPlaying.Voice
                        val pack = packId
                        val key = if (p != null && pack != null) ReadSourceKey.article(pack, p.path) else null
                        val mine = voice != null && key != null && voice.sourceKey == key
                        if (!mine) following = true
                        val address = progress?.address as? ReadAddress.Article
                        val reading = if (mine && progress != null && address != null && address.path == p?.path) {
                            Reading(address, if (progress.utterance.start == 0) progress.utterance.text.take(40) else "", progress.utterance.start, progress.utterance.end, following)
                        } else {
                            null
                        }
                        _state.update { it.copy(readingThisPage = mine, reading = reading, voice = VoiceButton.of(ui, key)) }
                    }
            }
        }
    }

    // ---- read aloud (Plan V1) ----

    private fun sourceFor(pack: String, p: Page, archive: ZimArchive): ArticleReadSource =
        source?.takeIf { it.packId == pack && it.path == p.path } ?: ArticleReadSource(archive, pack, p.path, p.title, io).also { source = it }

    /** Starts the voice on this page at [from] (a block the page named), else at the article's first sentence. */
    fun readAloud(from: BlockRef?) {
        val hub = player ?: return
        val pack = packId ?: return
        val p = page.value ?: return
        val archive = archive ?: return
        viewModelScope.launch {
            val src = sourceFor(pack, p, archive)
            val address = from?.let { src.addressAt(it.id, it.offset) } ?: src.firstAddress() ?: return@launch
            following = true
            hub.readAloud(src, address)
        }
    }

    /** Read from here: moves the voice within this page when it is already reading it, else starts it there. */
    fun readFrom(ref: BlockRef) {
        val hub = player ?: return
        if (!_state.value.readingThisPage) return readAloud(ref)
        val pack = packId ?: return
        val p = page.value ?: return
        val archive = archive ?: return
        viewModelScope.launch {
            val address = sourceFor(pack, p, archive).addressAt(ref.id, ref.offset) ?: return@launch
            following = true
            hub.jumpTo(address)
        }
    }

    fun stopReading() { player?.stopVoice() }

    /** The menu's entry while this page is read: pauses the voice, or resumes it at its sentence. */
    fun pauseResume() { if (_state.value.readingThisPage) player?.playPause() }

    /** The user scrolled: the page stops following the voice until [follow]. */
    fun onUserScroll() {
        if (!following || _state.value.reading == null) return
        following = false
        _state.update { s -> s.copy(reading = s.reading?.copy(following = false)) }
    }

    fun follow() {
        following = true
        _state.update { s -> s.copy(reading = s.reading?.copy(following = true)) }
    }

    /** Where [quote] is on [path]'s page; null when the page can't be read or the quote isn't on it. */
    private fun citeIn(archive: ZimArchive, path: String, quote: String): CiteRange? = try {
        val item = archive.read(path)
        val html = item?.let { String(it.bytes, ArticleReadSource.charsetOf(it.mimeType)) }.orEmpty()
        ArticleQuote.locate(ArticleText.blocks(html), fragment, quote)
    } catch (e: Exception) {
        null
    }

    private fun fail(message: String) {
        _state.update { it.copy(error = message, chromeVisible = true) }
    }

    /**
     * The WebView finished loading [url]. [documentTitle] is null when the page has none. The tab now shows this
     * page, on top of its back stack. Returns the scroll fraction to restore, or null to stay at the top (or at the URL's #fragment).
     */
    suspend fun onPageShown(url: String, documentTitle: String?): Float? {
        val pack = packId ?: return null
        val zim = ZimUrls.parse(url)?.takeIf { it.packId == pack } ?: return null
        val title = documentTitle?.takeIf { it.isNotBlank() } ?: titleFromPath(zim.path)
        val shown = Page(zim.path, title)
        page.value = shown
        if (citedPath != null && citedPath != zim.path) citedPath = null
        _state.update { it.copy(url = url, page = shown, cite = it.cite.takeIf { citedPath != null }) }
        writeStack { sessions.pushPath(tabId, zim.path, title) }
        return if (zim.fragment != null) null else library.position(pack, zim.path)
    }

    /**
     * The WebView committed a navigation to [url] (its history changed), before the page has finished loading. A new
     * page goes on the tab's back stack now, so Back pressed while it loads returns to the page the link was on; a
     * jump within the page shown isn't a new page.
     */
    fun onPageVisited(url: String) {
        val pack = packId ?: return
        val zim = ZimUrls.parse(url)?.takeIf { it.packId == pack } ?: return
        if (zim.path == page.value?.path) return
        // Following a link while the voice reads this page stops the voice (spec §4.1).
        if (_state.value.readingThisPage) player?.stopVoice()
        writeStack { sessions.pushPath(tabId, zim.path, titleFromPath(zim.path)) }
    }

    /** What Back does: see [back]. */
    sealed interface BackStep {
        /** Go back in the WebView's own history. */
        data object InWebView : BackStep

        /** Load the tab's previous page, from its stored back stack. */
        data class Load(val url: String) : BackStep

        /** At the tab's first page: leave the reader. */
        data object Leave : BackStep
    }

    /**
     * Back, from the ← arrow or the system: the WebView's own history while it has one, then the tab's stored back
     * stack (the WebView's history is lost with the reader's destination and with the process), then leaving.
     * [webPrevious] is the URL the WebView would go back to, or null when it can't go back; [webCurrent] is the one it
     * shows. The stored stack steps back with the WebView, except out of a jump within the same page.
     */
    suspend fun back(webCurrent: String?, webPrevious: String?): BackStep {
        if (webPrevious != null) {
            val samePage = webCurrent != null && ZimUrls.parse(webPrevious)?.path?.let { it == ZimUrls.parse(webCurrent)?.path } == true
            if (!samePage) writeStack { sessions.popPath(tabId) }.await()
            return BackStep.InWebView
        }
        val pack = packId ?: return BackStep.Leave
        val previous = writeStack { sessions.popPath(tabId) }.await() ?: return BackStep.Leave
        return BackStep.Load(ZimUrls.forEntry(pack, previous, null))
    }

    /**
     * Runs a write to the tab's back stack in [appScope], so it finishes when the screen goes, after every write
     * asked for before it: each starts on the caller's (main) thread and queues on [stackWrites] there, in order.
     */
    private fun <T> writeStack(write: suspend () -> T): Deferred<T> =
        appScope.async(start = CoroutineStart.UNDISPATCHED) { stackWrites.withLock { write() } }

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

    /**
     * The screen went away (another screen on top, or the tab left), not a rotation: coming back to this tab shows
     * the chrome again, as opening it does.
     */
    fun onScreenLeft() = showChrome()

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
        /** A page's title when it has none of its own: the last part of its path, with spaces for underscores. */
        private fun titleFromPath(path: String) = path.substringAfterLast('/').replace('_', ' ')

        private const val SUGGEST_DEBOUNCE_MS = 200L
        private const val SUGGESTION_LIMIT = 10
        private const val FULLTEXT_LIMIT = 25

        const val PACK_UNAVAILABLE =
            "This pack is missing or damaged. Check that its file is still on the phone or SD card."
        const val NO_MAIN_PAGE = "This pack has no main page. Use Search to open an article."
        const val TAB_GONE = "This bookmark was removed."
    }
}
