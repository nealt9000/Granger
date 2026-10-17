package app.granger.core.library

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** A media type with the non-empty collections filed under it. */
data class Shelf(val mediaType: MediaType, val collections: List<CollectionRow>)

data class CollectionRow(val collection: ShelfCollection, val packs: List<Pack>, val books: List<Book> = emptyList()) {
    val isEmpty: Boolean get() = packs.isEmpty() && books.isEmpty()
}

/** The library's media type, collection and placement structure. */
interface Shelves {
    /** Media types holding at least one pack or book, in sort order; collections sorted by name, empty app-made ones hidden. */
    val shelves: Flow<List<Shelf>>

    /** One collection with its packs and books, or null if it doesn't exist. */
    fun collection(id: String): Flow<CollectionRow?>

    /** A manual move; it survives the pack being updated to a new snapshot. */
    suspend fun movePack(pack: Pack, toCollectionId: String)

    /** A manual move; it survives the book being re-downloaded. */
    suspend fun moveBook(book: Book, toCollectionId: String)

    suspend fun moveCollection(collectionId: String, toMediaTypeId: String)

    /** Returns the new id. */
    suspend fun createMediaType(name: String): String

    /** False, and nothing changes, if the type is built-in or still holds packs or books. */
    suspend fun deleteMediaType(id: String): Boolean

    /** Returns the new id. */
    suspend fun createCollection(name: String, mediaTypeId: String): String

    suspend fun renameCollection(id: String, name: String)

    /** Deletes the collection; its packs and books go back to their default collections, whatever their `userSet`. */
    suspend fun deleteCollection(id: String)
}

/**
 * Packs and books share one placement table: a pack's key is [Filing.packKey], a book's is [Filing.bookKey]
 * (`book:...`, which no pack name or id takes), so both are filed, moved and re-filed by the same paths.
 * [bookshelf] null files packs only.
 */
class RoomShelves(
    private val db: LibraryDatabase,
    private val library: Library,
    private val bookshelf: Bookshelf? = null,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : Shelves {
    private val dao = db.shelfDao()

    /** What filing needs to know about a pack or a book. */
    private class Filable(val key: String, val defaultKey: String, val name: () -> String, val mediaTypeId: String)

    private fun filables(packs: List<Pack>, books: List<Book>): List<Filable> =
        packs.map { pack ->
            val key = Filing.defaultKey(pack)
            Filable(Filing.packKey(pack), key, { Filing.defaultName(key, pack) }, Filing.defaultMediaType(pack))
        } + books.map { book ->
            val key = Filing.defaultKey(book)
            Filable(Filing.bookKey(book), key, { Filing.defaultName(key, book) }, Filing.defaultMediaType(book))
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

    private fun trigger(): Flow<Pair<List<Pack>, List<Book>>> =
        combine(
            library.packs, bookshelf?.books ?: flowOf(emptyList()),
            dao.observeMediaTypes(), dao.observeCollections(), dao.observePlacements(),
        ) { packs, books, _, _, _ -> packs to books }

    private fun rows(collections: List<ShelfCollection>, placements: Map<String, String>, packs: List<Pack>, books: List<Book>) =
        collections.sortedBy { it.name.lowercase() }.map { collection ->
            CollectionRow(
                collection,
                packs.filter { placements[Filing.packKey(it)] == collection.id },
                books.filter { placements[Filing.bookKey(it)] == collection.id },
            )
        }

    override val shelves: Flow<List<Shelf>> = trigger().map { (packs, books) ->
        val (types, collections, placements) = settled(filables(packs, books))
        val rows = rows(collections, placements, packs, books).filter { !it.isEmpty || it.collection.userCreated }
        types.mapNotNull { type ->
            val inType = rows.filter { it.collection.mediaTypeId == type.id }
            if (inType.any { !it.isEmpty }) Shelf(type, inType) else null
        }
    }.distinctUntilChanged()

    override fun collection(id: String): Flow<CollectionRow?> = trigger().map { (packs, books) ->
        val (_, collections, placements) = settled(filables(packs, books))
        collections.firstOrNull { it.id == id }?.let { rows(listOf(it), placements, packs, books).single() }
    }.distinctUntilChanged()

    override suspend fun movePack(pack: Pack, toCollectionId: String) {
        dao.upsertPlacement(Placement(Filing.packKey(pack), toCollectionId, userSet = true))
    }

    override suspend fun moveBook(book: Book, toCollectionId: String) {
        dao.upsertPlacement(Placement(Filing.bookKey(book), toCollectionId, userSet = true))
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
