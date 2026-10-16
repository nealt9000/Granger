package app.granger.core.library

import androidx.room.withTransaction
import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimOpenResult
import app.granger.core.zim.ZimOpener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [Library] backed by Room. Packs are the `*.zim` files in [packDirs] (one `packs` folder per
 * external-files volume); [refresh] reconciles the table with what is on disk.
 */
class RoomLibrary(
    private val db: LibraryDatabase,
    private val opener: ZimOpener,
    private val packDirs: () -> List<File>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : Library {
    private val packDao = db.packDao()
    private val bookmarkDao = db.bookmarkDao()
    private val positionDao = db.readingPositionDao()
    private val refreshMutex = Mutex()
    private val openArchives = HashMap<String, ZimArchive>()

    override val packs: Flow<List<Pack>> = packDao.observeAll()
    override val bookmarks: Flow<List<Bookmark>> = bookmarkDao.observeAll()

    override suspend fun refresh() = refreshMutex.withLock {
        withContext(io) {
            val known = packDao.getAll()
            val knownIds = known.mapTo(HashSet()) { it.id }
            val files = packDirs()
                .flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
                .filter { it.isFile && it.name.endsWith(".zim", ignoreCase = true) }
                .sortedBy { it.absolutePath }
            val seenPaths = HashSet<String>()
            val seenIds = HashSet<String>()
            val added = ArrayList<Pack>()
            for (file in files) {
                val path = file.absolutePath
                seenPaths += path
                val previous = known.firstOrNull { it.filePath == path }
                val pack = when (val result = opener.open(file)) {
                    is ZimOpenResult.Opened -> result.archive.use { archive ->
                        // A pack moved to another folder or volume keeps its addedAt.
                        val sameZim = previous ?: known.firstOrNull { it.id == archive.uuid }
                        Pack.from(archive, file, sameZim?.addedAt ?: now())
                    }
                    is ZimOpenResult.Failed ->
                        previous?.copy(status = PackStatus.CORRUPT, sizeBytes = file.length())
                            ?: Pack.unreadable(file, now())
                }
                if (!seenIds.add(pack.id)) continue // the same ZIM in two folders: keep the first
                if (previous != null && previous.id != pack.id) {
                    // A different ZIM now sits at this path (e.g. a newer snapshot).
                    packDao.delete(previous.id)
                    closeArchive(previous.id)
                }
                if (pack != previous) {
                    packDao.upsert(pack)
                    closeArchive(pack.id)
                }
                if (pack.id !in knownIds && pack.status == PackStatus.OK) added += pack
            }
            // A new snapshot whose predecessor is gone from disk takes over its bookmarks and positions.
            val superseded = HashSet<String>()
            for (pack in added) {
                if (pack.name.isBlank()) continue
                val old = known.firstOrNull {
                    it.name == pack.name && it.id !in seenIds && it.id !in superseded
                } ?: continue
                inherit(old, pack)
                superseded += old.id
            }
            for (pack in known) {
                if (pack.id in superseded) continue
                if (pack.id !in seenIds && pack.filePath !in seenPaths && pack.status != PackStatus.MISSING) {
                    packDao.upsert(pack.copy(status = PackStatus.MISSING))
                    closeArchive(pack.id)
                }
            }
        }
    }

    private suspend fun inherit(old: Pack, new: Pack) {
        db.withTransaction {
            bookmarkDao.moveToPack(old.id, new.id)
            bookmarkDao.deleteForPack(old.id)
            positionDao.moveToPack(old.id, new.id)
            positionDao.deleteForPack(old.id)
            packDao.delete(old.id)
            packDao.upsert(new.copy(addedAt = old.addedAt))
        }
        closeArchive(old.id)
    }

    override suspend fun remove(packId: String): Boolean = refreshMutex.withLock {
        withContext(io) {
            val pack = packDao.get(packId) ?: return@withContext true
            closeArchive(packId)
            val file = File(pack.filePath)
            if (file.exists() && !file.delete()) return@withContext false
            db.withTransaction {
                bookmarkDao.deleteForPack(packId)
                positionDao.deleteForPack(packId)
                packDao.delete(packId)
            }
            true
        }
    }

    override fun archive(packId: String): ZimArchive? {
        synchronized(openArchives) { openArchives[packId]?.let { return it } }
        val pack = packDao.get(packId)?.takeIf { it.status == PackStatus.OK } ?: return null
        val opened = (opener.open(File(pack.filePath)) as? ZimOpenResult.Opened)?.archive ?: return null
        if (opened.uuid != packId) {
            // The file was replaced (e.g. a new snapshot) since the last refresh; it isn't this pack.
            opened.close()
            return null
        }
        synchronized(openArchives) {
            openArchives[packId]?.let { existing ->
                opened.close() // another thread won the race
                return existing
            }
            openArchives[packId] = opened
            return opened
        }
    }

    override fun cachedArchive(packId: String): ZimArchive? = synchronized(openArchives) { openArchives[packId] }

    private fun closeArchive(packId: String) {
        synchronized(openArchives) { openArchives.remove(packId) }?.close()
    }

    override suspend fun startPath(packId: String): String? = withContext(io) {
        val archive = archive(packId) ?: return@withContext null
        positionDao.lastRead(packId)?.path?.takeIf { archive.resolvePath(it) != null } ?: archive.mainPath
    }

    override fun isBookmarked(packId: String, path: String): Flow<Boolean> =
        bookmarkDao.observeExists(packId, path)

    override suspend fun setBookmarked(packId: String, path: String, title: String, bookmarked: Boolean) {
        if (bookmarked) {
            bookmarkDao.upsert(Bookmark(packId, path, title, now()))
        } else {
            bookmarkDao.delete(packId, path)
        }
    }

    override suspend fun savePosition(packId: String, path: String, scrollFraction: Float) {
        val fraction = if (scrollFraction.isNaN()) 0f else scrollFraction.coerceIn(0f, 1f)
        positionDao.upsert(ReadingPosition(packId, path, fraction, now()))
    }

    override suspend fun position(packId: String, path: String): Float? =
        positionDao.get(packId, path)?.scrollFraction
}
