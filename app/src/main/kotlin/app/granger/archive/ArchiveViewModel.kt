package app.granger.archive

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.archive.ArchiveDoc
import app.granger.core.archive.ArchiveError
import app.granger.core.archive.ArchiveFile
import app.granger.core.archive.ArchiveAudioOffers
import app.granger.core.archive.ArchiveItem
import app.granger.core.archive.ArchiveKind
import app.granger.core.archive.ArchiveOffers
import app.granger.core.archive.ArchiveOrgClient
import app.granger.core.archive.ArchiveQuery
import app.granger.core.archive.ArchiveShelf
import app.granger.core.archive.AudioOffer
import app.granger.core.archive.Offer
import app.granger.core.archive.PublicDomain
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
import app.granger.core.media.MediaManifest
import app.granger.core.media.MediaManifests
import app.granger.formatBytes
import app.granger.media.MediaDownloader
import app.granger.media.MediaDownloads
import app.granger.sources.DownloadPrefs
import app.granger.sources.ItemStatus
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Archive.org has no versions, so there's no Updates tab (spec §5). */
enum class ArchiveTab(val title: String) { BROWSE("Browse"), INSTALLED("Installed") }

data class ArchiveRow(
    val identifier: String,
    val title: String,
    /** Creator · year on Browse; the file size on Installed, with the PDF beside a text after it. */
    val details: String,
    /** A guess from the search formats (or the file extension): its file is a placeholder, never downloaded. */
    val preview: Offer?,
    val status: ItemStatus,
    /** An audio view's guess from the search formats (its files are empty), or null; texts leave it null. */
    val audio: AudioOffer? = null,
)

data class ItemSheet(
    val identifier: String,
    val loading: Boolean,
    val item: ArchiveItem?,
    val offer: Offer?,
    val sizeBytes: Long?,
    val error: String?,
    val status: ItemStatus,
    /** An audio item's files in and in all ("Downloading: 3 of 7"); 0 and 0 for texts or before the item is known. */
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    /** The PDF beside a text download (Plan P2): its size, and where its own download stands. */
    val pdfSizeBytes: Long? = null,
    val pdfStatus: ItemStatus = ItemStatus.NotInstalled,
    /** A set's parts in order, each with where its download stands (Plan IA); empty for an item of one file. */
    val parts: List<PartRow> = emptyList(),
)

/** One part of a set on the sheet: its [number], [title], size and where its download stands. */
data class PartRow(val number: Int, val title: String, val sizeBytes: Long, val status: ItemStatus)

/**
 * A Get over 100 MB waiting for the user's yes (Plan P2): [pdf] when it's the PDF beside the item's text; [part] when
 * it's one part of a set, [set] when it's every part still missing (Plan IA).
 */
data class ConfirmLarge(
    val identifier: String, val pdf: Boolean, val bytes: Long, val title: String, val part: Int? = null, val set: Boolean = false,
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
    val kind: ArchiveKind = ArchiveKind.TEXTS,
    /** The first year the audio filter leaves out: 1926 in 2026. */
    val filterYear: Int = PublicDomain.lastRecordingYear(LocalDate.now()) + 1,
    /** What the open audio item offers (texts use [ItemSheet.offer]). */
    val sheetAudio: AudioOffer? = null,
    /** Filtered, for a kind with no usable recording date (spoken word before the waiver): nothing is searched or shown. */
    val waiverNeeded: Boolean = false,
    val confirm: ConfirmLarge? = null,
)

/**
 * The archive.org source: shelves and search (public domain only until the waiver), item details and Get. [kind] picks
 * the view: texts (Plan 7, unchanged), or spoken word or music, whose items download through [media] as one group each,
 * an audiobook or an album after the view they were got from. Spoken word has no usable recording date, so before the
 * waiver it searches nothing (Decisions, Plan M2).
 */
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
    private val kind: ArchiveKind = ArchiveKind.TEXTS,
    /** Required for an audio [kind]. */
    private val media: MediaDownloader? = null,
    private val today: () -> LocalDate = LocalDate::now,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    init {
        require(kind == ArchiveKind.TEXTS || media != null) { "An archive.org ${kind.noun} view needs a MediaDownloader" }
    }

    private val audio = kind != ArchiveKind.TEXTS

    private data class Sheet(
        val identifier: String,
        val loading: Boolean,
        val item: ArchiveItem?,
        val offer: Offer?,
        val error: String?,
        val audio: AudioOffer? = null,
    )

    private data class State(
        val tab: ArchiveTab = ArchiveTab.BROWSE,
        /** What is typed in the box, shown at once. */
        val query: String = "",
        /** The text the shown results are for: [search] reads this and [shelf], never [query]. */
        val searchedQuery: String = "",
        val shelf: ArchiveShelf?,
        val filtered: Boolean = true,
        val docs: List<ArchiveDoc> = emptyList(),
        val total: Int = 0,
        val page: Int = 0,
        val loading: Boolean = true,
        val error: String? = null,
        val sheet: Sheet? = null,
        val notice: String? = null,
        val confirm: ConfirmLarge? = null,
    )

    private val state = MutableStateFlow(State(shelf = ArchiveShelf.first(kind), filtered = !prefs.waiverAccepted.value))

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

    /** Installed items' [TextKind]s read from their sidecars on [io]; a missing sidecar isn't cached, so a later one is seen. */
    private val sidecarKinds = ConcurrentHashMap<String, TextKind>()

    /** Audio groups' file counts, read once from their manifests on [io]: the ledger drops a cancelled file's record. */
    private val fileCounts = ConcurrentHashMap<String, Int>()

    // Built on [io]: the Installed tab reads sidecars from disk.
    val ui: StateFlow<ArchiveUi> = combine(state, downloads.records, ::buildUi)
        .flowOn(io)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialUi())

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
                it.copy(searchedQuery = value, shelf = if (value.isBlank()) ArchiveShelf.first(kind) else null)
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
                if (audio) Sheet(identifier, loading = false, item = item, offer = null, error = null, audio = ArchiveAudioOffers.choose(item))
                else Sheet(identifier, loading = false, item = item, offer = ArchiveOffers.choose(item), error = null)
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

    /**
     * Gets the item's download, or with [pdf] the PDF beside its text; one over 100 MB asks first ([confirm]). A set's
     * Get is every part not yet got or on its way (Plan IA).
     */
    fun get(identifier: String, pdf: Boolean = false) = get(identifier, pdf, confirmed = false)

    /** Gets part [number] of a set (Plan IA). */
    fun getPart(identifier: String, number: Int) = get(identifier, pdf = false, confirmed = false, part = number)

    /** Yes to [ArchiveUi.confirm]; [dontAskAgain] turns the prompt off (Settings turns it back on). */
    fun confirmLarge(dontAskAgain: Boolean) {
        val confirm = state.value.confirm ?: return
        state.update { it.copy(confirm = null) }
        if (dontAskAgain) downloadPrefs.askBeforeLarge = false
        get(confirm.identifier, confirm.pdf, confirmed = true, part = confirm.part)
    }

    fun dismissLarge() {
        state.update { it.copy(confirm = null) }
    }

    private fun get(identifier: String, pdf: Boolean, confirmed: Boolean, part: Int? = null) {
        appScope.launch {
            val notice = try {
                if (audio) fetchAndGetAudio(identifier) else fetchAndEnqueue(identifier, pdf, confirmed, part)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageOf(e)
            }
            if (notice != null) state.update { it.copy(notice = notice) }
        }
    }

    fun cancel(identifier: String) {
        if (audio) {
            appScope.launch { media!!.cancel(groupOf(identifier)) }
            return
        }
        appScope.launch {
            // The sheet acts on the item: its text and the PDF beside it go together, and a set's parts.
            downloads.cancel(ArchiveDownloads.itemId(identifier))
            downloads.cancel(ArchiveDownloads.pdfItemId(identifier))
            val records = downloads.records.first()
            for (key in partKeys(identifier, records)) {
                downloads.cancel(ArchiveDownloads.itemId(key))
                val partKept = records.any { it.itemId == ArchiveDownloads.itemId(key) && it.state == DownloadState.INSTALLED }
                if (!partKept) withContext(io) { ArchiveDownloads.deleteSidecar(sidecarDir(), key) }
            }
            val kept = records.any {
                ArchiveDownloads.identifierOf(it.itemId) == identifier && ArchiveDownloads.partNumberOf(it.itemId) == null &&
                    it.state == DownloadState.INSTALLED
            }
            // An installed download keeps the sidecar it is filed (and repaired) with.
            if (!kept) withContext(io) { ArchiveDownloads.deleteSidecar(sidecarDir(), identifier) }.also { sidecarKinds.remove(identifier) }
        }
    }

    fun retryDownload(identifier: String) {
        if (audio) {
            appScope.launch { media!!.retry(groupOf(identifier)) }
            return
        }
        appScope.launch {
            downloads.retry(ArchiveDownloads.itemId(identifier))
            val records = downloads.records.first()
            // A set's parts that stopped.
            for (key in partKeys(identifier, records)) {
                val itemId = ArchiveDownloads.itemId(key)
                if (recordStatus(itemId, records) is ItemStatus.Failed) downloads.retry(itemId)
            }
            // The PDF beside the text only when it is the one that failed.
            val pdf = ArchiveDownloads.pdfItemId(identifier)
            if (downloads.records.first().any { it.itemId == pdf && statusOf(identifier, listOf(it), pdf = true) is ItemStatus.Failed }) {
                downloads.retry(pdf)
            }
        }
    }

    /**
     * The download goes first, then the shelf entry and the metadata sidecar; the book's place and marks stay for a
     * later re-download.
     */
    fun remove(identifier: String) {
        if (audio) {
            appScope.launch {
                val group = groupOf(identifier)
                media!!.remove(group)
                fileCounts.remove(group)
            }
            return
        }
        appScope.launch {
            downloads.remove(ArchiveDownloads.itemId(identifier))
            downloads.remove(ArchiveDownloads.pdfItemId(identifier))
            bookshelf.remove(BookIds.of(BookOrigin.ARCHIVE_ORG, identifier))
            bookshelf.remove(BookIds.of(BookOrigin.ARCHIVE_ORG, identifier + ArchiveDownloads.PDF_SUFFIX))
            for (key in partKeys(identifier, downloads.records.first())) removeKey(key)
            withContext(io) { ArchiveDownloads.deleteSidecar(sidecarDir(), identifier) }.also { sidecarKinds.remove(identifier) }
        }
    }

    /** Removes part [number] of a set: its download, its book and its sidecar (Plan IA). */
    fun removePart(identifier: String, number: Int) {
        val count = (state.value.sheet?.offer as? Offer.Download)?.parts?.size ?: return
        appScope.launch { removeKey(ArchiveDownloads.partKey(identifier, number, count)) }
    }

    private suspend fun removeKey(key: String) {
        downloads.remove(ArchiveDownloads.itemId(key))
        bookshelf.remove(BookIds.of(BookOrigin.ARCHIVE_ORG, key))
        withContext(io) { ArchiveDownloads.deleteSidecar(sidecarDir(), key) }
    }

    /** The keys of [identifier]'s parts with a download record. */
    private fun partKeys(identifier: String, records: List<DownloadRecord>): List<String> = records
        .filter { ArchiveDownloads.identifierOf(it.itemId) == identifier && ArchiveDownloads.partNumberOf(it.itemId) != null }
        .mapNotNull { ArchiveDownloads.bookRefOf(it.itemId) }
        .distinct()

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
        if (waiverNeeded(s.filtered)) {
            // Nothing may be shown, so nothing is asked (Decisions, Plan M2): ArchiveQuery.build refuses it too.
            state.update { it.copy(loading = false, error = null, docs = emptyList(), total = 0, page = 0) }
            return
        }
        val page = if (reset) 1 else s.page + 1
        val query = ArchiveQuery.build(s.searchedQuery, s.shelf, s.filtered, kind, today())
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

    /**
     * Returns the notice to show, or null when the download is on its way or waits on [ArchiveUi.confirm]. With
     * [pdf], the download is the PDF beside the item's text, a second book; without, the item's own download.
     */
    private suspend fun fetchAndEnqueue(identifier: String, pdf: Boolean, confirmed: Boolean, part: Int? = null): String? {
        // Only fetched metadata is downloaded from: a preview's file is a placeholder.
        val item = state.value.sheet?.item?.takeIf { it.identifier == identifier } ?: client.item(identifier)
        if (item.identifier != identifier || !ArchiveQuery.isValidIdentifier(identifier)) return NOTHING_TO_DOWNLOAD
        val offer = ArchiveOffers.choose(item) as? Offer.Download ?: return NOTHING_TO_DOWNLOAD
        if (pdf && offer.pdf == null) return NOTHING_TO_DOWNLOAD
        // The live preference, not the screen's: the waiver may have been withdrawn since this item was reached.
        if (!prefs.waiverAccepted.value && !ArchiveOffers.isPublicDomain(item)) return NOT_PUBLIC_DOMAIN
        val all = withContext(io) { volumes.list() }
        val volume = all.firstOrNull { it.root.path == downloadPrefs.volumePath } ?: all.firstOrNull()
            ?: return "No storage is available right now."
        if (offer.isSet && !pdf) return enqueueParts(item, offer, part, confirmed, volume.root, volume.label)
        val spec = ArchiveDownloads.spec(item, offer, client, pdfCopy = pdf)
        if (!confirmed && downloadPrefs.askBeforeLarge && spec.sizeBytes > DownloadPrefs.LARGE_DOWNLOAD_BYTES) {
            val isPdf = pdf || offer.kind == TextKind.PDF
            state.update { it.copy(confirm = ConfirmLarge(identifier, pdf, spec.sizeBytes, if (isPdf) "PDF" else "download")) }
            return null
        }
        // Written first: the download may finish before enqueue returns. The PDF beside a text shares the item's
        // sidecar: it is written only when there is none, and never deleted here (the text's book is filed with it).
        val existing = ArchiveDownloads.sidecarFile(sidecarDir(), identifier)
        val ownsSidecar = !pdf || !withContext(io) { existing.exists() }
        val sidecar = if (ownsSidecar) withContext(io) { ArchiveDownloads.writeSidecar(sidecarDir(), item, offer.kind) } else existing
        val result = try {
            downloads.enqueue(spec, volume.root, downloadPrefs.wifiOnly)
        } catch (e: Throwable) {
            if (ownsSidecar) withContext(NonCancellable + io) { sidecar.delete() }
            throw e
        }
        // A refused enqueue leaves no sidecar; an installed book keeps its (now refreshed) one for later repairs.
        if (ownsSidecar && (result is EnqueueResult.NoSpace || result is EnqueueResult.OverCap)) {
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

    /**
     * Queues part [part] of a set, or with none every part not yet got or on its way, in order, as one download group
     * (Plan IA). Each part has its own sidecar, titled for it. Over 100 MB in all asks first.
     */
    private suspend fun enqueueParts(
        item: ArchiveItem, offer: Offer.Download, part: Int?, confirmed: Boolean, root: File, label: String,
    ): String? {
        val records = downloads.records.first()
        val count = offer.parts.size
        val wanted = offer.parts.filter { p ->
            if (part != null) return@filter p.number == part
            val status = recordStatus(ArchiveDownloads.itemId(ArchiveDownloads.partKey(item.identifier, p.number, count)), records)
            status == ItemStatus.NotInstalled
        }
        if (wanted.isEmpty()) return null
        val specs = wanted.map { it to ArchiveDownloads.spec(item, offer, client, part = it) }
        val bytes = specs.sumOf { it.second.sizeBytes }
        if (!confirmed && downloadPrefs.askBeforeLarge && bytes > DownloadPrefs.LARGE_DOWNLOAD_BYTES) {
            val title = if (part != null) "part" else if (wanted.size == count) "set" else "${wanted.size} parts"
            state.update { it.copy(confirm = ConfirmLarge(item.identifier, false, bytes, title, part = part, set = part == null)) }
            return null
        }
        for ((p, spec) in specs) {
            // Written first: the download may finish before enqueue returns.
            val sidecar = withContext(io) { ArchiveDownloads.writeSidecar(sidecarDir(), item, offer.kind, p, count) }
            val result = try {
                downloads.enqueue(spec, root, downloadPrefs.wifiOnly)
            } catch (e: Throwable) {
                withContext(NonCancellable + io) { sidecar.delete() }
                throw e
            }
            when (result) {
                EnqueueResult.Queued, EnqueueResult.AlreadyActive, EnqueueResult.AlreadyInstalled -> Unit
                is EnqueueResult.NoSpace -> {
                    withContext(io) { sidecar.delete() }
                    return "Not enough space on $label for ${spec.title}: free up ${formatBytes(result.neededBytes)}."
                }
                is EnqueueResult.OverCap -> {
                    withContext(io) { sidecar.delete() }
                    return "${spec.title} would bring your downloads to ${formatBytes(result.wouldUseBytes)}, " +
                        "over the ${formatBytes(result.capBytes)} limit."
                }
            }
        }
        return null
    }

    /**
     * Gets an audio item as one group, an album (Music) or an audiobook (Audiobooks). Every check reads the live
     * preference, not the screen's: the waiver may have been withdrawn since this item was reached.
     */
    private suspend fun fetchAndGetAudio(identifier: String): String? {
        if (!prefs.waiverAccepted.value && !kind.datedRecordings) return SPOKEN_WORD_GET
        if (!ArchiveQuery.isValidIdentifier(identifier)) return NOTHING_TO_DOWNLOAD
        // Got from the other view: it lives on that shelf, and getting it here would make a second copy of one group.
        if (media!!.mediaRootOf(groupOf(identifier)) == otherRoot) return "This is already in your $otherShelf."
        val item = state.value.sheet?.item?.takeIf { it.identifier == identifier } ?: client.item(identifier)
        if (item.identifier != identifier) return NOTHING_TO_DOWNLOAD
        val offer = ArchiveAudioOffers.choose(item) as? AudioOffer.Tracks ?: return NOTHING_TO_DOWNLOAD
        if (offer.files.isEmpty()) return NOTHING_TO_DOWNLOAD
        if (!prefs.waiverAccepted.value && !ArchiveOffers.isPublicDomainRecording(item, today())) {
            return "This recording wasn't published before ${filterYear()}. Show everything to download it."
        }
        val manifest = MediaDownloads.albumManifest(item, offer, kind, client, now())
        fileCounts[groupOf(identifier)] = manifest.files.size
        return media!!.get(manifest)
    }

    private fun waiverNeeded(filtered: Boolean) = audio && filtered && !kind.datedRecordings

    private fun filterYear() = PublicDomain.lastRecordingYear(today()) + 1

    private fun groupOf(identifier: String) = MediaDownloads.groupId(MediaManifest.ARCHIVE_ORG, identifier)

    private fun initialUi(): ArchiveUi {
        val filtered = !prefs.waiverAccepted.value
        return ArchiveUi(
            shelf = ArchiveShelf.first(kind), filtered = filtered, kind = kind, filterYear = filterYear(),
            waiverNeeded = waiverNeeded(filtered), loading = !waiverNeeded(filtered),
        )
    }

    private fun buildUi(s: State, records: List<DownloadRecord>): ArchiveUi =
        if (audio) buildAudioUi(s, records) else buildTextsUi(s, records)

    /** This view's media folder: an item got from Music lives under `music/`, one from Audiobooks under `audiobooks/`. */
    private val mediaFolder = "/" + (if (kind == ArchiveKind.MUSIC) "music" else "audiobooks") + "/${MediaManifest.ARCHIVE_ORG}/"

    /** The other audio view's media root and shelf name: where an item got from there lives. */
    private val otherRoot = if (kind == ArchiveKind.MUSIC) "audiobooks" else "music"
    private val otherShelf = if (kind == ArchiveKind.MUSIC) "Audiobooks" else "Music"
    private val otherFolder = "/$otherRoot/${MediaManifest.ARCHIVE_ORG}/"

    private fun buildAudioUi(s: State, records: List<DownloadRecord>): ArchiveUi {
        val ours = records.filter { it.groupId?.startsWith(MediaDownloads.groupId(MediaManifest.ARCHIVE_ORG, "")) == true }
        val rows = when (s.tab) {
            ArchiveTab.BROWSE -> s.docs.map { doc ->
                ArchiveRow(
                    identifier = doc.identifier,
                    title = doc.title.ifBlank { doc.identifier },
                    details = listOfNotNull(doc.creator.ifBlank { null }, doc.year?.toString()).joinToString(" · "),
                    preview = null,
                    status = audioStatus(doc.identifier, ours, offerFiles = null),
                    audio = ArchiveAudioOffers.preview(doc),
                )
            }
            // Built from the ledger alone, so it works offline: the groups whose files lie in this view's folder.
            ArchiveTab.INSTALLED -> ours.filter { mediaFolder in it.filePath }.groupBy { it.groupId!! }.mapNotNull { (group, files) ->
                val identifier = MediaDownloads.refOf(group, MediaManifest.ARCHIVE_ORG) ?: return@mapNotNull null
                ArchiveRow(
                    identifier = identifier,
                    title = files.first().title.ifBlank { identifier },
                    details = formatBytes(files.sumOf { it.sizeBytes }),
                    preview = null,
                    status = audioStatus(identifier, ours, offerFiles = null),
                )
            }
        }
        val browsing = s.tab == ArchiveTab.BROWSE
        val needed = waiverNeeded(s.filtered)
        return ArchiveUi(
            tab = s.tab,
            query = s.query,
            shelf = s.shelf,
            filtered = s.filtered,
            rows = rows,
            total = if (browsing) s.total else rows.size,
            loading = browsing && s.loading && !needed,
            canLoadMore = browsing && !s.loading && !needed && s.docs.size < s.total,
            error = if (browsing && !needed) s.error else null,
            sheet = s.sheet?.let { sheet ->
                val tracks = sheet.audio as? AudioOffer.Tracks
                val group = groupOf(sheet.identifier)
                val total = tracks?.files?.size ?: fileCounts[group] ?: 0
                ItemSheet(
                    identifier = sheet.identifier,
                    loading = sheet.loading,
                    item = sheet.item,
                    offer = null,
                    sizeBytes = tracks?.totalBytes,
                    error = sheet.error,
                    status = audioStatus(sheet.identifier, ours, offerFiles = tracks?.files?.size),
                    filesDone = filesDone(group, ours),
                    filesTotal = total,
                )
            },
            notice = s.notice,
            kind = kind,
            filterYear = filterYear(),
            sheetAudio = s.sheet?.audio,
            waiverNeeded = needed,
        )
    }

    private fun filesDone(group: String, records: List<DownloadRecord>): Int = records
        .filter { it.groupId == group && it.state == DownloadState.INSTALLED && !MediaDownloads.isCover(it) }
        .map { it.itemId }.distinct().size

    /** By [MediaDownloads.statusOf], counting the files from the offer, else the item's manifest, else its records. */
    private fun audioStatus(identifier: String, records: List<DownloadRecord>, offerFiles: Int?): ItemStatus {
        val group = groupOf(identifier)
        val mine = records.filter { it.groupId == group }
        if (mine.isEmpty()) return ItemStatus.NotInstalled
        // Got from the other view (its files are under the other root): it's on that shelf, not a download of this view.
        if (mine.any { otherFolder in it.filePath }) return ItemStatus.InLibrary(otherShelf)
        val count = offerFiles ?: fileCounts[group]
            ?: mine.firstNotNullOfOrNull { r -> File(r.filePath).parentFile?.let(MediaManifests::read)?.files?.size }
                ?.also { fileCounts[group] = it }
            ?: mine.filterNot(MediaDownloads::isCover).map { it.itemId }.distinct().size
        return MediaDownloads.statusOf(group, count, records)
    }

    private fun buildTextsUi(s: State, records: List<DownloadRecord>): ArchiveUi {
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
            // Built from the ledger alone, so it works offline. An item's text and the PDF beside it are one row.
            ArchiveTab.INSTALLED -> ours.groupBy { ArchiveDownloads.identifierOf(it.itemId) }.mapNotNull { (identifier, all) ->
                identifier ?: return@mapNotNull null
                // A set is one row: its name, its parts and their size (Plan IA).
                val parts = all.filter { ArchiveDownloads.partNumberOf(it.itemId) != null }.groupBy { it.itemId }.map { it.value.last() }
                if (parts.isNotEmpty()) return@mapNotNull ArchiveRow(
                    identifier = identifier,
                    title = parts.first().title.substringBeforeLast(ArchiveDownloads.PART_TITLE_SEPARATOR).ifBlank { identifier },
                    details = "${parts.size} ${if (parts.size == 1) "part" else "parts"} · ${formatBytes(parts.sumOf { it.sizeBytes })}",
                    preview = previewOf(identifier, parts.first().filePath),
                    status = statusOf(identifier, ours),
                )
                val versions = all.filterNot { ArchiveDownloads.isPdfCopy(it.itemId) }.ifEmpty { all }
                val latest = versions.last()
                // The PDF beside a text is counted too: "561 KB + PDF 109 MB".
                val pdf = all.lastOrNull { ArchiveDownloads.isPdfCopy(it.itemId) }?.takeIf { it !in versions }
                ArchiveRow(
                    identifier = identifier,
                    title = latest.title.ifBlank { identifier },
                    details = listOfNotNull(formatBytes(latest.sizeBytes), pdf?.let { "PDF ${formatBytes(it.sizeBytes)}" }).joinToString(" + "),
                    preview = previewOf(identifier, latest.filePath),
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
                    sizeBytes = (sheet.offer as? Offer.Download)?.totalBytes,
                    error = sheet.error,
                    status = statusOf(sheet.identifier, ours),
                    pdfSizeBytes = (sheet.offer as? Offer.Download)?.pdf?.sizeBytes,
                    pdfStatus = statusOf(sheet.identifier, ours, pdf = true),
                    parts = (sheet.offer as? Offer.Download)?.takeIf { it.isSet }?.let { offer ->
                        offer.parts.map { p ->
                            val itemId = ArchiveDownloads.itemId(ArchiveDownloads.partKey(sheet.identifier, p.number, offer.parts.size))
                            PartRow(p.number, p.title, p.sizeBytes, recordStatus(itemId, ours))
                        }
                    }.orEmpty(),
                )
            },
            notice = s.notice,
            confirm = s.confirm,
        )
    }

    /**
     * Where the item's own download stands, or with [pdf] the PDF's beside its text. A set's is its parts' together
     * (Plan IA): downloading while any is, Partial with some in and none on its way ("Get the rest").
     */
    private fun statusOf(identifier: String, records: List<DownloadRecord>, pdf: Boolean = false): ItemStatus {
        if (pdf) return recordStatus(ArchiveDownloads.pdfItemId(identifier), records)
        val partIds = records.map { it.itemId }.filter {
            ArchiveDownloads.identifierOf(it) == identifier && ArchiveDownloads.partNumberOf(it) != null
        }.distinct()
        if (partIds.isEmpty()) return recordStatus(ArchiveDownloads.itemId(identifier), records)
        val total = maxOf(partIds.size, partCount(identifier))
        val per = partIds.map { recordStatus(it, records) }
        val done = per.count { it == ItemStatus.Installed }
        if (per.any { it is ItemStatus.Downloading }) {
            return ItemStatus.Downloading((done + per.sumOf { (it as? ItemStatus.Downloading)?.fraction?.toDouble() ?: 0.0 }.toFloat()) / total)
        }
        per.firstOrNull { it is ItemStatus.Queued }?.let { return it }
        per.firstOrNull { it is ItemStatus.Failed }?.let { return it }
        return when (done) {
            total -> ItemStatus.Installed
            0 -> ItemStatus.NotInstalled
            else -> ItemStatus.Partial(done, total)
        }
    }

    /** A set's part count from the open sheet's offer; 0 when it isn't open (the records then count). */
    private fun partCount(identifier: String): Int =
        (state.value.sheet?.takeIf { it.identifier == identifier }?.offer as? Offer.Download)?.parts?.size ?: 0

    /** Where one download ([itemId]) stands. */
    private fun recordStatus(itemId: String, records: List<DownloadRecord>): ItemStatus {
        val versions = records.filter { it.itemId == itemId }
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

    /** The sidecar's kind when there is one (an OCR `.txt` looks like plain text on disk), else the file's extension. */
    private fun previewOf(identifier: String, path: String): Offer? {
        val fromSidecar = sidecarKinds[identifier]
            ?: ArchiveDownloads.readSidecar(ArchiveDownloads.sidecarFile(sidecarDir(), identifier))?.second
                ?.also { sidecarKinds[identifier] = it }
        val kind = fromSidecar ?: when (File(path).extension.lowercase()) {
            "epub" -> TextKind.EPUB
            "txt" -> TextKind.TEXT
            "pdf" -> TextKind.PDF
            "cbz", "cbr" -> TextKind.COMIC
            else -> return null
        }
        return Offer.Download(PLACEHOLDER, kind)
    }

    private fun messageOf(e: Exception): String = (e as? ArchiveError)?.message ?: OFFLINE

    private companion object {
        const val DEBOUNCE_MS = 400L
        const val NOTHING_TO_DOWNLOAD = "Nothing on this item can be downloaded."
        const val NOT_PUBLIC_DOMAIN = "This item wasn't published in 1930 or earlier. Show everything to download it."
        const val SPOKEN_WORD_GET = "Show everything to download archive.org spoken word."
        const val OFFLINE = "Couldn't reach archive.org. Check your connection and try again."
        val PLACEHOLDER = ArchiveFile(name = "", format = "", sizeBytes = null, sha1 = null, md5 = null, source = "", isPrivate = false)
    }
}
