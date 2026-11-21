package app.granger.core.downloads

import app.granger.core.catalog.CatalogItem
import java.io.File

// Android's File forms of the shared queue's paths (Plan I4 Task 1): the queue itself takes path strings.

val DownloadRecord.file: File get() = File(filePath)
val DownloadRecord.partFile: File get() = File(partPath)

/** Checks free space on [volumeRoot], then queues [item]. */
suspend fun Downloads.enqueue(item: CatalogItem, volumeRoot: File, wifiOnly: Boolean): EnqueueResult =
    enqueue(item, volumeRoot.absolutePath, wifiOnly)

/** Checks free space on [volumeRoot], then queues [spec], which needn't be in the catalog. */
suspend fun Downloads.enqueue(spec: DownloadSpec, volumeRoot: File, wifiOnly: Boolean): EnqueueResult =
    enqueue(spec, volumeRoot.absolutePath, wifiOnly)

/** [Downloads.enqueueGroup] on a volume given as a [File]. */
suspend fun Downloads.enqueueGroup(
    groupId: String, specs: List<DownloadSpec>, volumeRoot: File, wifiOnly: Boolean, next: Boolean = false,
): EnqueueResult = enqueueGroup(groupId, specs, volumeRoot.absolutePath, wifiOnly, next)
