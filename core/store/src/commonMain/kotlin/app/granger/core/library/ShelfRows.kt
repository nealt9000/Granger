package app.granger.core.library

// Shared since Plan I4b Task 8 (Home's feeds resolve collections on both phones); `Shelves` fills them on Android.

/** A media type with the non-empty collections filed under it; [collections] are its top level, folders holding their children. */
data class Shelf(val mediaType: MediaType, val collections: List<CollectionRow>) {
    /** Every collection shown here, each folder followed by its children. */
    val allCollections: List<CollectionRow> get() = collections.flatMap { it.withChildren }
}

/** A collection and what's filed in it. A folder (the Kiwix folder) holds no items of its own, only [children]. */
data class CollectionRow(
    val collection: ShelfCollection,
    val packs: List<Pack>,
    val books: List<Book> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val audiobooks: List<Audiobook> = emptyList(),
    /** The non-empty collections in this folder, sorted by name; empty for an ordinary collection. */
    val children: List<CollectionRow> = emptyList(),
) {
    /** True when nothing is filed here or in any child. */
    val isEmpty: Boolean get() =
        packs.isEmpty() && books.isEmpty() && albums.isEmpty() && audiobooks.isEmpty() && children.all { it.isEmpty }

    /** The app-made Kiwix folder; it isn't a place to move things to. */
    val isFolder: Boolean get() = collection.defaultKey == FilingKeys.FOLDER_KEY

    val withChildren: List<CollectionRow> get() = listOf(this) + children
}
