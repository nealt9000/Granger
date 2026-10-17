package app.granger.ebook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.BookTarget
import app.granger.core.library.Bookshelf
import app.granger.core.library.ReadingSessions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import java.io.File

data class EbookUi(
    val title: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val publication: Publication? = null,
    /** Where the navigator opens. */
    val initial: Locator? = null,
    /** The top bar and the side rail. Hidden while reading; an error keeps them up so the user can leave. */
    val chromeVisible: Boolean = false,
    /** The place on screen now; null until the navigator reports one. */
    val locator: Locator? = null,
    val prefs: ReadingPrefs = ReadingPrefs(),
)

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
) : ViewModel() {
    private val _state = MutableStateFlow(EbookUi(prefs = prefsStore.prefs.value))
    val state: StateFlow<EbookUi> = _state.asStateFlow()

    /** Builds the navigator fragment; set with the publication, before the state shows it. */
    var navigatorFactory: EpubNavigatorFactory? = null
        private set

    /** The open book's id; set with [navigatorFactory]. */
    var bookId: String? = null
        private set
    private var publication: Publication? = null

    /** The place not yet written, and the debounced write that will write it. */
    @Volatile private var unsaved: Locator? = null
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            val tab = sessions.tab(tabId) ?: return@launch fail(TAB_GONE)
            val book = bookshelf.book(tab.packId) ?: return@launch fail(BOOK_GONE)
            _state.update { it.copy(title = book.title) }
            val file = File(book.filePath)
            if (!withContext(io) { file.isFile }) return@launch fail(BOOK_GONE)
            val opened = withContext(io) { opener.open(file) } ?: return@launch fail(BOOK_DAMAGED)
            val saved = bookshelf.position(book.id)
            val initial = StartLocator.choose(opened, saved?.locatorJson, target)
            val factory = EpubNavigatorFactory(opened)
            publication = opened
            bookId = book.id
            navigatorFactory = factory
            OpenBooks.current = book.id to factory
            appScope.launch { sessions.showInTab(tabId, book.id, "", book.title) }
            _state.update { it.copy(loading = false, publication = opened, initial = initial) }
        }
        viewModelScope.launch {
            prefsStore.prefs.collect { prefs -> _state.update { it.copy(prefs = prefs) } }
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(loading = false, error = message, chromeVisible = true) }
    }

    /** The navigator moved; the place is saved once it has stayed put for a second. */
    fun onLocator(locator: Locator) {
        _state.update { it.copy(locator = locator) }
        val id = bookId ?: return
        unsaved = locator
        saveJob?.cancel()
        saveJob = appScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            save(id, locator)
        }
    }

    /** Saves a pending place now: the app is going to the background. */
    fun flush() {
        val id = bookId ?: return
        val locator = unsaved ?: return
        saveJob?.cancel()
        saveJob = appScope.launch { save(id, locator) }
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

    /** A middle tap. Errors keep the chrome up. */
    fun toggleChrome() {
        _state.update { if (it.error != null) it else it.copy(chromeVisible = !it.chromeVisible) }
    }

    /** The shell's edge swipe. */
    fun showChrome() {
        _state.update { it.copy(chromeVisible = true) }
    }

    override fun onCleared() {
        navigatorFactory?.let(OpenBooks::release)
        // Off the main thread: closing the publication closes its zip.
        publication?.let { opened -> appScope.launch(io) { opened.close() } }
    }

    companion object {
        const val TAB_GONE = "This bookmark was removed."
        const val BOOK_GONE = "This book isn't on the phone any more. Check the SD card, or download it again from Granger."
        const val BOOK_DAMAGED = "This book couldn't be opened. Try removing it, then download or import it again."
        const val SAVE_DEBOUNCE_MS = 1_000L
    }
}
