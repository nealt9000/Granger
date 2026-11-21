package app.granger.core.library

import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimOpenResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * [Library] backed by Room, on both phones (Plan I4 Task 3; was Android's RoomLibrary, which now only adapts its `File`s).
 * Packs are the `*.zim` files in [packDirs] (Android: one `packs` folder per external-files volume; the iPhone:
 * `Documents/Packs`); [refresh] reconciles the table with what is on disk. [opener] opens a pack file by its path
 * (java-libkiwix on Android, CoreKiwix on the iPhone).
 */
open class PackLibrary(
    private val db: LibraryDatabase,
    private val opener: (path: String) -> ZimOpenResult,
    private val packDirs: () -> List<String>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long,
    /**
     * Runs after a pack is removed or superseded, with its row: its pack documents (Plan P2) go too, unless a pack of
     * the same name continues them (Plan KB2 Task 2.4).
     */
    private val onRemoved: suspend (pack: Pack) -> Unit = {},
) : Library {
    private val packDao = db.packDao()
    private val bookmarkDao = db.bookmarkDao()
    private val positionDao = db.readingPositionDao()
    private val sessionDao = db.sessionDao()
    private val refreshMutex = Mutex()
    private val openArchives = HashMap<String, ZimArchive>()
    private val archivesLock = TryLock()

    private inline fun <T> archives(block: HashMap<String, ZimArchive>.() -> T): T {
        archivesLock.lock()
        try { return openArchives.block() } finally { archivesLock.unlock() }
    }

    override val packs: Flow<List<Pack>> = packDao.observeAll()
    override val bookmarks: Flow<List<Bookmark>> = bookmarkDao.observeAll()

    override suspend fun refresh() = refreshMutex.withLock {
        withContext(io) {
            val known = packDao.getAll()
            val knownIds = known.mapTo(HashSet()) { it.id }
            val files = packDirs()
                .flatMap { dir -> runCatching { SystemFileSystem.list(Path(dir)) }.getOrDefault(emptyList()) }
                .filter { it.name.endsWith(".zim", ignoreCase = true) && SystemFileSystem.metadataOrNull(it)?.isRegularFile == true }
                .map { it.toString() }
                .sortedBy { it }
            val seenPaths = HashSet<String>()
            val seenIds = HashSet<String>()
            val added = ArrayList<Pack>()
            for (path in files) {
                seenPaths += path
                val previous = known.firstOrNull { it.filePath == path }
                val pack = when (val result = opener(path)) {
                    is ZimOpenResult.Opened -> result.archive.use { archive ->
                        // A pack moved to another folder or volume keeps its addedAt.
                        val sameZim = previous ?: known.firstOrNull { it.id == archive.uuid }
                        // The file doesn't say whether it has a book manifest (Plan KB2): the same ZIM keeps its own.
                        Pack.from(archive, path, sizeOf(path), sameZim?.addedAt ?: now())
                            .copy(bookManifest = sameZim?.takeIf { it.id == archive.uuid }?.bookManifest)
                    }
                    is ZimOpenResult.Failed ->
                        previous?.copy(status = PackStatus.CORRUPT, sizeBytes = sizeOf(path))
                            ?: Pack.unreadable(path, sizeOf(path), now())
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

    override suspend fun fileReplaced(path: String) = refreshMutex.withLock {
        withContext(io) {
            val absolute = path
            // A repair keeps the path and the ZIM's uuid, so refresh() alone would keep the old file handle.
            for (pack in packDao.getAll()) if (pack.filePath == absolute) closeArchive(pack.id)
        }
    }

    private suspend fun inherit(old: Pack, new: Pack) {
        db.inTransaction {
            bookmarkDao.moveToPack(old.id, new.id)
            bookmarkDao.deleteForPack(old.id)
            positionDao.moveToPack(old.id, new.id)
            positionDao.deleteForPack(old.id)
            sessionDao.moveTabsToPack(old.id, new.id)
            sessionDao.moveHistoryToPack(old.id, new.id)
            sessionDao.deleteHistoryForPack(old.id)
            packDao.delete(old.id)
            packDao.upsert(new.copy(addedAt = old.addedAt))
        }
        closeArchive(old.id)
    }

    override suspend fun remove(packId: String): Boolean = refreshMutex.withLock {
        withContext(io) {
            val pack = packDao.get(packId) ?: return@withContext true
            closeArchive(packId)
            if (!deleteFile(pack.filePath)) return@withContext false
            db.inTransaction {
                bookmarkDao.deleteForPack(packId)
                positionDao.deleteForPack(packId)
                sessionDao.deleteHistoryForPack(packId)
                sessionDao.deleteTabsForPack(packId)
                packDao.delete(packId)
            }
            onRemoved(pack)
            true
        }
    }

    override suspend fun supersede(oldPackId: String, newPackId: String, acrossNames: Boolean): Boolean = refreshMutex.withLock {
        withContext(io) {
            if (oldPackId == newPackId) return@withContext false
            val old = packDao.get(oldPackId) ?: return@withContext false
            val new = packDao.get(newPackId)?.takeIf { it.status == PackStatus.OK } ?: return@withContext false
            if (!acrossNames && (old.name.isBlank() || old.name != new.name)) return@withContext false
            // Across a set, the marks whose article the new pack lacks become history before anything is deleted.
            val missing = if (acrossNames) {
                val archive = archive(newPackId) ?: return@withContext false
                val bookmarks = bookmarkDao.forPack(oldPackId).filter { archive.resolvePath(it.path) == null }
                val places = positionDao.forPack(oldPackId).filter { archive.resolvePath(it.path) == null }
                bookmarks to places
            } else null
            closeArchive(oldPackId)
            if (!deleteFile(old.filePath)) return@withContext false
            if (missing != null) {
                val (bookmarks, places) = missing
                db.inTransaction {
                    for (b in bookmarks) {
                        if (!sessionDao.hasHistory(oldPackId, b.path)) sessionDao.upsertHistory(HistoryEntry(oldPackId, b.path, b.title, b.createdAt))
                        bookmarkDao.delete(oldPackId, b.path)
                    }
                    for (p in places) {
                        if (!sessionDao.hasHistory(oldPackId, p.path)) {
                            sessionDao.upsertHistory(HistoryEntry(oldPackId, p.path, p.path.substringAfterLast('/').replace('_', ' '), p.updatedAt))
                        }
                        positionDao.delete(oldPackId, p.path)
                    }
                }
            }
            inherit(old, new)
            onRemoved(old)
            true
        }
    }

    override fun archive(packId: String): ZimArchive? {
        archives { get(packId) }?.let { return it }
        val pack = packRow(packId)?.takeIf { it.status == PackStatus.OK } ?: return null
        val opened = (opener(pack.filePath) as? ZimOpenResult.Opened)?.archive ?: return null
        if (opened.uuid != packId) {
            // The file was replaced (e.g. a new snapshot) since the last refresh; it isn't this pack.
            opened.close()
            return null
        }
        val existing = archives { get(packId) ?: run { put(packId, opened); null } }
        if (existing != null) opened.close() // another thread won the race
        return existing ?: opened
    }

    override fun cachedArchive(packId: String): ZimArchive? = archives { get(packId) }

    private fun closeArchive(packId: String) {
        archives { remove(packId) }?.close()
    }

    /** A pack's row, read blocking ([archive] is blocking by contract, off the main thread). */
    private fun packRow(packId: String): Pack? = kotlinx.coroutines.runBlocking { packDao.get(packId) }

    private fun sizeOf(path: String): Long = runCatching { SystemFileSystem.metadataOrNull(Path(path))?.size }.getOrNull() ?: 0L

    /** True when [path] is gone afterwards (it wasn't there, or it was deleted). */
    private fun deleteFile(path: String): Boolean =
        runCatching { SystemFileSystem.delete(Path(path), mustExist = false); true }.getOrDefault(false) && !SystemFileSystem.exists(Path(path))

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
