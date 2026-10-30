package app.granger.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.catalog.CatalogSource
import app.granger.core.downloads.Downloads
import app.granger.core.library.Bookshelf
import app.granger.core.library.Library
import app.granger.core.library.ReadingSessions
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Midnight today, on the phone's clock: where Done today starts. */
fun startOfToday(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/**
 * Downloads (Plan U3b Decision 1): what's coming, what stopped and what landed today. Cancel and Retry run in the app
 * scope, so leaving the screen can't cut them short.
 */
class DownloadsViewModel(
    private val downloads: Downloads,
    library: Library,
    bookshelf: Bookshelf,
    private val sessions: ReadingSessions,
    private val appScope: CoroutineScope,
    catalog: CatalogSource? = null,
    private val today: () -> Long = ::startOfToday,
) : ViewModel() {
    private val catalogIds = catalog?.state?.map { s -> s.catalog?.let { c -> (c.packs + c.models + c.books + c.voices).mapTo(HashSet()) { it.id } } }
        ?: flowOf(null)

    val ui: StateFlow<DownloadsUi> = combine(downloads.records, library.packs, bookshelf.books, catalogIds) { records, packs, books, ids ->
        DownloadsModel.of(records, today(), packs, books, ids)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUi())

    /** Stops the row's group and items; what's installed stays. */
    fun cancel(row: DownloadRow) {
        appScope.launch {
            row.groupId?.let { downloads.cancelGroup(it) }
            row.itemIds.forEach { downloads.cancel(it) }
        }
    }

    /** Queues the row's failed files again. */
    fun retry(row: DownloadRow) {
        appScope.launch {
            row.groupId?.let { downloads.retryGroup(it) }
            row.itemIds.forEach { downloads.retry(it) }
        }
    }

    /** Deletes a row that can't be fetched again (withdrawn, or gone from the catalog), part file and all. */
    fun remove(row: DownloadRow) {
        appScope.launch { row.itemIds.forEach { downloads.remove(it) } }
    }

    /** Opens a finished book in its rail tab and returns the tab id. Packs open through the NavHost's openPack. */
    suspend fun openBook(target: OpenTarget.Book, title: String): Long = sessions.openTab(target.bookId, "", title)
}
