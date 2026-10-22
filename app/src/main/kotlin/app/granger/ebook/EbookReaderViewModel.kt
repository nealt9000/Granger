package app.granger.ebook

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.BookTarget
import app.granger.core.library.BookAnnotation
import app.granger.core.library.Bookshelf
import app.granger.core.library.MarkKind
import app.granger.core.library.ReadingSessions
import app.granger.core.player.NowPlaying
import app.granger.player.VoiceButton
import app.granger.core.player.PlayerHub
import app.granger.core.speech.ReadSourceKey
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.Url

data class EbookUi(
    val title: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val publication: Publication? = null,
    /** Where the navigator opens. */
    val initial: Locator? = null,
    /** The top bar and the side rail. Up when the reader opens and when its tab is returned to; a middle tap hides them for full-screen reading. An error keeps them up so the user can leave. */
    val chromeVisible: Boolean = true,
    /** The place on screen now; null until the navigator reports one. */
    val locator: Locator? = null,
    val prefs: ReadingPrefs = ReadingPrefs(),
    val toc: List<TocRow> = emptyList(),
    /** The last [toc] row in the chapter file on screen; null when none matches. */
    val currentTocIndex: Int? = null,
    /** Pages in the book; 0 until Readium has counted them, and the label shows percent meanwhile. */
    val positionsCount: Int = 0,
    /** Newest first. */
    val bookmarks: List<BookAnnotation> = emptyList(),
    val highlights: List<BookAnnotation> = emptyList(),
    val isBookmarkedHere: Boolean = false,
    val panel: Panel? = null,
    /** The highlights drawn on the page; the screen applies them to the navigator whenever they change. */
    val decorations: List<Decoration> = emptyList(),
    /** Highlights whose chapter isn't in this edition: listed, but not drawn and not jumpable. */
    val unplaceable: Set<Long> = emptySet(),
    val search: BookSearchUi = BookSearchUi(),
    /** The search result last jumped to, marked on the page until the next search or the panel closes. */
    val searchHit: Locator? = null,
    /** The highlight whose sheet (colour, note, delete) is open. */
    val editingHighlight: Long? = null,
    /** Whether that sheet opens with the note field focused (a new note). */
    val focusNote: Boolean = false,
    /** The voice is loaded on this book (reading, paused or run out); Read from here moves it instead of starting it. */
    val readingThisBook: Boolean = false,
    /** The sentence being read, to decorate and follow; null when the voice is elsewhere or silent. */
    val reading: BookReading? = null,
    /** What the bottom bar's voice button does now: start, pause or resume. */
    val voice: VoiceButton = VoiceButton.IDLE,
)

/** The voice's sentence in this book: its locator (the element's, with the sentence as text), its paragraph, and whether the page follows it. */
data class BookReading(val locator: Locator, val paragraph: Int, val following: Boolean)

data class TocRow(val title: String, val href: String, val depth: Int)

enum class Panel { CONTENTS, SETTINGS, SEARCH }

/** "Chapter · p. 120 of 410", or "Chapter · 29%" while the pages are uncounted. */
object ProgressLabel {
    fun of(locator: Locator, positionsCount: Int): String {
        val chapter = locator.title?.trim().orEmpty()
        val position = locator.locations.position
        val where = if (positionsCount > 0 && position != null) {
            "p. $position of $positionsCount"
        } else {
            "${((locator.locations.totalProgression ?: 0.0) * 100).toInt().coerceIn(0, 100)}%"
        }
        return if (chapter.isEmpty()) where else "$chapter · $where"
    }
}

/**
 * One book in a reading tab. The publication is opened once and lives as long as this view model, so rotation
 * doesn't reopen it. Saved places go to [appScope], debounced, so the last one lands even after the screen is gone.
 */
class EbookReaderViewModel(
    private val tabId: Long,
    private val target: BookTarget?,
    private val bookshelf: Bookshelf,
    private val sessions: ReadingSessions,
    private val opener: BookOpener,
    private val prefsStore: ReadingPrefsStore,
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val searcher: BookSearcher = ReadiumBookSearcher(),
    /** Survives process death with the route; remembers that the route's [target] was already used. */
    private val savedState: SavedStateHandle = SavedStateHandle(),
    /** The player, for read-aloud (Plan V1); null leaves the voice out of this reader. */
    private val player: PlayerHub? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    /** Set in [onCleared]; a cleared reader no longer claims the shared singletons ([OpenBooks], [SelectionBridge]). */
    @Volatile var isCleared: Boolean = false
        private set

    private val _state = MutableStateFlow(EbookUi(prefs = prefsStore.prefs.value))
    val state: StateFlow<EbookUi> = _state.asStateFlow()

    // Unlimited, so a jump is never dropped or suspended; trySend on it always succeeds.
    private val _navigation = Channel<Locator>(Channel.UNLIMITED)

    /** Places the reader asked to jump to; the screen hands each to the navigator. */
    val navigation: Flow<Locator> = _navigation.receiveAsFlow()

    // Unlimited, so a menu tap is never dropped; the screen reads the selection and acts on each.
    private val _selectionActions = Channel<SelectionRequest>(Channel.UNLIMITED)

    /** Taps on the selection menu, for the screen to carry out with its current navigator. */
    val selectionActions: Flow<SelectionRequest> = _selectionActions.receiveAsFlow()
    private val selectionSink: (SelectionRequest) -> Unit = { _selectionActions.trySend(it) }

    private val bookmarkLock = Mutex()
    private val highlightLock = Mutex()
    private var searchJob: Job? = null

    /** Builds the navigator fragment; set with the publication, before the state shows it. */
    var navigatorFactory: EpubNavigatorFactory? = null
        private set

    /** The open book's id; set with [navigatorFactory]. */
    var bookId: String? = null
        private set
    /** The open publication; whoever takes it out closes it. */
    private val publication = AtomicReference<Publication?>(null)

    /** The place not yet written, and the debounced write that will write it. Main thread only. */
    private var unsaved: Locator? = null
    private var saveJob: Job? = null

    // Read aloud (Plan V1): one source per open book, kept so its sentence locators survive across calls.
    private var readSource: BookReadSource? = null
    private var following = true
    private var voiceMovedAt = 0L

    init {
        SelectionBridge.sink = selectionSink
        player?.let { hub ->
            viewModelScope.launch {
                combine(hub.ui, hub.voiceProgress) { ui, progress -> ui to progress }
                    .collect { (ui, progress) ->
                        val voice = ui.now as? NowPlaying.Voice
                        val key = bookId?.let(ReadSourceKey::book)
                        val mine = voice != null && key != null && voice.sourceKey == key
                        if (!mine) following = true
                        // The source is this reader's own when it started the voice, else made now: the voice may
                        // have been reading this book before this reader existed (a restore, a return to the book).
                        val reading = if (mine && progress != null) {
                            source()?.locatorOf(progress.utterance)?.let { BookReading(it, progress.utterance.paragraph, following) }
                        } else {
                            null
                        }
                        _state.update { it.copy(readingThisBook = mine, reading = reading, voice = VoiceButton.of(ui, key)) }
                    }
            }
        }
        viewModelScope.launch {
            val tab = sessions.tab(tabId) ?: return@launch fail(TAB_GONE)
            val book = bookshelf.book(tab.packId) ?: return@launch fail(BOOK_GONE)
            _state.update { it.copy(title = book.title) }
            val file = File(book.filePath)
            if (!withContext(io) { file.isFile }) return@launch fail(BOOK_GONE)
            // Not cancellable, and recorded the moment it opens, so a publication is never lost: whichever comes
            // second, the open finishing or this view model being cleared, closes it.
            val opened = try {
                withContext(io + NonCancellable) { opener.open(file)?.also(publication::set) }
            } catch (e: CancellationException) {
                publication.getAndSet(null)?.let(::closeLater)
                throw e
            } ?: return@launch fail(BOOK_DAMAGED)
            val saved = bookshelf.position(book.id)
            // The route keeps its target after process death; once used, the saved place wins, not the old search hit.
            val targetUsed = savedState.get<Boolean>(TARGET_CONSUMED) == true
            val initial = StartLocator.choose(opened, saved?.locatorJson, target.takeUnless { targetUsed })
            val factory = EpubNavigatorFactory(opened)
            bookId = book.id
            navigatorFactory = factory
            OpenBooks.current = book.id to factory
            appScope.launch { sessions.showInTab(tabId, book.id, "", book.title) }
            // A search hit's text is marked where the reader lands, as an in-book search hit is.
            val hit = initial?.takeIf { !targetUsed && target?.quote != null && it.text.highlight != null }
            _state.update {
                it.copy(loading = false, publication = opened, initial = initial, searchHit = hit, toc = flatten(opened.tableOfContents, 0)).derived()
            }
            savedState[TARGET_CONSUMED] = true
            launch { bookshelf.marks(book.id).collect(::onMarks) }
            // Counting pages can be slow for a big book; the label shows percent until it's done.
            launch {
                val count = try {
                    withContext(io) { opened.positions().size }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Granger", "Couldn't count the book's pages", e)
                    0
                }
                _state.update { it.copy(positionsCount = count) }
            }
        }
        viewModelScope.launch {
            prefsStore.prefs.collect { prefs -> _state.update { it.copy(prefs = prefs) } }
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(loading = false, error = message, chromeVisible = true) }
    }

    /**
     * The navigator moved; the place is saved once it has stayed put for a second. Ignored when the screen isn't
     * [onScreen] (below STARTED): a navigator on its way out (Back, Home, rotation) re-lays out its page as its view
     * goes, and reports a place up to a page before the one the reader left.
     */
    fun onLocator(locator: Locator, onScreen: Boolean = true) {
        if (!onScreen) return
        _state.update { it.copy(locator = locator).derived() }
        val id = bookId ?: return
        unsaved = locator
        saveJob?.cancel()
        // On Main, which owns unsaved and saveJob; Bookshelf does the write on IO.
        saveJob = appScope.launch(Dispatchers.Main.immediate) {
            delay(SAVE_DEBOUNCE_MS)
            save(id, locator)
        }
    }

    /** Saves a pending place now: the app is going to the background. */
    fun flush() {
        val id = bookId ?: return
        val locator = unsaved ?: return
        saveJob?.cancel()
        saveJob = appScope.launch(Dispatchers.Main.immediate) { save(id, locator) }
    }

    private suspend fun save(id: String, locator: Locator) {
        if (unsaved === locator) unsaved = null
        bookshelf.savePosition(
            id,
            locator.toJSON().toString(),
            locator.locations.totalProgression ?: 0.0,
            locator.title.orEmpty(),
        )
    }

    private fun flatten(links: List<Link>, depth: Int): List<TocRow> =
        links.flatMap { listOf(TocRow(it.title.orEmpty().trim(), it.href.toString(), depth)) + flatten(it.children, depth + 1) }

    private fun onMarks(marks: List<BookAnnotation>) {
        _state.update {
            val highlights = marks.filter { m -> m.kind == MarkKind.HIGHLIGHT }
            val placed = place(it.publication, highlights)
            it.copy(
                bookmarks = marks.filter { m -> m.kind == MarkKind.BOOKMARK }.sortedWith(compareByDescending<BookAnnotation> { m -> m.createdAt }.thenByDescending { m -> m.id }),
                highlights = highlights,
                decorations = placed.decorations,
                unplaceable = placed.unplaceable,
            ).derived()
        }
    }

    /** Fills in what follows from the place, the contents and the bookmarks. */
    private fun EbookUi.derived(): EbookUi {
        val here = locator ?: return copy(currentTocIndex = null, isBookmarkedHere = false)
        val index = toc.indexOfLast { row -> Url(row.href)?.let { sameFile(it, here.href) } == true }.takeIf { it >= 0 }
        val marked = bookmarks.any { m -> parse(m.locatorJson)?.let { isSamePlace(it, here) } == true }
        return copy(currentTocIndex = index, isBookmarkedHere = marked)
    }

    private fun parse(json: String): Locator? = parseLocator(json)

    /**
     * Adds a bookmark at the current place, or removes the one here. Serialised, and decided from the stored
     * marks rather than the screen's copy, so a double tap can't add twice.
     */
    fun toggleBookmark() {
        val id = bookId ?: return
        val here = _state.value.locator ?: return
        appScope.launch {
            bookmarkLock.withLock {
                val existing = bookshelf.marks(id).first().filter { m ->
                    m.kind == MarkKind.BOOKMARK && parse(m.locatorJson)?.let { isSamePlace(it, here) } == true
                }
                if (existing.isNotEmpty()) {
                    existing.forEach { bookshelf.deleteMark(it.id) }
                } else {
                    val now = System.currentTimeMillis()
                    bookshelf.addMark(
                        BookAnnotation(0, id, MarkKind.BOOKMARK, here.toJSON().toString(), here.title.orEmpty(), "", "", now, now),
                    )
                }
            }
        }
    }

    /** A contents row: the chapter file it points into, with its #fragment. Not in the reading order: ignored. */
    fun goTo(href: String) {
        val target = Url(href) ?: return
        val link = _state.value.publication?.chapterFor(target) ?: return
        val base = _state.value.publication?.locatorFromLink(link) ?: return
        val fragment = target.fragment
        val locator = if (fragment == null) base else base.copy(locations = base.locations.copy(fragments = listOf(fragment)))
        jump(locator)
    }

    /** The slider's release: [p] is the share of the whole book. */
    fun goToProgression(p: Double) {
        val opened = _state.value.publication ?: return
        viewModelScope.launch {
            val locator = try {
                opened.locateProgression(p.coerceIn(0.0, 1.0))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (locator != null) _navigation.trySend(locator)
        }
    }

    /** A bookmark or highlight. One whose chapter a re-release renamed is placed as [StartLocator.repair] does; unplaceable: ignored. */
    fun goToMark(mark: BookAnnotation) {
        if (mark.id in _state.value.unplaceable) return
        val opened = _state.value.publication ?: return
        val stored = parse(mark.locatorJson) ?: return
        // Main.immediate: a mark in this edition jumps at once, so back-to-back taps keep their order.
        viewModelScope.launch { StartLocator.repair(opened, stored)?.let(::jump) }
    }

    /**
     * Saves the selection as a highlight and returns its new id. Serialised with the other highlight writes, and
     * run on [appScope], so leaving the screen mid-write doesn't lose it.
     */
    suspend fun highlight(locator: Locator, text: String, color: String = "yellow"): Long {
        val id = bookId ?: return -1L
        return appScope.async {
            highlightLock.withLock {
                val now = System.currentTimeMillis()
                bookshelf.addMark(
                    BookAnnotation(0, id, MarkKind.HIGHLIGHT, locator.toJSON().toString(), text, color, "", now, now),
                )
            }
        }.await()
    }

    /** A yellow highlight with its note editor open. */
    suspend fun note(locator: Locator, text: String) {
        val id = highlight(locator, text)
        if (id >= 0) _state.update { it.copy(editingHighlight = id, focusNote = true) }
    }

    /** Opens (or closes, with null) the sheet for a highlight drawn on the page. */
    fun editHighlight(id: Long?) {
        _state.update { it.copy(editingHighlight = id, focusNote = false) }
    }

    /** Saves a changed colour or note. */
    fun updateHighlight(mark: BookAnnotation) {
        appScope.launch {
            highlightLock.withLock { bookshelf.updateMark(mark.copy(updatedAt = System.currentTimeMillis())) }
        }
    }

    fun deleteHighlight(id: Long) {
        _state.update { if (it.editingHighlight == id) it.copy(editingHighlight = null) else it }
        appScope.launch { highlightLock.withLock { bookshelf.deleteMark(id) } }
    }

    /** Searches the book for [query]; any search still running is dropped. A blank query clears the results. */
    fun search(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        _state.update { it.copy(search = BookSearchUi(query = query), searchHit = null) }
        val opened = _state.value.publication
        if (trimmed.isEmpty() || opened == null) return
        _state.update { it.copy(search = it.search.copy(running = true)) }
        searchJob = viewModelScope.launch {
            val hits = try {
                withContext(io) { searcher.search(opened, trimmed) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Granger", "Searching the book failed", e)
                emptyList()
            }
            _state.update {
                it.copy(search = BookSearchUi(query, SearchRows.build(trimmed, it.highlights, hits, it.toc), running = false))
            }
        }
    }

    /** A search result: jump there and mark the match on the page. */
    fun goToHit(row: BookSearchRow) {
        val locator = row.locator ?: return
        if (row.markId != null && row.markId in _state.value.unplaceable) return
        _state.update { it.copy(panel = null, searchHit = if (row.markId == null) locator else null) }
        _navigation.trySend(locator)
    }

    private fun jump(locator: Locator) {
        _state.update { it.copy(panel = null) }
        _navigation.trySend(locator)
    }

    fun open(panel: Panel?) {
        _state.update { it.copy(panel = panel, searchHit = null) }
    }

    fun updatePrefs(prefs: ReadingPrefs) = prefsStore.update(prefs)

    /** A middle tap. Errors keep the chrome up. */
    fun toggleChrome() {
        _state.update { if (it.error != null) it else it.copy(chromeVisible = !it.chromeVisible) }
    }

    /** The shell's edge swipe. */
    fun showChrome() {
        _state.update { it.copy(chromeVisible = true) }
    }

    /**
     * The screen went away (another screen on top, or the tab left), not a rotation: coming back to this tab shows
     * the chrome again, as opening it does.
     */
    fun onScreenLeft() = showChrome()

    // ---- read aloud (Plan V1) ----

    private fun source(): BookReadSource? {
        val id = bookId ?: return null
        val opened = _state.value.publication ?: return null
        return readSource?.takeIf { it.bookId == id } ?: BookReadSource(opened, id, _state.value.title, io).also { readSource = it }
    }

    /**
     * Starts the voice at the sentence holding [visibleText] (the page's first words) in [from]'s chapter; without
     * the words, at the first element at [from] (a chapter's start), else where the book is open.
     */
    fun readAloud(from: Locator?, visibleText: String? = null) {
        val hub = player ?: return
        val src = source() ?: return
        viewModelScope.launch {
            val page = from ?: _state.value.locator ?: _state.value.initial
            val address = visibleText?.let { words -> page?.let { src.addressAtText(it, words) } }
                ?: src.addressAt(page)
                ?: return@launch
            following = true
            hub.readAloud(src, address)
        }
    }

    /** Read from here: the selection's sentence; moves the voice when it already reads this book, else starts it. */
    fun readFrom(selection: Locator) {
        val hub = player ?: return
        val src = source() ?: return
        viewModelScope.launch {
            val address = src.addressAt(selection) ?: return@launch
            following = true
            if (_state.value.readingThisBook) hub.jumpTo(address) else hub.readAloud(src, address)
        }
    }

    fun stopReading() { player?.stopVoice() }

    /** The bar's button while this book is read: pauses the voice, or resumes it at its sentence. */
    fun pauseResume() { if (_state.value.readingThisBook) player?.playPause() }

    /** The screen is about to turn the page to the voice's sentence: the page change that follows is not the user's. */
    fun voiceMoved() { voiceMovedAt = now() }

    /**
     * The page changed. Within [VOICE_MOVE_GRACE_MS] of [voiceMoved] it is the voice's own turn; otherwise the user
     * turned a page or scrolled, and the page stops following the voice until [follow].
     */
    fun onUserMoved() {
        if (!following || _state.value.reading == null) return
        if (now() - voiceMovedAt <= VOICE_MOVE_GRACE_MS) return
        following = false
        _state.update { s -> s.copy(reading = s.reading?.copy(following = false)) }
    }

    fun follow() {
        following = true
        _state.update { s -> s.copy(reading = s.reading?.copy(following = true)) }
    }

    /** Makes this reader the one the selection menu reaches; called whenever its screen is shown. */
    fun claimSelection() {
        if (isCleared) return
        SelectionBridge.sink = selectionSink
    }

    override fun onCleared() {
        isCleared = true
        if (SelectionBridge.sink === selectionSink) SelectionBridge.sink = null
        navigatorFactory?.let(OpenBooks::release)
        publication.getAndSet(null)?.let(::closeLater)
    }

    /** Off the main thread: closing the publication closes its zip. */
    private fun closeLater(opened: Publication) {
        appScope.launch(io) { opened.close() }
    }

    companion object {
        const val TAB_GONE = "This bookmark was removed."
        const val BOOK_GONE = "This book isn't on the phone any more. Check the SD card, or download it again from Granger."
        const val BOOK_DAMAGED = "This book couldn't be opened. Try removing it, then download or import it again."
        const val SAVE_DEBOUNCE_MS = 1_000L
        /** A page change this soon after the voice asked for one is the voice's, not the user's. */
        const val VOICE_MOVE_GRACE_MS = 1_500L
        const val TARGET_CONSUMED = "targetConsumed"
    }
}
