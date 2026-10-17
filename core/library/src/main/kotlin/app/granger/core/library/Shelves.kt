package app.granger.core.library

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A media type with the non-empty collections filed under it. */
data class Shelf(val mediaType: MediaType, val collections: List<CollectionRow>)

data class CollectionRow(val collection: ShelfCollection, val packs: List<Pack>)

/** The library's media type, collection and placement structure. */
interface Shelves {
    /** Media types holding at least one pack, in sort order; collections sorted by name, empty app-made ones hidden. */
    val shelves: Flow<List<Shelf>>

    /** One collection with its packs, or null if it doesn't exist. */
    fun collection(id: String): Flow<CollectionRow?>

    /** A manual move; it survives the pack being updated to a new snapshot. */
    suspend fun movePack(pack: Pack, toCollectionId: String)

    suspend fun moveCollection(collectionId: String, toMediaTypeId: String)

    /** Returns the new id. */
    suspend fun createMediaType(name: String): String

    /** False, and nothing changes, if the type is built-in or still holds packs. */
    suspend fun deleteMediaType(id: String): Boolean

    /** Returns the new id. */
    suspend fun createCollection(name: String, mediaTypeId: String): String

    suspend fun renameCollection(id: String, name: String)

    /** Deletes the collection; its packs go back to their default collections, whatever their `userSet`. */
    suspend fun deleteCollection(id: String)
}

class RoomShelves(
    private val db: LibraryDatabase,
    private val library: Library,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : Shelves {
    private val dao = db.shelfDao()

    /** Files unplaced packs, then reads the settled state. Idempotent: a second pass writes nothing. */
    private suspend fun settled(packs: List<Pack>): Triple<List<MediaType>, List<ShelfCollection>, Map<String, String>> =
        db.withTransaction {
            var placements = dao.getPlacements().associate { it.packKey to it.collectionId }
            var collections = dao.getCollections()
            for (pack in packs) {
                val key = Filing.packKey(pack)
                val placed = placements[key]
                if (placed != null && collections.any { it.id == placed }) continue
                val collection = fileInto(pack)
                if (collections.none { it.id == collection.id }) collections = collections + collection
                placements = placements + (key to collection.id)
                dao.upsertPlacement(Placement(key, collection.id, userSet = false))
            }
            Triple(dao.getMediaTypes(), collections, placements)
        }

    private suspend fun fileInto(pack: Pack): ShelfCollection {
        val key = Filing.defaultKey(pack)
        dao.collectionByDefaultKey(key)?.let { return it }
        val created = ShelfCollection(
            id = "c:$key",
            name = Filing.defaultName(key, pack),
            mediaTypeId = Filing.defaultMediaType(pack),
            userCreated = false,
            defaultKey = key,
        )
        dao.upsertCollection(created)
        return created
    }

    private fun trigger(): Flow<List<Pack>> =
        combine(library.packs, dao.observeMediaTypes(), dao.observeCollections(), dao.observePlacements()) { packs, _, _, _ -> packs }

    private fun rows(collections: List<ShelfCollection>, placements: Map<String, String>, packs: List<Pack>) =
        collections.sortedBy { it.name.lowercase() }.map { collection ->
            CollectionRow(collection, packs.filter { placements[Filing.packKey(it)] == collection.id })
        }

    override val shelves: Flow<List<Shelf>> = trigger().map { packs ->
        val (types, collections, placements) = settled(packs)
        val rows = rows(collections, placements, packs).filter { it.packs.isNotEmpty() || it.collection.userCreated }
        types.mapNotNull { type ->
            val inType = rows.filter { it.collection.mediaTypeId == type.id }
            if (inType.any { it.packs.isNotEmpty() }) Shelf(type, inType) else null
        }
    }.distinctUntilChanged()

    override fun collection(id: String): Flow<CollectionRow?> = trigger().map { packs ->
        val (_, collections, placements) = settled(packs)
        collections.firstOrNull { it.id == id }?.let { rows(listOf(it), placements, packs).single() }
    }.distinctUntilChanged()

    override suspend fun movePack(pack: Pack, toCollectionId: String) {
        dao.upsertPlacement(Placement(Filing.packKey(pack), toCollectionId, userSet = true))
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
