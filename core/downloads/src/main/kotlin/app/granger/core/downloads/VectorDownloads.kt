package app.granger.core.downloads

import app.granger.core.catalog.Catalog
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.VectorsEntry
import app.granger.core.catalog.vectors
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * A pack's or book's search vectors follow it (Plan R1 Task 10, spec §4). Whatever installed or removed the item (a
 * source screen, onboarding, a preset, the library), [reconcile] makes the vectors match:
 * - an item installed at the exact file its catalog entry names, with vectors in that entry, gets them queued on the
 *   same volume (an item installed before its vectors were in the catalog gets them after the next refresh);
 * - vectors whose item has no download record left are removed with their files;
 * - once an item's current vectors are installed, its older versions of them are deleted.
 *
 * An item at another snapshot than the catalog's gets none: vectors are valid only for the file they were built from.
 */
class VectorDownloads(private val downloads: Downloads, private val wifiOnly: () -> Boolean) {
    private val mutex = Mutex()

    suspend fun reconcile(catalog: Catalog?, records: List<DownloadRecord>) = mutex.withLock {
        val byItem = records.groupBy { it.itemId }
        for (itemId in records.filter { it.kind == ItemKind.VECTORS }.mapTo(LinkedHashSet()) { it.itemId }) {
            val parent = parentOf(itemId) ?: continue
            if (byItem[parent].isNullOrEmpty()) downloads.remove(itemId)
        }
        if (catalog == null) return@withLock
        for (item in catalog.packs + catalog.books) {
            val vectors = item.vectors ?: continue
            val versions = byItem[itemIdOf(item.id)].orEmpty()
            if (versions.any { it.hash == vectors.sha256 && it.state == DownloadState.INSTALLED }) {
                for (old in versions.filter { it.hash != vectors.sha256 && !it.isActive }) downloads.removeVersion(old.itemId, old.hash)
            }
            val installed = byItem[item.id].orEmpty()
                .firstOrNull { it.state == DownloadState.INSTALLED && it.hash == item.sha256 } ?: continue
            if (byItem[itemIdOf(item.id)].orEmpty().any { it.hash == vectors.sha256 || it.isActive }) continue
            val relative = downloadSpecOf(item).relativePath
            val root = installed.filePath.removeSuffix(File.separator + relative).takeIf { it != installed.filePath } ?: continue
            // Not enough space is a quiet no: the next change to the records tries again.
            downloads.enqueue(specOf(item, vectors), File(root), wifiOnly())
        }
    }

    companion object {
        private const val SUFFIX = ".vectors"

        /** The download item of [parentId]'s vectors. */
        fun itemIdOf(parentId: String) = parentId + SUFFIX

        /** The pack or book a vectors download belongs to; null for any other item. */
        fun parentOf(itemId: String): String? = itemId.removeSuffix(SUFFIX).takeIf { it != itemId }

        /** One file per vectors version (its sha256 in the name), so a new one never overwrites the one in use. */
        fun specOf(item: CatalogItem, vectors: VectorsEntry): DownloadSpec = DownloadSpec(
            itemIdOf(item.id), ItemKind.VECTORS, item.title, listOf(vectors.url), vectors.sizeBytes, vectors.sha256,
            HashAlgorithm.SHA256, "vectors/${item.id}_${vectors.sha256.take(12)}.gvec", downloadSpecOf(item).snapshotDate,
            capped = false,
        )
    }
}
