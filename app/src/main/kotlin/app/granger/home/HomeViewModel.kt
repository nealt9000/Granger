package app.granger.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.catalog.CatalogSource
import app.granger.core.downloads.Downloads
import app.granger.core.library.Bookshelf
import app.granger.core.library.Library
import app.granger.readiness.ReadinessModel
import app.granger.readiness.ReadinessReport
import app.granger.shell.RailEntry
import app.granger.shell.RailUi
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Home (spec §5): the readiness strip, then where you left off. */
data class HomeUi(
    val strip: ReadinessReport = ReadinessReport.EMPTY,
    /** The rail's tabs and plays, as the rail orders them (pins first, then newest), at most [HomeViewModel.CONTINUE]. */
    val continueCards: List<RailEntry> = emptyList(),
)

/**
 * Home's data: the readiness report over the same four flows the Readiness screen uses, and Continue from the rail's
 * list (no second query, no second cover loader). The report is computed on [io].
 */
class HomeViewModel(
    catalog: CatalogSource,
    library: Library,
    bookshelf: Bookshelf,
    downloads: Downloads,
    rail: StateFlow<RailUi>,
    io: CoroutineDispatcher = Dispatchers.IO,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    private val strip = combine(catalog.state, library.packs, bookshelf.books, downloads.records) { c, packs, books, records ->
        ReadinessModel.report(c.catalog, packs, books, records, today())
    }.flowOn(io)

    val ui: StateFlow<HomeUi> = combine(strip, rail) { report, railUi ->
        HomeUi(strip = report, continueCards = continueCards(railUi))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    init {
        // The cached catalog, no network; MainActivity loads it too, and the repository loads once.
        viewModelScope.launch { catalog.load() }
    }

    companion object {
        const val CONTINUE = 8

        /** The rail's tabs and plays in the rail's order; searches, chats and the voice's live row are not "continue". */
        fun continueCards(rail: RailUi): List<RailEntry> =
            rail.entries.filter { it is RailEntry.Tab || it is RailEntry.Play }.take(CONTINUE)
    }
}
