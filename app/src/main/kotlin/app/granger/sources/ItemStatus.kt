package app.granger.sources

import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.BookEntry
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.ModelEntry
import app.granger.core.catalog.ModelRole
import app.granger.core.catalog.PackEntry
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.isActive
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus

sealed interface ItemStatus {
    data object NotInstalled : ItemStatus
    /** [lastError] is why the previous attempt stopped, when this is a retry. */
    data class Queued(val wifiOnly: Boolean, val lastError: String? = null) : ItemStatus
    data class Downloading(val fraction: Float) : ItemStatus
    data class Failed(val reason: String) : ItemStatus
    data object Installed : ItemStatus
    data object UpdateAvailable : ItemStatus

    /** A book or album with [done] of its [total] files in and none downloading (cancelled part-way): "Get the rest". */
    data class Partial(val done: Int, val total: Int) : ItemStatus

    /**
     * An archive.org audio item got from the other audio view: one item is one download, so it's on the [shelf]
     * ("Music" or "Audiobooks") it was got for. It offers Open (there), never Get or Remove here.
     */
    data class InLibrary(val shelf: String) : ItemStatus

    /** A model this phone doesn't have the memory to run (spec "Device tiers"). */
    data object NeedsMoreMemory : ItemStatus
}

fun statusOf(item: CatalogItem, records: List<DownloadRecord>, packs: List<Pack>, tier: DeviceTier): ItemStatus {
    val versions = records.filter { it.itemId == item.id }
    versions.firstOrNull { it.isActive }?.let { active ->
        return when (active.state) {
            DownloadState.QUEUED -> ItemStatus.Queued(active.wifiOnly, active.error)
            DownloadState.RUNNING ->
                ItemStatus.Downloading(if (active.sizeBytes > 0) active.bytesDone.toFloat() / active.sizeBytes else 0f)
            else -> ItemStatus.Failed(active.error ?: "Download failed")
        }
    }
    val currentInstalled = when (item) {
        is PackEntry -> versions.any { it.hash == item.sha256 } ||
            packs.any { it.id == item.zimUuid && it.status != PackStatus.MISSING }
        is BookEntry, is ModelEntry -> versions.any { it.hash == item.sha256 }
    }
    return when {
        currentInstalled -> ItemStatus.Installed
        versions.isNotEmpty() -> ItemStatus.UpdateAvailable
        item is ModelEntry && !tier.canRun(item.role) -> ItemStatus.NeedsMoreMemory
        else -> ItemStatus.NotInstalled
    }
}

/** The library pack an entry is installed as: its exact ZIM, or the file an older version was saved to. */
fun installedPackId(entry: PackEntry, records: List<DownloadRecord>, packs: List<Pack>): String? =
    packs.firstOrNull { it.id == entry.zimUuid }?.id
        ?: records.filter { it.itemId == entry.id && it.state == DownloadState.INSTALLED }
            .firstNotNullOfOrNull { record -> packs.firstOrNull { it.filePath == record.filePath }?.id }

fun DeviceTier.canRun(role: ModelRole): Boolean = when (role) {
    ModelRole.SMALL, ModelRole.EMBEDDER -> this >= DeviceTier.ASK
    ModelRole.LARGE -> this >= DeviceTier.THINK_DEEPER
}
