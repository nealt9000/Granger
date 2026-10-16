package app.granger.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.library.Bookmark
import app.granger.core.library.Library
import app.granger.core.library.PackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BookmarksViewModel(private val library: Library, private val appScope: CoroutineScope) : ViewModel() {
    data class Row(val bookmark: Bookmark, val packTitle: String, val available: Boolean)

    /** Newest first; null until loaded. Bookmarks in missing or damaged packs stay listed but disabled. */
    val rows: StateFlow<List<Row>?> = combine(library.bookmarks, library.packs) { bookmarks, packs ->
        val byId = packs.associateBy { it.id }
        bookmarks.map { b ->
            val pack = byId[b.packId]
            Row(b, pack?.title ?: REMOVED_PACK, pack?.status == PackStatus.OK)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun remove(bookmark: Bookmark) {
        appScope.launch { library.setBookmarked(bookmark.packId, bookmark.path, bookmark.title, false) }
    }

    companion object {
        const val REMOVED_PACK = "Removed pack"
    }
}
