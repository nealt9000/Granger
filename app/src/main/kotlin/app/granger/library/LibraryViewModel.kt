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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import app.granger.core.library.Filing
import app.granger.core.library.bookSource
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * An archive source the empty Music and Audiobooks screens (and an empty Listen) link to. The three archive.org links are its texts,
 * spoken-word and music views.
 */
enum class SourceLink(@androidx.annotation.StringRes val title: Int) {
    GRANGER(app.granger.R.string.library_link_granger), KIWIX(app.granger.R.string.library_link_kiwix),
    ARCHIVE_ORG(app.granger.R.string.library_link_archive), LIBRIVOX(app.granger.R.string.library_link_librivox),
    ARCHIVE_ORG_SPOKEN(app.granger.R.string.library_link_archive), ARCHIVE_ORG_MUSIC(app.granger.R.string.library_link_archive),
}

/**
 * One Library row: a kind of thing the phone holds, opening to its collections (Plan U3b Decision 4). [mediaTypeId] is
 * the media type its collections are filed under; [id] keeps its collapsed state.
 */
data class KindRow(val id: String, val title: app.granger.UiText, val mediaTypeId: String, val collections: List<CollectionRow>) {
    /** Every collection here, each folder followed by its children. */
    val allCollections: List<CollectionRow> get() = collections.flatMap { it.withChildren }
}

/**
 * The Library: the books the phone holds, by kind. [manifests] are the Kiwix book packs' manifests by their collection's
 * default key, for the rows' noun and provider (Plan KB2 Task 4.2).
 */
data class LibraryUi(
    val kinds: List<KindRow>,
    val emptyLibrary: Boolean,
    val manifests: Map<String, app.granger.core.library.ManifestCollection> = emptyMap(),
    /** Each Kiwix book pack collection's provider by default key, from the catalog (Plan KB2): when its manifest names none. */
    val providers: Map<String, String> = emptyMap(),
    /** The reading shelf (Plan O4 Task 5): reading now and finished. */
    val shelf: ReadingShelfUi = ReadingShelfUi(emptyList(), emptyList()),
)

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
        listOfNotNull(literature?.let { KindRow(BOOKS_KIND, app.granger.UiText.Res(app.granger.R.string.library_books), LITERATURE, it.bookCollections()) }) +
            // A media type's name is saved in library.db (the defaults in English): translating it is L2's.
            rest.map { KindRow(it.mediaType.id, app.granger.UiText.Raw(it.mediaType.name), it.mediaType.id, it.bookCollections()) }
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
        .mapNotNull { id -> byId[id]?.let { KindRow(id, app.granger.UiText.Raw(it.mediaType.name), id, it.collections) } }
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
    /** What a Kiwix book pack's manifest says of its collection; read off the main thread. */
    private val manifestOf: (app.granger.core.library.Pack) -> app.granger.core.library.ManifestCollection? = ::manifestCollectionOf,
    private val io: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    /** Where a book pack's provider comes from when its manifest names none (Plan KB2); null leaves it out. */
    private val catalog: app.granger.core.catalog.CatalogSource? = null,
    /** The books and their positions, for the reading shelf (Plan O4 Task 5); null leaves it out. */
    private val bookshelf: app.granger.core.library.Bookshelf? = null,
) : ViewModel() {
    private val _notice = MutableStateFlow<app.granger.UiText?>(null)

    /** What the last import did, shown once as a snackbar. */
    val notice: StateFlow<app.granger.UiText?> = _notice

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
    val ui: StateFlow<LibraryUi?> = kotlinx.coroutines.flow.combine(shelves.shelves, manifests(), providers(), reading()) { shelves, manifests, providers, shelf ->
        // Empty means no books: packs alone fill Browse, and music alone fills Listen.
        libraryKinds(shelves).let { LibraryUi(it, emptyLibrary = it.isEmpty(), manifests = manifests, providers = providers, shelf = shelf) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun reading() = bookshelf?.let { b -> kotlinx.coroutines.flow.combine(b.books, b.positions) { books, positions -> ReadingShelf.of(books, positions) } }
        ?: kotlinx.coroutines.flow.flowOf(ReadingShelfUi(emptyList(), emptyList()))

    /** The catalog's book pack providers by their collections' default key (Plan KB2): `kiwixbooks-<ZIM name>`. */
    private fun providers() = (catalog?.state ?: kotlinx.coroutines.flow.flowOf(null))
        .map { state -> bookPackProviders(state?.catalog) }
        .distinctUntilChanged()

    /** The manifests of the Kiwix book packs on the phone, by their collection's default key. */
    private fun manifests() = library.packs
        .map { packs -> packs.filter { it.bookSource == app.granger.core.library.BookOrigin.KIWIX } }
        .distinctUntilChanged()
        .map { packs -> packs.mapNotNull { pack -> manifestOf(pack)?.let { Filing.defaultKey(pack) to it } }.toMap() }
        .flowOn(io)
        .onStart { emit(emptyMap()) }

    /** Re-scan pack folders; called whenever the screen resumes (an SD card may have come back). */
    fun refresh() {
        viewModelScope.launch { library.refresh() }
    }
}

/**
 * The catalog's provider of each Kiwix book pack (Plan KB2), by its collection's default key (`kiwixbooks-<ZIM name>`):
 * what a collection says when its manifest names no one (nautilus packs: "From Kiwix · Prunelle").
 */
fun bookPackProviders(catalog: app.granger.core.catalog.Catalog?): Map<String, String> =
    catalog?.packs.orEmpty().filter { it.isBookPack && it.provider.isNotBlank() && it.zimName.isNotBlank() }
        .associate { Filing.kiwixBooksKey(it.zimName) to it.provider }
