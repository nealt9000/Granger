package app.granger.ebook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.library.BookAnnotation
import app.granger.core.library.Bookshelf
import app.granger.core.library.MarkKind
import app.granger.screens.ioDispatcher
import app.granger.screens.logWarn
import app.granger.screens.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The e-book reader's state that names no reader types (Plan I5 Task 2): the marks, the search panel, a highlight's
 * sheet, the contents and the text settings. The iPhone's reader draws this; Android's keeps its own `EbookUi`, which
 * also holds Readium's publication, locators and decorations, and is told through [EbookReaderModel]'s hooks.
 */
data class EbookReaderState(
    val bookId: String? = null,
    val prefs: ReadingPrefs = ReadingPrefs(),
    val toc: List<TocRow> = emptyList(),
    /** Newest first. */
    val bookmarks: List<BookAnnotation> = emptyList(),
    val highlights: List<BookAnnotation> = emptyList(),
    val search: BookSearchUi = BookSearchUi(),
    /** The highlight whose sheet (colour, note, delete) is open. */
    val editingHighlight: Long? = null,
    /** Whether that sheet opens with the note field focused (a new note). */
    val focusNote: Boolean = false,
    val panel: Panel? = null,
)

/**
 * The e-book reader's model without the reader's own types (Plan I5 Task 2): highlights and notes (add, edit, delete),
 * bookmarks, search in the book through a [BookSearcher], and the text settings. Places cross as the reader's locator
 * JSON (what [Bookshelf] keeps in `BookAnnotation.locatorJson`); contents rows as hrefs.
 *
 * Android's `EbookReaderViewModel` extends it, keeping the Readium navigator, the publication and its `EbookUi`; it
 * overrides the hooks ([showSearch], [showEditing], …) to put the results there. The iPhone uses it as it is: [openBook]
 * starts it, and [reader] is its state. Mark writes run on [appScope], so leaving the screen mid-write doesn't lose one.
 */
open class EbookReaderModel(
    private val bookshelf: Bookshelf,
    private val readingPrefs: ReadingPrefsStore,
    private val appScope: CoroutineScope,
    private val searchIo: CoroutineDispatcher = ioDispatcher,
    private val clock: () -> Long = ::nowMillis,
) : ViewModel() {
    /** The open book's id; null until it has opened. */
    var bookId: String? = null
        protected set

    /** Searches the open book; null until it has opened (and for a book that can't be searched). */
    protected var bookSearcher: BookSearcher? = null

    private val shared = MutableStateFlow(EbookReaderState(prefs = readingPrefs.prefs.value))

    /** The shared state ([openBook]); Android's reader shows its own `EbookUi` instead. */
    val reader: StateFlow<EbookReaderState> = shared.asStateFlow()

    private val bookmarkLock = Mutex()
    private val highlightLock = Mutex()
    private var searchJob: Job? = null

    /**
     * The iPhone's start: the book [id] is open, searched through [searcher], with its contents [toc]. Its marks and the
     * text settings follow into [reader] from now on.
     */
    fun openBook(id: String, searcher: BookSearcher?, toc: List<TocRow> = emptyList()) {
        bookId = id
        bookSearcher = searcher
        shared.update { it.copy(bookId = id, toc = toc) }
        viewModelScope.launch { bookshelf.marks(id).collect { marks -> ReaderMarks.of(marks).let { m -> shared.update { it.copy(bookmarks = m.bookmarks, highlights = m.highlights) } } } }
        viewModelScope.launch { readingPrefs.prefs.collect { p -> shared.update { it.copy(prefs = p) } } }
    }

    /**
     * Saves the selection at [locatorJson] as a highlight and returns its new id (-1 with no book open). Serialised with
     * the other highlight writes.
     */
    suspend fun highlight(locatorJson: String, text: String, color: String = "yellow"): Long {
        val id = bookId ?: return -1L
        return appScope.async {
            highlightLock.withLock {
                val now = clock()
                bookshelf.addMark(BookAnnotation(0, id, MarkKind.HIGHLIGHT, locatorJson, text, color, "", now, now))
            }
        }.await()
    }

    /** A yellow highlight with its note editor open; its id, or -1. */
    suspend fun note(locatorJson: String, text: String): Long {
        val id = highlight(locatorJson, text)
        if (id >= 0) showEditing(id, focusNote = true)
        return id
    }

    /** Opens (or closes, with null) the sheet for a highlight drawn on the page. */
    fun editHighlight(id: Long?) = showEditing(id, focusNote = false)

    /** Saves a changed colour or note. */
    fun updateHighlight(mark: BookAnnotation) {
        appScope.launch {
            highlightLock.withLock { bookshelf.updateMark(mark.copy(updatedAt = clock())) }
        }
    }

    fun deleteHighlight(id: Long) {
        highlightDeleted(id)
        appScope.launch { highlightLock.withLock { bookshelf.deleteMark(id) } }
    }

    /**
     * Adds a bookmark at [hereJson] titled [text], or removes the ones [isHere] says are this place. Serialised, and
     * decided from the stored marks rather than the screen's copy, so a double tap can't add twice.
     */
    fun toggleBookmark(hereJson: String, text: () -> String, isHere: (locatorJson: String) -> Boolean) {
        val id = bookId ?: return
        appScope.launch {
            bookmarkLock.withLock {
                val existing = bookshelf.marks(id).first().filter { m -> m.kind == MarkKind.BOOKMARK && isHere(m.locatorJson) }
                if (existing.isNotEmpty()) {
                    existing.forEach { bookshelf.deleteMark(it.id) }
                } else {
                    val now = clock()
                    bookshelf.addMark(BookAnnotation(0, id, MarkKind.BOOKMARK, hereJson, text(), "", "", now, now))
                }
            }
        }
    }

    /** Searches the book for [query]; any search still running is dropped. A blank query clears the results. */
    fun search(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        showSearch(BookSearchUi(query = query), started = true)
        val searcher = bookSearcher
        if (trimmed.isEmpty() || searcher == null) return
        showSearch(currentSearch().copy(running = true), started = false)
        searchJob = viewModelScope.launch {
            val hits = try {
                withContext(searchIo) { searcher.search(trimmed) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logWarn("Searching the book failed", e)
                emptyList()
            }
            showSearch(BookSearchUi(query, SearchRows.build(trimmed, currentHighlights(), hits, currentToc(), ::sameFile), running = false), started = false)
        }
    }

    fun updatePrefs(prefs: ReadingPrefs) = readingPrefs.update(prefs)

    /** Opens a side panel, or closes it with null. */
    open fun open(panel: Panel?) {
        shared.update { it.copy(panel = panel) }
    }

    // ---- where the results go: the shared state here, Android's EbookUi in its reader ----

    /** The search panel now. */
    protected open fun currentSearch(): BookSearchUi = shared.value.search

    /** The search panel changed; [started] when a new search began (Android clears the marked hit then). */
    protected open fun showSearch(ui: BookSearchUi, started: Boolean) {
        shared.update { it.copy(search = ui) }
    }

    protected open fun currentHighlights(): List<BookAnnotation> = shared.value.highlights

    protected open fun currentToc(): List<TocRow> = shared.value.toc

    /** Whether two hrefs name the same file, ignoring #fragments (Android compares them as Readium's Urls). */
    protected open fun sameFile(a: String, b: String): Boolean = a.substringBefore('#') == b.substringBefore('#')

    /** A highlight's sheet opens ([id]), or closes (null). */
    protected open fun showEditing(id: Long?, focusNote: Boolean) {
        shared.update { it.copy(editingHighlight = id, focusNote = focusNote) }
    }

    /** The highlight [id] is being deleted: its sheet closes if it's open. */
    protected open fun highlightDeleted(id: Long) {
        shared.update { if (it.editingHighlight == id) it.copy(editingHighlight = null) else it }
    }
}
