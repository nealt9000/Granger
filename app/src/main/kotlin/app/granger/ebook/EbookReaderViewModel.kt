package app.granger.ebook

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.BookTarget
import app.granger.core.books.OutlineEntry
import app.granger.core.books.PdfInspector
import app.granger.core.books.PdfLink
import app.granger.core.books.PdfLinks
import app.granger.core.library.Book
import app.granger.core.library.BookAnnotation
import app.granger.core.library.Bookshelf
import app.granger.core.library.MarkKind
import app.granger.core.library.ReadingSessions
import app.granger.core.library.onShelf
import app.granger.core.player.NowPlaying
import app.granger.player.VoiceButton
import app.granger.core.player.PlayerHub
import app.granger.core.speech.ReadAddress
import app.granger.core.speech.ReadSourceKey
import app.granger.voice.listenStart
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
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Publication.Profile
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.use

data class EbookUi(
    val title: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val publication: Publication? = null,
    /** Where the navigator opens. */
    val initial: Locator? = null,
    /** The bars, the side rail and the mini-player. Up when the reader opens and when its tab is returned to; a page turn hides them and a middle tap toggles them. An error keeps them up so the user can leave. */
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
    /**
     * A PDF (Plan P2): pages as printed. Readium's PDF navigator draws no decorations and has no selection, so
     * highlights, notes, the selection menu, in-book search and read-aloud are off, and the text settings are the
     * PDF ones.
     */
    val isPdf: Boolean = false,
    /**
     * A fixed-layout EPUB (a picture book, FixedPages): pages as drawn, one at a time, so the text settings are off.
     */
    val fixedLayout: Boolean = false,
    /** Whether the book has words to read aloud or search; a picture book's pages may hold only pictures. */
    val hasText: Boolean = true,
    /** An ebook made from a PDF (Plan IA): the size of its original pages, which ⋯ opens or removes; null without them. */
    val originalPagesBytes: Long? = null,
    /** A PDF on the shelves, which ⋯ Read as ebook can make an ebook (Plan IA Decisions 6 and 9). */
    val canReadAsEbook: Boolean = false,
    /** Read as ebook is working. */
    val makingEbook: Boolean = false,
    /** Something the reader says once (Read as ebook's outcome), then [noticeShown] clears. */
    val notice: String? = null,
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
    /** Brings back a missing file it can (a pack document, copied out of its pack again; Plan P2); false if not. */
    private val restore: suspend (Book) -> Boolean = { false },
    /** A text's saved place (Plan U2): Listen goes on from it when the page is at or before it ([listenStart]). */
    private val savedPlace: suspend (sourceKey: String) -> ReadAddress? = { null },
    /** A PDF's outline as PdfBox reads it, for when PDFium's has nothing to show (called on [io]). */
    private val pdfOutline: (File) -> List<OutlineEntry> = PdfInspector::outline,
    /** A PDF's links within itself as PdfBox reads them, for a link PDFium found but couldn't place (called on [io]). */
    private val pdfLinks: (File) -> List<PdfLink> = PdfLinks::read,
    /** Whether a PDF is in colour, which Night leaves in its own colours (called on [io]; the app passes PdfColour). */
    private val pdfColour: (File) -> Boolean = { false },
) : ViewModel() {
    /** The open PDF is in colour ([pdfColour]): Night doesn't invert it. */
    @Volatile private var colour = false

    /** Where the anchored contents rows start in their files ([TocPlace.anchorStarts]), by file; read after opening. */
    @Volatile private var anchorStarts: Map<String, Map<String, Double>> = emptyMap()

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
    var navigatorFactory: BookNavigator? = null
        private set

    /** The open book's id; set with [navigatorFactory]. */
    var bookId: String? = null
        private set

    /** The open PDF's file, for PdfBox; null for an EPUB. */
    private var pdfFile: File? = null

    /** The open PDF's links as PdfBox read them, the first time a tapped link needed them. */
    private var pdfLinkList: List<PdfLink>? = null
    /** The open publication; whoever takes it out closes it. */
    private val publication = AtomicReference<Publication?>(null)

    /** The place not yet written, and the debounced write that will write it. Main thread only. */
    private var unsaved: Locator? = null
    private var saveJob: Job? = null

    // Read aloud (Plan V1): one source per open book, kept so its sentence locators survive across calls.
    private var readSource: BookReadSource? = null
    private var following = true
    private var voiceMovedAt = 0L

    // A finger on the page (Plan U3c Decision 5): a page change while it's down or just after is a turn by hand.
    private var pageTouched = false
    private var pageTouchedAt = Long.MIN_VALUE / 2

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
            if (!withContext(io) { file.isFile } && !(restore(book) && withContext(io) { file.isFile })) return@launch fail(BOOK_GONE)
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
            val pdf = opened.conformsTo(Profile.PDF)
            // A comic or a magazine keeps its colours in Night; only black-and-white pages are inverted.
            colour = pdf && withContext(io) { pdfColour(file) }
            val fixed = !pdf && opened.metadata.layout == Layout.FIXED
            val text = !fixed || withContext(io) { FixedPages.scan(opened, stopAtUnsized = false).hasText }
            val factory = if (pdf) {
                BookNavigator.Pdf(opened, prefsStore.prefs.value.pdfNight && !colour, prefsStore.prefs.value.pdfFitWidth)
            } else {
                BookNavigator.Epub(EpubNavigatorFactory(opened))
            }
            bookId = book.id
            if (pdf) pdfFile = file
            navigatorFactory = factory
            OpenBooks.current = book.id to factory
            appScope.launch { sessions.showInTab(tabId, book.id, "", book.title) }
            // A search hit's text is marked where the reader lands, as an in-book search hit is.
            val hit = initial?.takeIf { !targetUsed && target?.quote != null && it.text.highlight != null }
            _state.update {
                it.copy(
                    loading = false, publication = opened, initial = initial, searchHit = hit,
                    toc = flatten(opened.tableOfContents, 0), isPdf = pdf, fixedLayout = fixed, hasText = text,
                ).derived()
            }
            savedState[TARGET_CONSUMED] = true
            launch {
                val pages = bookshelf.originalPagesBytes(book.id)
                _state.update { it.copy(originalPagesBytes = pages, canReadAsEbook = pdf && book.onShelf) }
            }
            if (pdf && _state.value.toc.none { (pdfPageOf(it.href) ?: 0) >= 1 }) launch { pdfOutlineInstead(opened, file) }
            if (!pdf) launch { readAnchorStarts(opened) }
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
            prefsStore.prefs.collect { prefs ->
                (navigatorFactory as? BookNavigator.Pdf)?.let {
                    it.night = prefs.pdfNight && !colour
                    it.fitWidth = prefs.pdfFitWidth
                }
                _state.update { it.copy(prefs = prefs) }
            }
        }
    }

    /** Opens the PDF this ebook was made from in its own tab (Plan IA); the tab's id, or null when it has none. */
    suspend fun openOriginalPages(): Long? {
        val id = bookId ?: return null
        val pages = bookshelf.originalPages(id) ?: return null
        return sessions.openTab(pages.id, "", pages.title + ORIGINAL_PAGES_TITLE)
    }

    /** Deletes the PDF this ebook was made from; the ebook stays (Plan IA Decision 7). */
    fun removeOriginalPages() {
        val id = bookId ?: return
        appScope.launch {
            val removed = bookshelf.removeOriginalPages(id)
            _state.update {
                if (removed) it.copy(originalPagesBytes = null, notice = "Original pages removed")
                else it.copy(notice = "Couldn't remove the original pages")
            }
        }
    }

    /**
     * Makes this PDF an ebook (Plan IA). On success [onDone] runs, to open the book again as the ebook; else the notice
     * says why not.
     */
    fun readAsEbook(onDone: () -> Unit) {
        val id = bookId ?: return
        if (_state.value.makingEbook) return
        _state.update { it.copy(makingEbook = true) }
        appScope.launch {
            val report = try {
                bookshelf.readAsEbook(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Granger", "Read as ebook failed", e)
                null
            }
            _state.update {
                it.copy(
                    makingEbook = false,
                    notice = when {
                        report == null -> "Couldn't make an ebook of this PDF"
                        report.passes -> null
                        else -> "This PDF doesn't read well as an ebook (${report.why}), so it stays as pages."
                    },
                )
            }
            if (report?.passes == true) withContext(Dispatchers.Main) { onDone() }
        }
    }

    fun noticeShown() {
        _state.update { it.copy(notice = null) }
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
        val before = _state.value.locator
        if (before != null && turnedByHand(before, locator)) hideChromeForReading()
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
            placeTitle(locator),
        )
    }

    /** What the rail and Continue call a place: the chapter, or in a PDF its outline entry, else "Page n". */
    /** A PDF's locator has no title: its bookmark is named by the page, after the outline entry it falls in. */
    private fun bookmarkText(locator: Locator): String {
        val page = locator.locations.position
        if (!_state.value.isPdf || page == null) return locator.title.orEmpty()
        val ui = _state.value
        val entry = pdfTocIndex(ui.toc, page)?.let { ui.toc[it].title.ifBlank { null } }
        return listOfNotNull(entry, "p. $page").joinToString(" · ")
    }

    private fun placeTitle(locator: Locator): String {
        locator.title?.takeIf { it.isNotBlank() }?.let { return it }
        val ui = _state.value
        if (!ui.isPdf) return ""
        val page = locator.locations.position ?: return ""
        return pdfTocIndex(ui.toc, page)?.let { ui.toc[it].title.ifBlank { null } } ?: "Page $page"
    }

    /**
     * PDFium reads some PDFs' outlines as nothing, or as entries with no page (a destination it can't follow), and
     * Contents would say there is none: PdfBox's reading of [file] is shown instead, when it finds one. When it finds
     * none either, PDFium's rows that go nowhere are dropped, so Contents says the PDF has no list.
     */
    private suspend fun pdfOutlineInstead(opened: Publication, file: File) {
        val href = opened.readingOrder.firstOrNull()?.href?.toString() ?: return
        val entries = try {
            withContext(io) { pdfOutline(file) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Granger", "Couldn't read the PDF's outline", e)
            emptyList()
        }
        _state.update { it.copy(toc = entries.map { e -> TocRow(e.title, "$href#page=${e.page}", e.depth) }).derived() }
    }

    /** Reads the files holding several contents rows, to tell which of them the reader is in. */
    private suspend fun readAnchorStarts(opened: Publication) {
        val wanted = TocPlace.anchorsByFile(_state.value.toc)
        if (wanted.isEmpty()) return
        val starts = withContext(io) {
            wanted.mapNotNull { (file, fragments) ->
                try {
                    val link = Url(file)?.let(opened::linkWithHref) ?: return@mapNotNull null
                    val bytes = opened.get(link)?.use { it.read().getOrNull() } ?: return@mapNotNull null
                    file to TocPlace.anchorStarts(String(bytes), fragments)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }.toMap()
        }
        anchorStarts = starts
        _state.update { it.derived() }
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
        val index = if (isPdf) {
            // One file: the row is the last outline entry at or before the page.
            here.locations.position?.let { pdfTocIndex(toc, it) }
        } else {
            TocPlace.index(toc, here, anchorStarts)
        }
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
                        BookAnnotation(0, id, MarkKind.BOOKMARK, here.toJSON().toString(), bookmarkText(here), "", "", now, now),
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

    /**
     * A link tapped on a PDF page, to another page of it (a web link is the screen's): the page PDFium gave, or when
     * it gave none it can use, the page PdfBox finds for the link's box ([PdfLinks.target]), nearest the page shown.
     * A link neither can place is ignored.
     */
    fun onPdfLink(tap: PdfLinkTap) {
        val pages = _state.value.positionsCount
        val given = tap.page?.takeIf { it >= 0 && (pages == 0 || it < pages) }
        if (given != null) return goToPdfPage(given)
        val file = pdfFile ?: return
        val shown = (_state.value.locator?.locations?.position ?: 1) - 1
        viewModelScope.launch {
            val links = pdfLinkList ?: try {
                withContext(io) { pdfLinks(file) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Granger", "Couldn't read the PDF's links", e)
                emptyList()
            }.also { pdfLinkList = it }
            PdfLinks.target(links, tap.left, tap.top, tap.right, tap.bottom, shown)?.let(::goToPdfPage)
        }
    }

    /** Opens the PDF at its 0-based page [index], as a Contents row does. */
    private fun goToPdfPage(index: Int) {
        val href = _state.value.publication?.readingOrder?.firstOrNull()?.href ?: return
        goTo("$href#page=${index + 1}")
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

    /** A search result: jump there and mark the match on the page, with the panel and the chrome out of the way ([jump]). */
    fun goToHit(row: BookSearchRow) {
        val locator = row.locator ?: return
        if (row.markId != null && row.markId in _state.value.unplaceable) return
        _state.update { it.copy(panel = null, chromeVisible = false, searchHit = if (row.markId == null) locator else null) }
        _navigation.trySend(locator)
    }

    /**
     * A jump from the Contents, Bookmarks or Search panel: the panel closes and the chrome slides away (Plan U3c), since
     * it lies over the page (Decision 5) and would cover the chapter's first lines until a tap. A tap brings it back.
     */
    private fun jump(locator: Locator) {
        _state.update { it.copy(panel = null, chromeVisible = false) }
        _navigation.trySend(locator)
    }

    fun open(panel: Panel?) {
        _state.update { it.copy(panel = panel, searchHit = null) }
    }

    fun updatePrefs(prefs: ReadingPrefs) = prefsStore.update(prefs)

    /**
     * A finger went [down] on the page, or came up. Page changes while it's down, and up to [PAGE_TURN_GRACE_MS] after,
     * are the reader's own turns (a swipe, an edge tap, a link), which hide the chrome; others (the slider, Contents,
     * a search hit, the voice, a new text size, the navigator settling as it opens) leave it.
     */
    fun onPageTouch(down: Boolean) {
        pageTouched = down
        pageTouchedAt = now()
    }

    private fun turnedByHand(before: Locator, after: Locator): Boolean {
        val moved = before.href != after.href || before.locations.progression != after.locations.progression ||
            before.locations.position != after.locations.position
        if (!moved) return false
        val t = now()
        if (t - voiceMovedAt <= VOICE_MOVE_GRACE_MS) return false
        return pageTouched || t - pageTouchedAt <= PAGE_TURN_GRACE_MS
    }

    /** A page turn by hand: the chrome slides away, unless a panel or a highlight's sheet is open over the page. */
    private fun hideChromeForReading() {
        _state.update { if (!it.chromeVisible || it.panel != null || it.editingHighlight != null) it else it.copy(chromeVisible = false) }
    }

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
     * the words, at the first element at [from] (a chapter's start), else where the book is open. The book's saved
     * place wins when the page is at or before it ([listenStart]).
     */
    fun readAloud(from: Locator?, visibleText: String? = null) {
        val hub = player ?: return
        val src = source() ?: return
        viewModelScope.launch {
            val page = from ?: _state.value.locator ?: _state.value.initial
            val address = visibleText?.let { words -> page?.let { src.addressAtText(it, words) } }
                ?: src.addressAt(page)
                ?: return@launch
            val start = listenStart(src, address, runCatching { savedPlace(src.key) }.getOrNull())
            following = true
            hub.readAloud(src, start)
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
        /** A page change this soon after a finger left the page is its turn: the turn's animation runs on after it lifts. */
        const val PAGE_TURN_GRACE_MS = 1_000L
        const val TARGET_CONSUMED = "targetConsumed"

        /** After the title of an ebook's original pages, on their tab. */
        const val ORIGINAL_PAGES_TITLE = " (original pages)"
    }
}

/** The PDF page a contents row opens at (its `#page=n`); null if it names none. */
internal fun pdfPageOf(href: String): Int? = href.substringAfter("#page=", "").takeIf { it.isNotEmpty() }?.toIntOrNull()

/** The last contents row at or before [page] in a PDF's outline; null when the page comes before them all. */
internal fun pdfTocIndex(toc: List<TocRow>, page: Int): Int? {
    var best: Int? = null
    var bestPage = 0
    toc.forEachIndexed { i, row ->
        val p = pdfPageOf(row.href) ?: return@forEachIndexed
        if (p <= page && p >= bestPage) {
            best = i
            bestPage = p
        }
    }
    return best
}
