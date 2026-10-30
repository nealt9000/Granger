package app.granger.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.catalog.CatalogSource
import app.granger.core.downloads.DownloadQueueSummary
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.VolumeSource
import app.granger.core.library.Bookshelf
import app.granger.core.library.Library
import app.granger.core.library.ReadingSessions
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Midnight today, on the phone's clock: where Done today starts. */
fun startOfToday(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** Every [millis], for text that ages ("Trying again in 2 min") while nothing else changes. */
fun ticks(millis: Long): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(millis)
    }
}

/**
 * Downloads (Plan DL Decision 8): the whole queue, what it's doing and how long it has left, what stopped and what
 * landed today. The actions run in the app scope, so leaving the screen can't cut them short.
 */
class DownloadsViewModel(
    private val downloads: Downloads,
    private val library: Library,
    bookshelf: Bookshelf,
    private val sessions: ReadingSessions,
    private val appScope: CoroutineScope,
    catalog: CatalogSource? = null,
    volumes: VolumeSource? = null,
    private val today: () -> Long = ::startOfToday,
    private val clock: () -> Long = System::currentTimeMillis,
    tick: Flow<Unit> = ticks(10_000),
    /** A volume's free bytes; read on each change, not the whole volume walk [VolumeSource] does. */
    private val freeBytes: (File) -> Long = { it.usableSpace },
    /** Where the page is worked out (and the volumes listed): off the main thread; tests pass theirs. */
    work: CoroutineDispatcher = Dispatchers.Default,
    private val tidyDismissals: app.granger.sources.TidyDismissals = app.granger.sources.TidyDismissals(),
) : ViewModel() {
    private val catalogIds = catalog?.state?.map { s -> s.catalog?.let { c -> (c.packs + c.models + c.books + c.voices).mapTo(HashSet()) { it.id } } }
        ?: flowOf(null)

    /** The volumes, listed once (their roots and labels); free space is read again each time. */
    private val volumeList = flow { emit(volumes?.list().orEmpty()) }

    private val shelves = combine(library.packs, bookshelf.books, catalogIds, volumeList) { p, b, ids, v -> Shelf(p, b, ids, v) }

    private data class Shelf(
        val packs: List<app.granger.core.library.Pack>, val books: List<app.granger.core.library.Book>, val ids: Set<String>?,
        val volumes: List<app.granger.core.downloads.Volume>,
    )

    val ui: StateFlow<DownloadsUi> = combine(downloads.records, downloads.live, shelves, tick) { records, live, shelf, _ ->
        val spaces = shelf.volumes.map { VolumeSpace(it.root.path, it.label, freeBytes(it.root)) }
        val summary = DownloadQueueSummary.of(records, live, clock(), spaces.associate { it.root to it.freeBytes })
        DownloadsModel.of(records, summary, today(), shelf.packs, shelf.books, shelf.ids, spaces)
    }.flowOn(work).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUi())

    /** A bigger slice is in: the offers to remove the smaller (Plan WF Decision 5); never removed without a yes. */
    val tidy: StateFlow<List<app.granger.sources.TidyOffer>> =
        combine(catalog?.state ?: flowOf(null), downloads.records, library.packs, tidyDismissals.keys) { c, records, packs, keys ->
            app.granger.sources.SetTidy.offers(c?.catalog, records, packs, keys)
        }.flowOn(work).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Yes to a tidy offer: the smaller slice goes, its places and bookmarks moving to the bigger. */
    fun removeSmaller(offer: app.granger.sources.TidyOffer) {
        appScope.launch {
            if (!app.granger.sources.SetTidy.remove(offer, library, downloads)) tidyDismissals.dismiss(offer.key)
        }
    }

    /** Keep both: the offer doesn't come back. */
    fun keepBoth(offer: app.granger.sources.TidyOffer) = tidyDismissals.dismiss(offer.key)

    /** The clock the page's "Trying again in 2 min" reads. */
    fun now(): Long = clock()

    fun pause() {
        appScope.launch { downloads.pause() }
    }

    fun resume() {
        appScope.launch { downloads.resume() }
    }

    /** Moves the row's item ahead of everything else waiting; it starts when the current file finishes. */
    fun downloadNext(row: DownloadRow) {
        appScope.launch { row.itemIds.firstOrNull()?.let { downloads.downloadNext(it) } }
    }

    /** Stops the row's item and its index; what's installed stays. */
    fun cancel(row: DownloadRow) {
        appScope.launch { row.itemIds.forEach { downloads.cancel(it) } }
    }

    /** Stops every unfinished file of the row's group. */
    fun cancelGroup(row: DownloadRow) {
        appScope.launch { row.groupId?.let { downloads.cancelGroup(it) } }
    }

    /** Queues the row's failed files again. */
    fun retry(row: DownloadRow) {
        appScope.launch { row.itemIds.forEach { downloads.retry(it) } }
    }

    /** Retry all: every stopped row that can be fetched again. */
    fun retryAll(rows: List<DownloadRow>) {
        appScope.launch { rows.filterNot { it.removeOnly }.flatMap { it.itemIds }.forEach { downloads.retry(it) } }
    }

    /** Deletes a row that can't be fetched again (withdrawn, or gone from the catalog), part file and all. */
    fun remove(row: DownloadRow) {
        appScope.launch { row.itemIds.forEach { downloads.remove(it) } }
    }

    /** Opens a finished book in its rail tab and returns the tab id. Packs open through the NavHost's openPack. */
    suspend fun openBook(target: OpenTarget.Book, title: String): Long = sessions.openTab(target.bookId, "", title)
}
