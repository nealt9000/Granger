package app.granger.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.archive.ArchiveDownloads
import app.granger.core.library.Book
import app.granger.core.library.BookOrigin
import app.granger.core.library.BookPosition
import app.granger.core.library.Bookshelf
import app.granger.core.library.CollectionRow
import app.granger.core.library.Filing
import app.granger.core.library.Library
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.core.library.ReadingSessions
import app.granger.core.library.Shelf
import app.granger.core.library.Shelves
import app.granger.core.downloads.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One collection's packs and books, or a folder's collections (`ui.children`). Rename and Delete are offered only
 * when `ui.collection.userCreated`; a folder offers nothing.
 */
class CollectionViewModel(
    private val collectionId: String,
    private val shelves: Shelves,
    private val library: Library,
    private val sessions: ReadingSessions,
    private val bookshelf: Bookshelf,
    private val downloads: Downloads,
    private val appScope: CoroutineScope,
    /** Where archive.org sidecars live (R2); null leaves them alone. */
    private val archiveSidecarDir: java.io.File? = null,
) : ViewModel() {
    /** Null until loaded, and again if the collection goes away (emptied or deleted). */
    val ui: StateFlow<CollectionRow?> =
        shelves.collection(collectionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Where the reader left off in each book, by book id. */
    val positions: StateFlow<Map<String, BookPosition>> =
        bookshelf.positions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Ids of the packs here that the Granger Classics e-books replace. */
    val superseded: StateFlow<Set<String>> = ui
        .map { row -> row?.packs.orEmpty().filter(Filing::isSuperseded).map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Every media type and collection a pack or collection can move to. */
    val targets: StateFlow<List<Shelf>> =
        shelves.shelves.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Opens [book] in its rail tab (one per book) and returns the tab id; null, and nothing happens, unless it is readable. */
    suspend fun openBook(book: Book): Long? {
        if (book.status != PackStatus.OK) return null
        return sessions.openTab(book.id, "", book.title)
    }

    fun moveBook(book: Book, toCollectionId: String) {
        viewModelScope.launch { shelves.moveBook(book, toCollectionId) }
    }

    fun moveBookToNewCollection(book: Book, mediaTypeId: String, name: String) {
        viewModelScope.launch { shelves.moveBook(book, shelves.createCollection(name.trim(), mediaTypeId)) }
    }

    fun moveBookToNewMediaType(book: Book, mediaTypeName: String, collectionName: String) {
        viewModelScope.launch {
            val type = shelves.createMediaType(mediaTypeName.trim())
            shelves.moveBook(book, shelves.createCollection(collectionName.trim(), type))
        }
    }

    /**
     * Deletes the book's file from the phone; its place, bookmarks and highlights are kept. A Granger book's download
     * record goes first so it can be fetched again; an archive.org book's metadata sidecar goes last. Runs in the app scope so leaving the screen can't cut it short.
     */
    fun removeBook(book: Book) {
        appScope.launch {
            when (book.origin) {
                BookOrigin.GRANGER -> downloads.remove(book.sourceRef)
                BookOrigin.ARCHIVE_ORG -> downloads.remove(ArchiveDownloads.itemId(book.sourceRef))
                else -> Unit
            }
            bookshelf.remove(book.id)
            if (book.origin == BookOrigin.ARCHIVE_ORG) archiveSidecarDir?.let { ArchiveDownloads.deleteSidecar(it, book.sourceRef) }
        }
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
