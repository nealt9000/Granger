package app.granger.archive

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.archive.ArchiveDoc
import app.granger.core.archive.ArchiveError
import app.granger.core.archive.ArchiveFile
import app.granger.core.archive.ArchiveItem
import app.granger.core.archive.ArchiveOffers
import app.granger.core.archive.ArchiveOrgClient
import app.granger.core.archive.ArchiveQuery
import app.granger.core.archive.ArchiveShelf
import app.granger.core.archive.Offer
import app.granger.core.archive.TextKind
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.EnqueueResult
import app.granger.core.downloads.VolumeSource
import app.granger.core.downloads.isActive
import app.granger.core.library.BookIds
import app.granger.core.library.BookOrigin
import app.granger.core.library.Bookshelf
import app.granger.formatBytes
import app.granger.sources.DownloadPrefs
import app.granger.sources.ItemStatus
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Archive.org has no versions, so there's no Updates tab (spec §5). */
enum class ArchiveTab(val title: String) { BROWSE("Browse"), INSTALLED("Installed") }

data class ArchiveRow(
    val identifier: String,
    val title: String,
    /** Creator · year on Browse; the file size on Installed. */
    val details: String,
    /** A guess from the search formats (or the file extension): its file is a placeholder, never downloaded. */
    val preview: Offer?,
    val status: ItemStatus,
)

data class ItemSheet(
    val identifier: String,
    val loading: Boolean,
    val item: ArchiveItem?,
    val offer: Offer?,
    val sizeBytes: Long?,
    val error: String?,
    val status: ItemStatus,
)

data class ArchiveUi(
    val tab: ArchiveTab = ArchiveTab.BROWSE,
    val query: String = "",
    val shelf: ArchiveShelf? = ArchiveShelf.MOST_DOWNLOADED,
    val filtered: Boolean = true,
    val rows: List<ArchiveRow> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
    val canLoadMore: Boolean = false,
    val error: String? = null,
    val sheet: ItemSheet? = null,
    val notice: String? = null,
)

/** The archive.org source: shelves and search (public domain only until the waiver), item details and Get. */
class ArchiveViewModel(
    private val client: ArchiveOrgClient,
    private val downloads: Downloads,
    private val bookshelf: Bookshelf,
    private val prefs: ArchivePrefs,
    private val volumes: VolumeSource,
    private val downloadPrefs: DownloadPrefs,
    /** The internal volume's `books/archive-org`, where every sidecar lives (R2). */
    private val sidecarDir: () -> File,
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class Sheet(val identifier: String, val loading: Boolean, val item: ArchiveItem?, val offer: Offer?, val error: String?)

    private data class State(
        val tab: ArchiveTab = ArchiveTab.BROWSE,
        /** What is typed in the box, shown at once. */
        val query: String = "",
        /** The text the shown results are for: [search] reads this and [shelf], never [query]. */
        val searchedQuery: String = "",
        val shelf: ArchiveShelf? = ArchiveShelf.MOST_DOWNLOADED,
        val filtered: Boolean = true,
        val docs: List<ArchiveDoc> = emptyList(),
        val total: Int = 0,
        val page: Int = 0,
        val loading: Boolean = true,
        val error: String? = null,
        val sheet: Sheet? = null,
        val notice: String? = null,
    )

    private val state = MutableStateFlow(State(filtered = !prefs.waiverAccepted.value))

    /**
     * The search field's text, as Compose state written synchronously on every keystroke. The field must not read its
     * value from a flow: a value that lags a dispatch behind the keyboard is pushed back into the field and scrambles
     * fast typing (seen on the Pixel 6, 2026-10-17).
     */
    var searchText by mutableStateOf("")
        private set

    /** Bumped by every search; a response for an older generation is dropped (Review Focus 2). */
    @Volatile private var generation = 0
    private var queryJob: Job? = null
    private var sheetJob: Job? = null

    val ui: StateFlow<ArchiveUi> = combine(state, downloads.records, ::buildUi)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArchiveUi(filtered = !prefs.waiverAccepted.value))

    init {
        // The first value starts the screen on Most downloaded; every later change reruns the current search in the other view.
        viewModelScope.launch {
            prefs.waiverAccepted.collect { accepted ->
                state.update { it.copy(filtered = !accepted) }
                search(reset = true)
            }
        }
    }

    /** Searches [value] with no shelf after a 400 ms pause; clearing it goes back to Most downloaded. */
    fun setQuery(value: String) {
        searchText = value
        state.update { it.copy(query = value) }
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            state.update {
                it.copy(searchedQuery = value, shelf = if (value.isBlank()) ArchiveShelf.MOST_DOWNLOADED else null)
            }
            search(reset = true)
        }
    }

    fun selectShelf(shelf: ArchiveShelf) {
        queryJob?.cancel()
        searchText = ""
        state.update { it.copy(query = "", searchedQuery = "", shelf = shelf) }
        search(reset = true)
    }

    fun loadMore() {
        val s = state.value
        if (s.loading || s.docs.size >= s.total) return
        search(reset = false)
    }

    /** Reruns what failed: the first page, or the next one when some results are already shown. */
    fun retry() {
        search(reset = state.value.docs.isEmpty())
    }

    fun setTab(value: ArchiveTab) {
        state.update { it.copy(tab = value) }
    }

    fun openItem(identifier: String) {
        sheetJob?.cancel()
        state.update { it.copy(sheet = Sheet(identifier, loading = true, item = null, offer = null, error = null)) }
        sheetJob = viewModelScope.launch {
            val sheet = try {
                val item = client.item(identifier)
                Sheet(identifier, loading = false, item = item, offer = ArchiveOffers.choose(item), error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Sheet(identifier, loading = false, item = null, offer = null, error = messageOf(e))
            }
            state.update { if (it.sheet?.identifier == identifier) it.copy(sheet = sheet) else it }
        }
    }

    fun closeItem() {
        sheetJob?.cancel()
        state.update { it.copy(sheet = null) }
    }

    fun get(identifier: String) {
        appScope.launch {
            val notice = try {
                fetchAndEnqueue(identifier)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageOf(e)
            }
            if (notice != null) state.update { it.copy(notice = notice) }
        }
    }

    fun cancel(identifier: String) {
        appScope.launch {
            downloads.cancel(ArchiveDownloads.itemId(identifier))
            withContext(io) { ArchiveDownloads.deleteSidecar(sidecarDir(), identifier) }
        }
    }

    fun retryDownload(identifier: String) {
        appScope.launch { downloads.retry(ArchiveDownloads.itemId(identifier)) }
    }

    /**
     * The download goes first, then the shelf entry and the metadata sidecar; the book's place and marks stay for a
     * later re-download.
     */
    fun remove(identifier: String) {
        appScope.launch {
            downloads.remove(ArchiveDownloads.itemId(identifier))
            bookshelf.remove(BookIds.of(BookOrigin.ARCHIVE_ORG, identifier))
            withContext(io) { ArchiveDownloads.deleteSidecar(sidecarDir(), identifier) }
        }
    }

    /** The waiver flow reruns the search unfiltered. */
    fun acceptWaiver() {
        prefs.accept()
    }

    fun dismissNotice() {
        state.update { it.copy(notice = null) }
    }

    private fun search(reset: Boolean) {
        val gen = ++generation
        val s = state.value
        val page = if (reset) 1 else s.page + 1
        val query = ArchiveQuery.build(s.searchedQuery, s.shelf, s.filtered)
        state.update {
            if (reset) it.copy(loading = true, error = null, docs = emptyList(), total = 0, page = 0)
            else it.copy(loading = true, error = null)
        }
        viewModelScope.launch {
            try {
                val result = client.search(query, page)
                if (gen != generation) return@launch
                state.update { current ->
                    val seen = current.docs.mapTo(HashSet()) { it.identifier }
                    val docs = current.docs + result.docs.filter { seen.add(it.identifier) }
                    // An empty page ends the list even if numFound says there's more.
                    val total = if (result.docs.isEmpty()) docs.size else result.total
                    current.copy(docs = docs, total = total, page = page, loading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                state.update { it.copy(loading = false, error = messageOf(e)) }
            }
        }
    }

    /** Returns the notice to show, or null when the download is on its way. */
    private suspend fun fetchAndEnqueue(identifier: String): String? {
        // Only fetched metadata is downloaded from: a preview's file is a placeholder.
        val item = state.value.sheet?.item?.takeIf { it.identifier == identifier } ?: client.item(identifier)
        if (item.identifier != identifier || !ArchiveQuery.isValidIdentifier(identifier)) return NOTHING_TO_DOWNLOAD
        val offer = ArchiveOffers.choose(item) as? Offer.Download ?: return NOTHING_TO_DOWNLOAD
        // The live preference, not the screen's: the waiver may have been withdrawn since this item was reached.
        if (!prefs.waiverAccepted.value && !ArchiveOffers.isPublicDomain(item)) return NOT_PUBLIC_DOMAIN
        val all = withContext(io) { volumes.list() }
        val volume = all.firstOrNull { it.root.path == downloadPrefs.volumePath } ?: all.firstOrNull()
            ?: return "No storage is available right now."
        val spec = ArchiveDownloads.spec(item, offer, client)
        // Written first: the download may finish before enqueue returns.
        val sidecar = withContext(io) { ArchiveDownloads.writeSidecar(sidecarDir(), item, offer.kind) }
        val result = try {
            downloads.enqueue(spec, volume.root, downloadPrefs.wifiOnly)
        } catch (e: Throwable) {
            withContext(NonCancellable + io) { sidecar.delete() }
            throw e
        }
        // A refused enqueue leaves no sidecar; an installed book keeps its (now refreshed) one for later repairs.
        if (result is EnqueueResult.NoSpace || result is EnqueueResult.OverCap) {
            withContext(io) { sidecar.delete() }
        }
        return when (result) {
            EnqueueResult.Queued, EnqueueResult.AlreadyActive, EnqueueResult.AlreadyInstalled -> null
            is EnqueueResult.NoSpace ->
                "Not enough space on ${volume.label} for ${spec.title}: free up ${formatBytes(result.neededBytes)}."
            is EnqueueResult.OverCap ->
                "${spec.title} would bring your downloads to ${formatBytes(result.wouldUseBytes)}, " +
                    "over the ${formatBytes(result.capBytes)} limit."
        }
    }

    private fun buildUi(s: State, records: List<DownloadRecord>): ArchiveUi {
        val ours = records.filter { it.itemId.startsWith(ArchiveDownloads.PREFIX) }
        val rows = when (s.tab) {
            ArchiveTab.BROWSE -> s.docs.map { doc ->
                ArchiveRow(
                    identifier = doc.identifier,
                    title = doc.title.ifBlank { doc.identifier },
                    details = listOfNotNull(doc.creator.ifBlank { null }, doc.year?.toString()).joinToString(" · "),
                    preview = ArchiveOffers.preview(doc),
                    status = statusOf(doc.identifier, ours),
                )
            }
            // Built from the ledger alone, so it works offline.
            ArchiveTab.INSTALLED -> ours.groupBy { it.itemId }.mapNotNull { (itemId, versions) ->
                val identifier = ArchiveDownloads.identifierOf(itemId) ?: return@mapNotNull null
                val latest = versions.last()
                ArchiveRow(
                    identifier = identifier,
                    title = latest.title.ifBlank { identifier },
                    details = formatBytes(latest.sizeBytes),
                    preview = previewOfFile(latest.filePath),
                    status = statusOf(identifier, ours),
                )
            }
        }
        val browsing = s.tab == ArchiveTab.BROWSE
        return ArchiveUi(
            tab = s.tab,
            query = s.query,
            shelf = s.shelf,
            filtered = s.filtered,
            rows = rows,
            total = if (browsing) s.total else rows.size,
            loading = browsing && s.loading,
            canLoadMore = browsing && !s.loading && s.docs.size < s.total,
            error = if (browsing) s.error else null,
            sheet = s.sheet?.let { sheet ->
                ItemSheet(
                    identifier = sheet.identifier,
                    loading = sheet.loading,
                    item = sheet.item,
                    offer = sheet.offer,
                    sizeBytes = (sheet.offer as? Offer.Download)?.file?.sizeBytes,
                    error = sheet.error,
                    status = statusOf(sheet.identifier, ours),
                )
            },
            notice = s.notice,
        )
    }

    private fun statusOf(identifier: String, records: List<DownloadRecord>): ItemStatus {
        val versions = records.filter { it.itemId == ArchiveDownloads.itemId(identifier) }
        versions.firstOrNull { it.isActive }?.let { active ->
            return when (active.state) {
                DownloadState.QUEUED -> ItemStatus.Queued(active.wifiOnly, active.error)
                DownloadState.RUNNING ->
                    ItemStatus.Downloading(if (active.sizeBytes > 0) active.bytesDone.toFloat() / active.sizeBytes else 0f)
                else -> ItemStatus.Failed(active.error ?: "Download failed")
            }
        }
        return if (versions.any { it.state == DownloadState.INSTALLED }) ItemStatus.Installed else ItemStatus.NotInstalled
    }

    private fun previewOfFile(path: String): Offer? = when (File(path).extension.lowercase()) {
        "epub" -> Offer.Download(PLACEHOLDER, TextKind.EPUB, alsoPdf = false)
        "txt" -> Offer.Download(PLACEHOLDER, TextKind.TEXT, alsoPdf = false)
        else -> null
    }

    private fun messageOf(e: Exception): String = (e as? ArchiveError)?.message ?: OFFLINE

    private companion object {
        const val DEBOUNCE_MS = 400L
        const val NOTHING_TO_DOWNLOAD = "Nothing on this item can be downloaded."
        const val NOT_PUBLIC_DOMAIN = "This item wasn't published in 1930 or earlier. Show everything to download it."
        const val OFFLINE = "Couldn't reach archive.org. Check your connection and try again."
        val PLACEHOLDER = ArchiveFile(name = "", format = "", sizeBytes = null, sha1 = null, md5 = null, source = "", isPrivate = false)
    }
}
