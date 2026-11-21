package app.granger.library

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import app.granger.ScreenTextIds
import app.granger.core.catalog.CatalogSource
import app.granger.core.downloads.Downloads
import app.granger.core.library.Bookshelf
import app.granger.core.library.Library
import app.granger.core.library.ManifestCollection
import app.granger.core.library.Pack
import app.granger.core.library.ReadingSessions
import app.granger.core.library.Shelves
import app.granger.library.shelf.LibraryViewPrefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

// Android's side of the shared Library models (core:screens, Plan I5 Task 2): the constructors every screen and test
// calls, with the app's own collaborators, and the resource ids the shared enums name by key.

/** The Library tab on Android: the shared [LibraryModel], importing content URIs through [importer]. */
class LibraryViewModel(
    shelves: Shelves,
    library: Library,
    private val importer: ImportRunner,
    appScope: CoroutineScope,
    /** What a Kiwix book pack's manifest says of its collection; read off the main thread. */
    manifestOf: (Pack) -> ManifestCollection? = ::manifestCollectionOf,
    io: CoroutineDispatcher = Dispatchers.IO,
    /** Where a book pack's provider comes from when its manifest names none (Plan KB2); null leaves it out. */
    catalog: CatalogSource? = null,
    /** The books and their positions, for the reading shelf (Plan O4 Task 5); null leaves it out. */
    bookshelf: Bookshelf? = null,
) : LibraryModel(shelves, library, importer, appScope, manifestOf, io, catalog, bookshelf) {
    /** Imports the chosen files one by one. Runs in the app scope so leaving the screen doesn't stop it. */
    fun import(uris: List<Uri>) = importFiles(importer.picked(uris))
}

/** A collection on Android: the shared [CollectionModel], as it was built before, with the Find field as Compose state. */
class CollectionViewModel(
    collectionId: String,
    shelves: Shelves,
    library: Library,
    sessions: ReadingSessions,
    bookshelf: Bookshelf,
    downloads: Downloads,
    appScope: CoroutineScope,
    /** Where archive.org sidecars live (R2); null leaves them alone. */
    archiveSidecarDir: java.io.File? = null,
    /** Where a Granger book's category comes from; null offers no category chips. */
    catalog: CatalogSource? = null,
    savedState: SavedStateHandle = SavedStateHandle(),
    /** The books shelf's remembered sort and view. */
    viewPrefs: LibraryViewPrefs = LibraryViewPrefs(),
    /** Where the books are filtered and sorted, off the main thread. */
    compute: CoroutineDispatcher = Dispatchers.Default,
    /** "Make all searchable…" for a Gutenberg collection (Plan GB); null offers it nowhere. */
    gutenberg: GutenbergShelf? = null,
    /** What a Kiwix book pack's manifest says of its collection (Plan KB2); read off the main thread. */
    manifestOf: (Pack) -> ManifestCollection? = ::manifestCollectionOf,
    /** A language code's name for the language chips ("eng" → "English"). */
    languageName: (String) -> String = app.granger.sources.KiwixList::languageName,
    /** A book set's level as its section is headed, in the app's language ("Level 2", "Nivel 2"; Plan KB2 Task 3.2). */
    levelName: (Int) -> String = { "Level $it" },
    /** Plan BK: opens a reading edition (a catalog book) without keeping it; its library id, or null. */
    readEdition: suspend (editionId: String) -> String? = { null },
) : CollectionModel(
    collectionId, shelves, library, sessions, bookshelf, downloads, appScope, archiveSidecarDir?.path, catalog, savedState, viewPrefs,
    compute, gutenberg, manifestOf, languageName, levelName, readEdition,
) {
    /** The Find field's text, held as Compose state so typing never waits on a flow. */
    override var findText by mutableStateOf(savedState["query"] ?: "")
        protected set
}

/** The link's title as an Android resource. */
@get:StringRes
val SourceLink.title: Int get() = ScreenTextIds.string(titleKey)

/** The sort's label as an Android resource. */
@get:StringRes
val BookSort.label: Int get() = ScreenTextIds.string(labelKey)
