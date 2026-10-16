package app.granger.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.library.Library
import app.granger.core.library.Shelf
import app.granger.core.library.Shelves
import app.granger.sources.CatalogScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Library's two sections: [media] (media types with their collections) and the archive [sources]. */
data class LibraryUi(val media: List<Shelf>, val sources: List<CatalogScope>, val emptyLibrary: Boolean)

class LibraryViewModel(shelves: Shelves, private val library: Library) : ViewModel() {
    /** Null until the first load finishes. */
    val ui: StateFlow<LibraryUi?> = shelves.shelves
        .map { LibraryUi(it, listOf(CatalogScope.GRANGER, CatalogScope.KIWIX), emptyLibrary = it.isEmpty()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Re-scan pack folders; called whenever the screen resumes (an SD card may have come back). */
    fun refresh() {
        viewModelScope.launch { library.refresh() }
    }
}
