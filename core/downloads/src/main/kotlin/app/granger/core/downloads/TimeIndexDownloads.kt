package app.granger.core.downloads

import app.granger.core.catalog.Catalog
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.TimeIndexEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * A pack's time index follows it (Plan H5b, Task 3), as its search vectors do ([VectorDownloads]). Whatever installed or
 * removed the pack, [reconcile] makes the index match:
 * - a pack installed at the exact file its catalog entry names, with a time index in that entry, gets it queued on the
 *   same volume (a pack installed before its index was in the catalog gets it after the next refresh);
 * - an index whose pack has no download record left is removed with its file;
 * - once a pack's current index is installed, its older versions are deleted.
 *
 * Unlike vectors, every phone gets the index, whatever its size: the Timeline isn't Ask.
 */
class TimeIndexDownloads(private val downloads: Downloads, private val wifiOnly: () -> Boolean) {
    private val mutex = Mutex()

    suspend fun reconcile(catalog: Catalog?, records: List<DownloadRecord>) = mutex.withLock {
        val byItem = records.groupBy { it.itemId }
        for (itemId in records.filter { it.kind == ItemKind.TIME_INDEX }.mapTo(LinkedHashSet()) { it.itemId }) {
            val parent = parentOf(itemId) ?: continue
            if (byItem[parent].isNullOrEmpty()) downloads.remove(itemId)
        }
        if (catalog == null) return@withLock
        for (pack in catalog.packs) {
            val index = pack.timeIndex ?: continue
            val versions = byItem[itemIdOf(pack.id)].orEmpty()
            if (versions.any { it.hash == index.sha256 && it.state == DownloadState.INSTALLED }) {
                for (old in versions.filter { it.hash != index.sha256 && !it.isActive }) downloads.removeVersion(old.itemId, old.hash)
            }
            val installed = byItem[pack.id].orEmpty()
                .firstOrNull { it.state == DownloadState.INSTALLED && it.hash == pack.sha256 } ?: continue
            if (versions.any { it.hash == index.sha256 || it.isActive }) continue
            val relative = downloadSpecOf(pack).relativePath
            val root = installed.filePath.removeSuffix(File.separator + relative).takeIf { it != installed.filePath } ?: continue
            // Not enough space is a quiet no: the next change to the records tries again.
            downloads.enqueue(specOf(pack, index), File(root), wifiOnly())
        }
    }

    companion object {
        private const val SUFFIX = ".timeindex"

        /** The download item of [parentId]'s time index. */
        fun itemIdOf(parentId: String) = parentId + SUFFIX

        /** The pack a time index download belongs to; null for any other item. */
        fun parentOf(itemId: String): String? = itemId.removeSuffix(SUFFIX).takeIf { it != itemId }

        /** One file per index version (its sha256 in the name), so a new one never overwrites the one in use. */
        fun specOf(item: CatalogItem, index: TimeIndexEntry): DownloadSpec = DownloadSpec(
            itemIdOf(item.id), ItemKind.TIME_INDEX, item.title, listOf(index.url), index.sizeBytes, index.sha256,
            HashAlgorithm.SHA256, "timeindex/${item.id}_${index.sha256.take(12)}.gtime", downloadSpecOf(item).snapshotDate,
        )
    }
}
