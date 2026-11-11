package app.granger.core.downloads

import app.granger.core.catalog.Catalog
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.SidecarEntry
import app.granger.core.catalog.sidecars
import app.granger.core.catalog.timeIndex
import app.granger.core.catalog.vectors
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * One kind of file built offline for a pack or book and downloaded with it (Linked Library spec §5.1). Search vectors
 * (Plan R1) and the time index (Plan H5b) keep the item ids, record kinds and folders they shipped with, so nothing
 * already on a phone moves; every later kind is an [ItemKind.SIDECAR] under `sidecars/`.
 */
enum class SidecarKind(val id: String, val itemKind: ItemKind, private val suffix: String, private val folder: String, private val ext: String) {
    VECTORS("gvec", ItemKind.VECTORS, ".vectors", "vectors", "gvec"),
    TIME_INDEX("gtime", ItemKind.TIME_INDEX, ".timeindex", "timeindex", "gtime"),
    QID_INDEX("gqid", ItemKind.SIDECAR, ".gqid", "sidecars", "gqid"),
    LINKS("glink", ItemKind.SIDECAR, ".glink", "sidecars", "glink"),
    ;

    /** The download item of [parentId]'s file of this kind. */
    fun itemIdOf(parentId: String) = parentId + suffix

    /** The pack or book a download of this kind belongs to; null for any other item. */
    fun parentOf(itemId: String): String? = itemId.removeSuffix(suffix).takeIf { it != itemId }

    /** One file per version (its sha256 in the name), so a new one never overwrites the one in use. */
    fun specOf(item: CatalogItem, file: SidecarFile): DownloadSpec = DownloadSpec(
        itemIdOf(item.id), itemKind, item.title, listOf(file.url), file.sizeBytes, file.sha256,
        HashAlgorithm.SHA256, "$folder/${item.id}_${file.sha256.take(12)}.$ext", downloadSpecOf(item).snapshotDate,
    )

    /** What the catalog names as [item]'s file of this kind; null when it has none. */
    fun fileOf(item: CatalogItem): SidecarFile? = when (this) {
        VECTORS -> item.vectors?.let { SidecarFile(it.url, it.sha256, it.sizeBytes) }
        TIME_INDEX -> item.timeIndex?.let { SidecarFile(it.url, it.sha256, it.sizeBytes) }
        else -> item.sidecars.firstOrNull { it.kind == id }?.let(SidecarFile::of)
    }

    companion object {
        /** The kind of a download item; null for an item that isn't a sidecar. */
        fun ofItem(itemId: String): SidecarKind? = entries.firstOrNull { it.parentOf(itemId) != null }

        /** The pack or book any sidecar download belongs to; null for any other item. */
        fun parentOf(itemId: String): String? = entries.firstNotNullOfOrNull { it.parentOf(itemId) }
    }
}

/** The file a catalog entry names for one sidecar. */
data class SidecarFile(val url: String, val sha256: String, val sizeBytes: Long) {
    companion object {
        fun of(e: SidecarEntry) = SidecarFile(e.url, e.sha256, e.sizeBytes)
    }
}

/**
 * Every pack's and book's sidecars follow it (Linked Library spec §5.1; was VectorDownloads and TimeIndexDownloads).
 * Whatever installed or removed the item (a source screen, onboarding, a preset, the library), [reconcile] makes its
 * sidecars match, kind by kind:
 * - an item installed at the exact file its catalog entry names gets each sidecar that entry lists, queued on the same
 *   volume (an item installed before a sidecar was in the catalog gets it after the next refresh);
 * - a sidecar whose item has no download record left is removed with its file;
 * - once an item's current sidecar of a kind is installed, its older versions are deleted.
 *
 * An item at another snapshot than the catalog's gets none: a sidecar is valid only for the file it was built from.
 * Which phones get vectors is the catalog's business ([Catalog.forBuild]), not this class's.
 */
class Sidecars(private val downloads: Downloads, private val wifiOnly: () -> Boolean) {
    private val mutex = Mutex()

    suspend fun reconcile(catalog: Catalog?, records: List<DownloadRecord>) = mutex.withLock {
        val byItem = records.groupBy { it.itemId }
        for (itemId in records.filter { it.kind.isCompanion }.mapTo(LinkedHashSet()) { it.itemId }) {
            val parent = SidecarKind.parentOf(itemId) ?: continue
            if (byItem[parent].isNullOrEmpty()) downloads.remove(itemId)
        }
        if (catalog == null) return@withLock
        for (item in catalog.packs + catalog.books) {
            val installed = byItem[item.id].orEmpty().firstOrNull { it.state == DownloadState.INSTALLED && it.hash == item.sha256 }
            for (kind in SidecarKind.entries) {
                val file = kind.fileOf(item) ?: continue
                val versions = byItem[kind.itemIdOf(item.id)].orEmpty()
                if (versions.any { it.hash == file.sha256 && it.state == DownloadState.INSTALLED }) {
                    for (old in versions.filter { it.hash != file.sha256 && !it.isActive }) downloads.removeVersion(old.itemId, old.hash)
                }
                if (installed == null || versions.any { it.hash == file.sha256 || it.isActive }) continue
                val relative = downloadSpecOf(item).relativePath
                val root = installed.filePath.removeSuffix(File.separator + relative).takeIf { it != installed.filePath } ?: continue
                // Not enough space is a quiet no: the next change to the records tries again.
                downloads.enqueue(kind.specOf(item, file), File(root), wifiOnly())
            }
        }
    }
}
