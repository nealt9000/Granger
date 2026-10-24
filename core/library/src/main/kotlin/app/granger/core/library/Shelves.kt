package app.granger.core.library

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
    val isFolder: Boolean get() = collection.defaultKey == Filing.FOLDER_KEY

    val withChildren: List<CollectionRow> get() = listOf(this) + children
}

/** The library's media type, collection and placement structure. */
interface Shelves {
    /**
     * Media types holding at least one pack, book, album or audiobook, in sort order; collections sorted by name, empty
     * app-made ones hidden. A folder is one row carrying its children, and is hidden when they're all empty.
     */
    val shelves: Flow<List<Shelf>>

    /** One collection with its packs and books (a folder with its non-empty children), or null if it doesn't exist. */
    fun collection(id: String): Flow<CollectionRow?>

    /** A manual move; it survives the pack being updated to a new snapshot. */
    suspend fun movePack(pack: Pack, toCollectionId: String)

    /** A manual move; it survives the book being re-downloaded. */
    suspend fun moveBook(book: Book, toCollectionId: String)

    /** A manual move; it survives the album's tracks being re-scanned. */
    suspend fun moveAlbum(album: AlbumSummary, toCollectionId: String)

    /** A manual move; it survives the audiobook being re-scanned. */
    suspend fun moveAudiobook(book: Audiobook, toCollectionId: String)

    /** Moves the collection to the top of [toMediaTypeId], out of any folder. */
    suspend fun moveCollection(collectionId: String, toMediaTypeId: String)

    /** Returns the new id. */
    suspend fun createMediaType(name: String): String

    /** False, and nothing changes, if the type is built-in or still holds anything. */
    suspend fun deleteMediaType(id: String): Boolean

    /** Returns the new id. */
    suspend fun createCollection(name: String, mediaTypeId: String): String

    suspend fun renameCollection(id: String, name: String)

    /** Deletes the collection; its items go back to their default collections, whatever their `userSet`. */
    suspend fun deleteCollection(id: String)
}

/**
 * Packs, books, albums and audiobooks share one placement table: a pack's key is [Filing.packKey], a book's is
 * [Filing.bookKey] (`book:...`), an album's [Filing.albumKey] (`album:...`) and an audiobook's [Filing.audiobookKey]
 * (`audiobook:...`), prefixes no pack name or id takes, so all are filed, moved and re-filed by the same paths.
 */
class RoomShelves(
    private val db: LibraryDatabase,
    private val library: Library,
    private val bookshelf: Bookshelf,
    private val media: MediaLibrary,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : Shelves {
    private val dao = db.shelfDao()

    /** Everything the shelves show. [albums] are built from the music tracks; [albumOrigins] maps an album key to its first track's origin. */
    private data class Items(
        val packs: List<Pack>,
        val books: List<Book>,
        val albums: List<AlbumSummary>,
        val audiobooks: List<Audiobook>,
        val albumOrigins: Map<String, MediaOrigin>,
    )

    /** What filing needs to know about an item. [folderKey] is the `defaultKey` of the folder a new collection for it goes in. */
    private class Filable(
        val key: String,
        val defaultKey: String,
        val name: () -> String,
        val mediaTypeId: String,
        val folderKey: String? = null,
    )

    private fun filables(items: Items): List<Filable> {
        val (packs, books, albums, audiobooks, origins) = items
        return packs.map { pack ->
            val key = Filing.defaultKey(pack)
            Filable(Filing.packKey(pack), key, { Filing.defaultName(key, pack) }, Filing.defaultMediaType(pack), Filing.folderKey(pack))
        } + books.map { book ->
            val key = Filing.defaultKey(book)
            Filable(Filing.bookKey(book), key, { Filing.defaultName(key, book) }, Filing.defaultMediaType(book))
        } + albums.map { album ->
            mediaFilable(Filing.albumKey(album), origins[album.key] ?: MediaOrigin.PHONE, Filing.defaultMediaType(album))
        } + audiobooks.map { book ->
            mediaFilable(Filing.audiobookKey(book), book.origin, Filing.defaultMediaType(book))
        }
    }

    private fun mediaFilable(key: String, origin: MediaOrigin, mediaTypeId: String): Filable {
        val originKey = Filing.defaultKey(origin)
        return Filable(key, Filing.mediaCollectionKey(mediaTypeId, originKey), { Filing.defaultName(originKey) }, mediaTypeId)
    }

    /** Files unplaced packs and books, then reads the settled state. Idempotent: a second pass writes nothing. */
    private suspend fun settled(items: List<Filable>): Triple<List<MediaType>, List<ShelfCollection>, Map<String, String>> =
        db.withTransaction {
            var placements = dao.getPlacements().associate { it.packKey to it.collectionId }
            val collections = dao.getCollections()
            var filed = false
            for (item in items) {
                val placed = placements[item.key]
                if (placed != null && collections.any { it.id == placed }) continue
                val collection = fileInto(item)
                placements = placements + (item.key to collection.id)
                dao.upsertPlacement(Placement(item.key, collection.id, userSet = false))
                filed = true
            }
            // Filing may have made collections (and a folder); read them back rather than track each.
            Triple(dao.getMediaTypes(), if (filed) dao.getCollections() else collections, placements)
        }

    /**
     * The collection with [item]'s default key, whatever its media type or folder now (the user may have moved it).
     * A new one goes in [Filable.folderKey]'s folder, made on first use.
     */
    private suspend fun fileInto(item: Filable): ShelfCollection {
        dao.collectionByDefaultKey(item.defaultKey)?.let { return it }
        val created = ShelfCollection(
            id = "c:${item.defaultKey}",
            name = item.name(),
            mediaTypeId = item.mediaTypeId,
            userCreated = false,
            defaultKey = item.defaultKey,
            parentId = item.folderKey?.let { folder(it, item.mediaTypeId).id },
        )
        dao.upsertCollection(created)
        return created
    }

    /** The app-made folder with [key], made in [mediaTypeId] when it's missing. */
    private suspend fun folder(key: String, mediaTypeId: String): ShelfCollection {
        dao.collectionByDefaultKey(key)?.let { return it }
        val created = ShelfCollection("c:$key", Filing.FOLDER_NAME, mediaTypeId, userCreated = false, defaultKey = key)
        dao.upsertCollection(created)
        return created
    }

    private fun trigger(): Flow<Items> {
        val items = combine(library.packs, bookshelf.books, media.tracks, media.audiobooks) { packs, books, tracks, audiobooks ->
            val origins = HashMap<String, MediaOrigin>()
            tracks.forEach { origins.putIfAbsent(it.albumKey, it.origin) }
            Items(packs, books.filter { it.onShelf }, Albums.summaries(tracks), audiobooks, origins)
        }
        return combine(items, dao.observeMediaTypes(), dao.observeCollections(), dao.observePlacements()) { it, _, _, _ -> it }
    }

    private fun rows(collections: List<ShelfCollection>, placements: Map<String, String>, items: Items) =
        collections.sortedBy { it.name.lowercase() }.map { collection ->
            CollectionRow(
                collection,
                items.packs.filter { placements[Filing.packKey(it)] == collection.id },
                items.books.filter { placements[Filing.bookKey(it)] == collection.id },
                items.albums.filter { placements[Filing.albumKey(it)] == collection.id },
                items.audiobooks.filter { placements[Filing.audiobookKey(it)] == collection.id },
            )
        }

    /**
     * Puts each row whose parent is a top-level collection of the same media type in that folder's [CollectionRow.children],
     * dropping empty app-made children; any other row stays at the top (a parent that's gone or elsewhere doesn't hide it).
     */
    private fun nested(rows: List<CollectionRow>): List<CollectionRow> {
        val byId = rows.associateBy { it.collection.id }
        fun inFolder(row: CollectionRow): Boolean {
            val parent = row.collection.parentId?.let(byId::get) ?: return false
            return parent.collection.parentId == null && parent.collection.mediaTypeId == row.collection.mediaTypeId
        }
        val children = rows.filter(::inFolder).filter(::shown).groupBy { it.collection.parentId }
        return rows.filterNot(::inFolder).map { row -> children[row.collection.id]?.let { row.copy(children = it) } ?: row }
    }

    private fun shown(row: CollectionRow) = !row.isEmpty || row.collection.userCreated

    override val shelves: Flow<List<Shelf>> = trigger().map { items ->
        val (types, collections, placements) = settled(filables(items))
        val rows = nested(rows(collections, placements, items)).filter(::shown)
        types.mapNotNull { type ->
            val inType = rows.filter { it.collection.mediaTypeId == type.id }
            if (inType.any { !it.isEmpty }) Shelf(type, inType) else null
        }
    }.distinctUntilChanged()

    override fun collection(id: String): Flow<CollectionRow?> = trigger().map { items ->
        val (_, collections, placements) = settled(filables(items))
        // The collection and, if it's a folder, its children.
        val wanted = collections.filter { it.id == id || it.parentId == id }
        nested(rows(wanted, placements, items)).firstOrNull { it.collection.id == id }
    }.distinctUntilChanged()

    override suspend fun movePack(pack: Pack, toCollectionId: String) {
        dao.upsertPlacement(Placement(Filing.packKey(pack), toCollectionId, userSet = true))
    }

    override suspend fun moveBook(book: Book, toCollectionId: String) {
        dao.upsertPlacement(Placement(Filing.bookKey(book), toCollectionId, userSet = true))
    }

    override suspend fun moveAlbum(album: AlbumSummary, toCollectionId: String) {
        dao.upsertPlacement(Placement(Filing.albumKey(album), toCollectionId, userSet = true))
    }

    override suspend fun moveAudiobook(book: Audiobook, toCollectionId: String) {
        dao.upsertPlacement(Placement(Filing.audiobookKey(book), toCollectionId, userSet = true))
    }

    override suspend fun moveCollection(collectionId: String, toMediaTypeId: String) {
        db.withTransaction {
            dao.collection(collectionId)?.let { dao.upsertCollection(it.copy(mediaTypeId = toMediaTypeId, parentId = null)) }
        }
    }

    override suspend fun createMediaType(name: String): String {
        val id = newId()
        db.withTransaction {
            val next = (dao.getMediaTypes().maxOfOrNull { it.sortOrder } ?: -1) + 1
            dao.upsertMediaType(MediaType(id, name.trim(), builtIn = false, sortOrder = next))
        }
        return id
    }

    override suspend fun deleteMediaType(id: String): Boolean = db.withTransaction {
        val type = dao.getMediaTypes().firstOrNull { it.id == id }
        if (type == null || type.builtIn) return@withTransaction false
        val inType = dao.getCollections().filter { it.mediaTypeId == id }
        if (inType.any { dao.placementsIn(it.id).isNotEmpty() }) return@withTransaction false
        inType.forEach { dao.deleteCollection(it.id) }
        dao.deleteMediaType(id)
        true
    }

    override suspend fun createCollection(name: String, mediaTypeId: String): String {
        val id = newId()
        dao.upsertCollection(ShelfCollection(id, name.trim(), mediaTypeId, userCreated = true, defaultKey = null))
        return id
    }

    override suspend fun renameCollection(id: String, name: String) {
        db.withTransaction {
            dao.collection(id)?.let { dao.upsertCollection(it.copy(name = name.trim())) }
        }
    }

    override suspend fun deleteCollection(id: String) {
        db.withTransaction {
            dao.placementsIn(id).forEach { dao.deletePlacement(it.packKey) }
            dao.deleteCollection(id)
        }
    }
}
