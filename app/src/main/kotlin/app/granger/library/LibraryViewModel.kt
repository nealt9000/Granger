package app.granger.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.audiobooks.AUDIOBOOKS_TYPE_ID
import app.granger.core.library.AudioShelves
import app.granger.core.library.CollectionRow
import app.granger.core.library.Library
import app.granger.core.library.Shelf
import app.granger.core.library.Shelves
import app.granger.music.MUSIC_TYPE_ID
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * An archive source the empty Music and Audiobooks screens (and an empty Listen) link to. The three archive.org links are its texts,
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

/** The Library: the books the phone holds, by kind. */
data class LibraryUi(val kinds: List<KindRow>, val emptyLibrary: Boolean)

const val BOOKS_KIND = "books"

/**
 * The Library's rows (Plan U3c Decision 2: the Library is books), each only when it holds something. Literature's
 * collections that hold a book are Books (Granger Classics, My books, Archive.org texts, imports, and the user's own);
 * any other media type with books in it (Video, one the user made) comes after, in its own order. Packs and maps are
 * Browse's ([app.granger.browse.browseSections]), so the Kiwix folder and a collection holding only packs aren't
 * here; a collection holding both stays. Music and Audiobooks are Listen's ([listenKinds], Plan U2 Decision 4). No
 * schema change: the media types stay as they are.
 */
fun libraryKinds(shelves: List<Shelf>): List<KindRow> {
    fun Shelf.bookCollections() = collections.filter { row -> row.withChildren.any { it.books.isNotEmpty() } }
    val literature = shelves.firstOrNull { it.mediaType.id == LITERATURE }
    val rest = shelves.filter { it.mediaType.id != LITERATURE && it.mediaType.id !in AUDIO_TYPE_IDS }
    return (
        listOfNotNull(literature?.let { KindRow(BOOKS_KIND, "Books", LITERATURE, it.bookCollections()) }) +
            rest.map { KindRow(it.mediaType.id, it.mediaType.name, it.mediaType.id, it.bookCollections()) }
        ).filter { it.collections.isNotEmpty() }
}

/**
 * Listen's shelves (Plan U2 Decisions 4 and 7): Music, then Audiobooks, each only when it holds something that plays
 * ([AudioShelves]: an audiobook that's missing or won't open doesn't make a shelf). Podcasts join them once
 * they exist.
 */
fun listenKinds(shelves: List<Shelf>): List<KindRow> {
    val byId = shelves.associateBy { it.mediaType.id }
    val audio = AudioShelves.of(shelves)
    val held = mapOf(MUSIC_TYPE_ID to audio.music, AUDIOBOOKS_TYPE_ID to audio.audiobooks)
    return AUDIO_TYPE_IDS.filter { held[it] == true }
        .mapNotNull { id -> byId[id]?.let { KindRow(id, it.mediaType.name, id, it.collections) } }
        .filter { it.collections.isNotEmpty() }
}

private const val LITERATURE = "literature"

/** The media types Listen holds rather than the Library, in Listen's order. */
private val AUDIO_TYPE_IDS = listOf(MUSIC_TYPE_ID, AUDIOBOOKS_TYPE_ID)

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
        // Empty means no books: packs alone fill Browse, and music alone fills Listen.
        .map { shelves -> libraryKinds(shelves).let { LibraryUi(it, emptyLibrary = it.isEmpty()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Re-scan pack folders; called whenever the screen resumes (an SD card may have come back). */
    fun refresh() {
        viewModelScope.launch { library.refresh() }
    }
}
