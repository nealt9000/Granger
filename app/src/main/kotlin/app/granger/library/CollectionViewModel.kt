package app.granger.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.library.CollectionRow
import app.granger.core.library.Library
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.core.library.ReadingSessions
import app.granger.core.library.Shelf
import app.granger.core.library.Shelves
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One collection's packs. Rename and Delete are offered only when `ui.collection.userCreated`. */
class CollectionViewModel(
    private val collectionId: String,
    private val shelves: Shelves,
    private val library: Library,
    private val sessions: ReadingSessions,
) : ViewModel() {
    /** Null until loaded, and again if the collection goes away (emptied or deleted). */
    val ui: StateFlow<CollectionRow?> =
        shelves.collection(collectionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Every media type and collection a pack or collection can move to. */
    val targets: StateFlow<List<Shelf>> =
        shelves.shelves.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Opens [pack] at its last-read page in a rail tab and returns the tab id, or null when the pack can't be
     * read. The tab is titled with the pack until the page reports its own title.
     */
    suspend fun open(pack: Pack): Long? {
        if (pack.status != PackStatus.OK) return null
        val path = library.startPath(pack.id) ?: return null
        return sessions.openTab(pack.id, path, pack.title)
    }

    fun movePack(pack: Pack, toCollectionId: String) {
        viewModelScope.launch { shelves.movePack(pack, toCollectionId) }
    }

    fun movePackToNewCollection(pack: Pack, mediaTypeId: String, name: String) {
        viewModelScope.launch { shelves.movePack(pack, shelves.createCollection(name.trim(), mediaTypeId)) }
    }

    fun movePackToNewMediaType(pack: Pack, mediaTypeName: String, collectionName: String) {
        viewModelScope.launch {
            val type = shelves.createMediaType(mediaTypeName.trim())
            shelves.movePack(pack, shelves.createCollection(collectionName.trim(), type))
        }
    }

    fun moveCollection(toMediaTypeId: String) {
        viewModelScope.launch { shelves.moveCollection(collectionId, toMediaTypeId) }
    }

    fun moveCollectionToNewMediaType(name: String) {
        viewModelScope.launch { shelves.moveCollection(collectionId, shelves.createMediaType(name.trim())) }
    }

    fun rename(name: String) {
        viewModelScope.launch { shelves.renameCollection(collectionId, name.trim()) }
    }

    fun delete() {
        viewModelScope.launch { shelves.deleteCollection(collectionId) }
    }

    /** Deletes the pack's file from the phone. */
    fun remove(pack: Pack) {
        viewModelScope.launch { library.remove(pack.id) }
    }
}
