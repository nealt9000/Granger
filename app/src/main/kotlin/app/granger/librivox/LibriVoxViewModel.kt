package app.granger.librivox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.archive.ArchiveError
import app.granger.core.archive.ArchiveOrgClient
import app.granger.core.archive.LibriVoxBook
import app.granger.core.archive.LibriVoxClient
import app.granger.core.archive.LibriVoxError
import app.granger.core.archive.LibriVoxOffer
import app.granger.core.archive.LibriVoxOffers
import app.granger.core.archive.LibriVoxQuery
import app.granger.core.archive.LibriVoxShelf
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Downloads
import app.granger.core.media.MediaManifest
import app.granger.core.media.MediaManifests
import app.granger.formatBytes
import app.granger.media.MediaDownloader
import app.granger.media.MediaDownloads
import app.granger.sources.ItemStatus
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibriVoxTab(val title: String) { BROWSE("Browse"), INSTALLED("Installed") }

data class LibriVoxRow(
    val id: String,
    val title: String,
    /** "H. G. Wells · 4 h 9 min" on Browse; the size on Installed. */
    val details: String,
    val status: ItemStatus,
)

data class LibriVoxSheet(
    val id: String,
    val loading: Boolean,
    /** With its sections, readers and genres (`LibriVoxClient.book`). */
    val book: LibriVoxBook?,
    val offer: LibriVoxOffer?,
    /** The chapters' total size, when the book can be got. */
    val sizeBytes: Long?,
    val error: String?,
    val status: ItemStatus,
    /** Chapters in, and in all ("Downloading: 3 of 13 chapters"); 0 and 0 before the book is known. */
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    /** The downloaded cover, once it's in. */
    val coverPath: String? = null,
)

data class LibriVoxUi(
    val tab: LibriVoxTab = LibriVoxTab.BROWSE,
    val query: String = "",
    val shelf: LibriVoxShelf? = LibriVoxShelf.RECENT,
    val rows: List<LibriVoxRow> = emptyList(),
    val loading: Boolean = true,
    val canLoadMore: Boolean = false,
    val error: String? = null,
    val sheet: LibriVoxSheet? = null,
    val notice: String? = null,
)

/**
 * The LibriVox source: Recently added, six genres and search, a book sheet and Get. The same shape as the archive.org
 * screen: typing is debounced (the client paces its requests, so one search per keystroke would queue up), a
 * generation number drops stale answers, and the Installed tab is built from the ledger alone, so it works offline.
 * Every book downloads through [media] as one group of 64 kbps chapters (decided with Neal).
 */
class LibriVoxViewModel(
    private val client: LibriVoxClient,
    private val archive: ArchiveOrgClient,
    private val media: MediaDownloader,
    private val downloads: Downloads,
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private data class Sheet(val id: String, val loading: Boolean, val book: LibriVoxBook?, val offer: LibriVoxOffer?, val error: String?)

    private data class State(
        val tab: LibriVoxTab = LibriVoxTab.BROWSE,
        /** What is typed in the box, shown at once. */
        val query: String = "",
        /** The text the shown results are for: [search] reads this and [shelf], never [query]. */
        val searchedQuery: String = "",
        val shelf: LibriVoxShelf? = LibriVoxShelf.RECENT,
        val books: List<LibriVoxBook> = emptyList(),
        /** The offset the next page starts at. */
        val nextOffset: Int = 0,
        val hasMore: Boolean = false,
        val loading: Boolean = true,
        val error: String? = null,
        val sheet: Sheet? = null,
        val notice: String? = null,
    )

    private val state = MutableStateFlow(State())

    /** The search field's text, as Compose state written synchronously on every keystroke (Plan 7 lesson). */
    var searchText by mutableStateOf("")
        private set

    @Volatile private var generation = 0
    private var queryJob: Job? = null
    private var sheetJob: Job? = null

    /** Books' chapter counts, from the offer or read once from their manifests: a cancel drops unfinished records. */
    private val fileCounts = ConcurrentHashMap<String, Int>()

    val ui: StateFlow<LibriVoxUi> = combine(state, downloads.records, ::buildUi)
        .flowOn(io)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibriVoxUi())

    init {
        search(reset = true)
    }

    /** Searches [value] by title and author after a 400 ms pause; clearing it goes back to Recently added. */
    fun setQuery(value: String) {
        searchText = value
        state.update { it.copy(query = value) }
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            state.update { it.copy(searchedQuery = value, shelf = if (value.isBlank()) LibriVoxShelf.RECENT else null) }
            search(reset = true)
        }
    }

    fun selectShelf(shelf: LibriVoxShelf) {
        queryJob?.cancel()
        searchText = ""
        state.update { it.copy(query = "", searchedQuery = "", shelf = shelf) }
        search(reset = true)
    }

    fun loadMore() {
        val s = state.value
        if (s.loading || !s.hasMore) return
        search(reset = false)
    }

    /** Reruns what failed: the first page, or the next one when some results are already shown. */
    fun retry() {
        search(reset = state.value.books.isEmpty())
    }

    fun setTab(value: LibriVoxTab) {
        state.update { it.copy(tab = value) }
    }

    fun openBook(id: String) {
        sheetJob?.cancel()
        state.update { it.copy(sheet = Sheet(id, loading = true, book = null, offer = null, error = null)) }
        sheetJob = viewModelScope.launch {
            val sheet = try {
                val (book, offer) = fetchOffer(id)
                Sheet(id, loading = false, book = book, offer = offer, error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Sheet(id, loading = false, book = null, offer = null, error = messageOf(e))
            }
            if (sheet.offer is LibriVoxOffer.Files) fileCounts[groupOf(id)] = sheet.offer.files.size
            state.update { if (it.sheet?.id == id) it.copy(sheet = sheet) else it }
        }
    }

    fun closeBook() {
        sheetJob?.cancel()
        state.update { it.copy(sheet = null) }
    }

    /** Gets the book as one group, or the chapters still missing ("Get the rest"). */
    fun get(id: String) {
        appScope.launch {
            val notice = try {
                getBook(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageOf(e)
            }
            if (notice != null) state.update { it.copy(notice = notice) }
        }
    }

    /** Stops the unfinished chapters; the ones already in stay (decided with Neal). */
    fun cancel(id: String) {
        if (!LibriVoxClient.isValidId(id)) return
        appScope.launch { media.cancel(groupOf(id)) }
    }

    fun retryDownload(id: String) {
        if (!LibriVoxClient.isValidId(id)) return
        appScope.launch { media.retry(groupOf(id)) }
    }

    /** Deletes every file of the book; its place and bookmarks are kept. */
    fun remove(id: String) {
        if (!LibriVoxClient.isValidId(id)) return
        appScope.launch {
            val group = groupOf(id)
            media.remove(group)
            fileCounts.remove(group)
        }
    }

    fun dismissNotice() {
        state.update { it.copy(notice = null) }
    }

    private fun search(reset: Boolean) {
        val gen = ++generation
        val s = state.value
        val offset = if (reset) 0 else s.nextOffset
        val query = s.searchedQuery.trim().takeIf { it.isNotEmpty() }?.let { LibriVoxQuery.Text(it) }
            ?: (s.shelf ?: LibriVoxShelf.RECENT).query
        state.update {
            if (reset) it.copy(loading = true, error = null, books = emptyList(), nextOffset = 0, hasMore = false)
            else it.copy(loading = true, error = null)
        }
        viewModelScope.launch {
            try {
                val page = client.search(query, offset, PAGE_SIZE)
                if (gen != generation) return@launch
                state.update { current ->
                    val seen = current.books.mapTo(HashSet()) { it.id }
                    val books = current.books + page.books.filter { seen.add(it.id) }
                    current.copy(books = books, nextOffset = offset + PAGE_SIZE, hasMore = page.hasMore, loading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                state.update { it.copy(loading = false, error = messageOf(e)) }
            }
        }
    }

    /** The book with its sections, and what it offers: an archive.org item is fetched only when it names one. */
    private suspend fun fetchOffer(id: String): Pair<LibriVoxBook, LibriVoxOffer> {
        val book = client.book(id)
        val identifier = book.archiveIdentifier
        if (identifier == null || book.sections.isEmpty()) return book to LibriVoxOffer.NotOnArchive
        return book to LibriVoxOffers.choose(book, archive.item(identifier))
    }

    /** Returns the notice to show, or null when the download is on its way. */
    private suspend fun getBook(id: String): String? {
        if (!LibriVoxClient.isValidId(id)) return NOT_FOUND
        // Only a fetched book and item are downloaded from: the sheet's, when it's this book's and has them.
        val offer = state.value.sheet?.takeIf { it.id == id && !it.loading }?.offer ?: fetchOffer(id).second
        return when (offer) {
            is LibriVoxOffer.Files -> {
                val manifest = MediaDownloads.bookManifest(offer, archive, now())
                fileCounts[groupOf(id)] = manifest.files.size
                media.get(manifest)
            }
            is LibriVoxOffer.Incomplete -> INCOMPLETE
            LibriVoxOffer.NotOnArchive -> NOT_ON_ARCHIVE
        }
    }

    private fun groupOf(id: String) = MediaDownloads.groupId(MediaManifest.LIBRIVOX, id)

    private fun buildUi(s: State, records: List<DownloadRecord>): LibriVoxUi {
        val ours = records.filter { it.groupId?.startsWith(MediaDownloads.groupId(MediaManifest.LIBRIVOX, "")) == true }
        val rows = when (s.tab) {
            LibriVoxTab.BROWSE -> s.books.map { book ->
                LibriVoxRow(
                    id = book.id,
                    title = decodeEntities(book.title).ifBlank { book.id },
                    details = listOfNotNull(
                        decodeEntities(book.authors.joinToString(", ")).ifBlank { null },
                        durationLabel(book.totalTimeMs),
                    ).joinToString(" · "),
                    status = statusOf(book.id, ours, offerFiles = null),
                )
            }
            // Built from the ledger alone, so it works offline.
            LibriVoxTab.INSTALLED -> ours.groupBy { it.groupId!! }.mapNotNull { (group, files) ->
                val id = MediaDownloads.refOf(group, MediaManifest.LIBRIVOX)?.takeIf(LibriVoxClient::isValidId) ?: return@mapNotNull null
                LibriVoxRow(
                    id = id,
                    title = files.first().title.ifBlank { id },
                    details = formatBytes(files.sumOf { it.sizeBytes }),
                    status = statusOf(id, ours, offerFiles = null),
                )
            }
        }
        val browsing = s.tab == LibriVoxTab.BROWSE
        return LibriVoxUi(
            tab = s.tab,
            query = s.query,
            shelf = s.shelf,
            rows = rows,
            loading = browsing && s.loading,
            canLoadMore = browsing && !s.loading && s.hasMore,
            error = if (browsing) s.error else null,
            sheet = s.sheet?.let { sheet ->
                val files = sheet.offer as? LibriVoxOffer.Files
                val group = groupOf(sheet.id)
                val mine = ours.filter { it.groupId == group }
                LibriVoxSheet(
                    id = sheet.id,
                    loading = sheet.loading,
                    book = sheet.book,
                    offer = sheet.offer,
                    sizeBytes = files?.totalBytes,
                    error = sheet.error,
                    status = statusOf(sheet.id, ours, offerFiles = files?.files?.size),
                    filesDone = mine.filter { it.state == DownloadState.INSTALLED && !MediaDownloads.isCover(it) }
                        .map { it.itemId }.distinct().size,
                    filesTotal = files?.files?.size ?: fileCounts[group] ?: 0,
                    coverPath = mine.firstOrNull { it.state == DownloadState.INSTALLED && MediaDownloads.isCover(it) }?.filePath,
                )
            },
            notice = s.notice,
        )
    }

    /** By [MediaDownloads.statusOf], counting the chapters from the offer, else the book's manifest, else its records. */
    private fun statusOf(id: String, records: List<DownloadRecord>, offerFiles: Int?): ItemStatus {
        val group = groupOf(id)
        val mine = records.filter { it.groupId == group }
        if (mine.isEmpty()) return ItemStatus.NotInstalled
        val count = offerFiles ?: fileCounts[group]
            ?: mine.firstNotNullOfOrNull { r -> File(r.filePath).parentFile?.let(MediaManifests::read)?.files?.size }
                ?.also { fileCounts[group] = it }
            ?: mine.filterNot(MediaDownloads::isCover).map { it.itemId }.distinct().size
        return MediaDownloads.statusOf(group, count, records)
    }

    private fun messageOf(e: Exception): String = when (e) {
        is LibriVoxError, is ArchiveError -> e.message ?: OFFLINE
        else -> OFFLINE
    }

    private companion object {
        const val DEBOUNCE_MS = 400L
        const val PAGE_SIZE = 50
        const val INCOMPLETE = "This book's files aren't all on archive.org."
        const val NOT_ON_ARCHIVE = "This book isn't on archive.org yet."
        const val NOT_FOUND = "Not found on LibriVox"
        const val OFFLINE = "Couldn't reach LibriVox. Check your connection and try again."
    }
}

/** "4 h 9 min", "1 h", "59 min"; null for no time (a book not yet on archive.org). Under a minute reads as 1 min. */
internal fun durationLabel(ms: Long): String? {
    if (ms <= 0) return null
    val minutes = maxOf(1L, ms / 60_000)
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "$m min"
        m == 0L -> "$h h"
        else -> "$h h $m min"
    }
}
