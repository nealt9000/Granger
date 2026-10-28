package app.granger.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.library.CollectionRow
import app.granger.core.library.Library
import app.granger.core.library.Shelf
import app.granger.core.library.Shelves
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * An archive source the empty Music and Audiobooks screens link to. The three archive.org links are its texts,
 * spoken-word and music views.
 */
enum class SourceLink(val title: String) {
    GRANGER("Granger"), KIWIX("Kiwix"), ARCHIVE_ORG("Archive.org"),
    LIBRIVOX("LibriVox"), ARCHIVE_ORG_SPOKEN("Archive.org"), ARCHIVE_ORG_MUSIC("Archive.org"),
}

/**
 * One Library row: a kind of thing the phone holds, opening to its collections (Plan U3b Decision 4). [mediaTypeId] is
 * the media type its collections are filed under; [id] keeps its collapsed state.
 */
data class KindRow(val id: String, val title: String, val mediaTypeId: String, val collections: List<CollectionRow>) {
    /** Every collection here, each folder followed by its children. */
    val allCollections: List<CollectionRow> get() = collections.flatMap { it.withChildren }
}

/** The Library: what the phone holds, by kind. */
data class LibraryUi(val kinds: List<KindRow>, val emptyLibrary: Boolean)

const val REFERENCE_KIND = "reference"
const val BOOKS_KIND = "books"

/**
 * The Library's rows, each only when it holds something. Literature splits in two: its packs (the Kiwix folder, and
 * any collection with a pack in it) are Encyclopedias & reference, its book collections are Books. Maps, Audiobooks
 * and Music follow; any other media type (Video, one the user made) comes after, in its own order. No schema change:
 * the media types stay as they are.
 */
fun libraryKinds(shelves: List<Shelf>): List<KindRow> {
    val byId = shelves.associateBy { it.mediaType.id }
    val literature = byId[LITERATURE]?.collections.orEmpty()
    val (reference, books) = literature.partition { row -> row.isFolder || row.withChildren.any { it.packs.isNotEmpty() } }
    val rest = shelves.filter { it.mediaType.id != LITERATURE }
        .sortedBy { FOLLOWING.indexOf(it.mediaType.id).let { i -> if (i < 0) FOLLOWING.size else i } }
    return listOf(
        KindRow(REFERENCE_KIND, "Encyclopedias & reference", LITERATURE, reference),
        KindRow(BOOKS_KIND, "Books", LITERATURE, books),
    ).filter { it.collections.isNotEmpty() } +
        rest.map { KindRow(it.mediaType.id, it.mediaType.name, it.mediaType.id, it.collections) }
}

private const val LITERATURE = "literature"

/** The built-in media types after Literature's two rows, in the Library's order. */
private val FOLLOWING = listOf("maps", "audiobooks", "music")

class LibraryViewModel(
    shelves: Shelves,
    private val library: Library,
    private val importer: ImportRunner,
    private val appScope: CoroutineScope,
) : ViewModel() {
    private val _notice = MutableStateFlow<String?>(null)

    /** What the last import did, shown once as a snackbar. */
    val notice: StateFlow<String?> = _notice

    fun dismissNotice() {
        _notice.value = null
    }

    /** Imports the chosen files one by one. Runs in the app scope so leaving the screen doesn't stop it. */
    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        appScope.launch {
            _notice.value = importer.run(uris).text()
        }
    }

    /** Null until the first load finishes. */
    val ui: StateFlow<LibraryUi?> = shelves.shelves
        .map { LibraryUi(libraryKinds(it), emptyLibrary = it.isEmpty()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Re-scan pack folders; called whenever the screen resumes (an SD card may have come back). */
    fun refresh() {
        viewModelScope.launch { library.refresh() }
    }
}
