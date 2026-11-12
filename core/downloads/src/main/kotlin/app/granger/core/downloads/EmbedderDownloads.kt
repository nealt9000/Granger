package app.granger.core.downloads

import app.granger.core.catalog.Catalog
import app.granger.core.catalog.ModelRole
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The embedder follows the first search vectors, once (Plan SQ Phase 6): vectors are no use to Search without it, and
 * since Ask rests nothing else fetches it. When the first vectors download is on record and the catalog's embedder has
 * none, the embedder is queued on the vectors' volume and [markFollowed] is called, so removing it later in Models
 * sticks. A phone that already has (or is fetching) the embedder is marked without queueing anything.
 */
class EmbedderDownloads(
    private val downloads: Downloads,
    private val wifiOnly: () -> Boolean,
    private val followed: () -> Boolean,
    private val markFollowed: () -> Unit,
) {
    private val mutex = Mutex()

    suspend fun reconcile(catalog: Catalog?, records: List<DownloadRecord>) = mutex.withLock {
        if (followed() || catalog == null) return@withLock
        val model = catalog.models.firstOrNull { it.role == ModelRole.EMBEDDER } ?: return@withLock
        if (records.any { it.itemId == model.id }) {
            markFollowed()
            return@withLock
        }
        val vectors = records.firstOrNull { it.kind == ItemKind.VECTORS } ?: return@withLock
        // A vectors file is <volume>/vectors/<name>.gvec.
        val root = File(vectors.filePath).parentFile?.parentFile ?: return@withLock
        when (downloads.enqueue(downloadSpecOf(model), root, wifiOnly())) {
            // Not enough space is a quiet no, as for the vectors: the next change to the records tries again.
            is EnqueueResult.NoSpace -> Unit
            else -> markFollowed()
        }
    }
}
