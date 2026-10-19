package app.granger.core.library

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A media type with the non-empty collections filed under it. */
data class Shelf(val mediaType: MediaType, val collections: List<CollectionRow>)

data class CollectionRow(
    val collection: ShelfCollection,
    val packs: List<Pack>,
    val books: List<Book> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val audiobooks: List<Audiobook> = emptyList(),
) {
    val isEmpty: Boolean get() = packs.isEmpty() && books.isEmpty() && albums.isEmpty() && audiobooks.isEmpty()
}

/** The library's media type, collection and placement structure. */
interface Shelves {
    /** Media types holding at least one pack, book, album or audiobook, in sort order; collections sorted by name, empty app-made ones hidden. */
    val shelves: Flow<List<Shelf>>

    /** One collection with its packs and books, or null if it doesn't exist. */
    fun collection(id: String): Flow<CollectionRow?>

    /** A manual move; it survives the pack being updated to a new snapshot. */
    suspend fun movePack(pack: Pack, toCollectionId: String)

    /** A manual move; it survives the book being re-downloaded. */
    suspend fun moveBook(book: Book, toCollectionId: String)

    /** A manual move; it survives the album's tracks being re-scanned. */
    suspend fun moveAlbum(album: AlbumSummary, toCollectionId: String)

    /** A manual move; it survives the audiobook being re-scanned. */
    suspend fun moveAudiobook(book: Audiobook, toCollectionId: String)

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

    /** What filing needs to know about an item. */
    private class Filable(val key: String, val defaultKey: String, val name: () -> String, val mediaTypeId: String)

    private fun filables(items: Items): List<Filable> {
        val (packs, books, albums, audiobooks, origins) = items
        return packs.map { pack ->
            val key = Filing.defaultKey(pack)
            Filable(Filing.packKey(pack), key, { Filing.defaultName(key, pack) }, Filing.defaultMediaType(pack))
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
            var collections = dao.getCollections()
            for (item in items) {
                val placed = placements[item.key]
                if (placed != null && collections.any { it.id == placed }) continue
                val collection = fileInto(item)
                if (collections.none { it.id == collection.id }) collections = collections + collection
                placements = placements + (item.key to collection.id)
                dao.upsertPlacement(Placement(item.key, collection.id, userSet = false))
            }
            Triple(dao.getMediaTypes(), collections, placements)
        }

    private suspend fun fileInto(item: Filable): ShelfCollection {
        dao.collectionByDefaultKey(item.defaultKey)?.let { return it }
        val created = ShelfCollection(
            id = "c:${item.defaultKey}",
            name = item.name(),
            mediaTypeId = item.mediaTypeId,
            userCreated = false,
            defaultKey = item.defaultKey,
        )
        dao.upsertCollection(created)
        return created
    }

    private fun trigger(): Flow<Items> {
        val items = combine(library.packs, bookshelf.books, media.tracks, media.audiobooks) { packs, books, tracks, audiobooks ->
            val origins = HashMap<String, MediaOrigin>()
            tracks.forEach { origins.putIfAbsent(it.albumKey, it.origin) }
            Items(packs, books, Albums.summaries(tracks), audiobooks, origins)
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

    override val shelves: Flow<List<Shelf>> = trigger().map { items ->
        val (types, collections, placements) = settled(filables(items))
        val rows = rows(collections, placements, items).filter { !it.isEmpty || it.collection.userCreated }
        types.mapNotNull { type ->
            val inType = rows.filter { it.collection.mediaTypeId == type.id }
            if (inType.any { !it.isEmpty }) Shelf(type, inType) else null
        }
    }.distinctUntilChanged()

    override fun collection(id: String): Flow<CollectionRow?> = trigger().map { items ->
        val (_, collections, placements) = settled(filables(items))
        collections.firstOrNull { it.id == id }?.let { rows(listOf(it), placements, items).single() }
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
            dao.collection(collectionId)?.let { dao.upsertCollection(it.copy(mediaTypeId = toMediaTypeId)) }
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
