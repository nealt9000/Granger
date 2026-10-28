package app.granger.research

import app.granger.core.catalog.Catalog
import app.granger.core.catalog.CatalogSource
import app.granger.core.catalog.ModelEntry
import app.granger.core.catalog.ModelRole
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.EnqueueResult
import app.granger.core.downloads.VolumeSource
import app.granger.formatBytes
import app.granger.sources.DownloadPrefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Ask's models as Ask's own empty state shows them (Plan U3a Decision 8): what they weigh, how far their download has
 * got, and whether it stopped. [items] are never empty.
 */
data class LibrarianUi(val items: List<ModelEntry>, val bytes: Long, val bytesDone: Long, val downloading: Boolean, val failed: Boolean)

/** The librarian: Ask's models from the catalog, fetched in place from Ask's empty state. */
class Librarian(
    private val catalog: CatalogSource,
    private val downloads: Downloads,
    private val volumes: VolumeSource,
    private val prefs: DownloadPrefs,
    private val dense: Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Null when this build's catalog offers no answering model (release builds today). */
    val ui: Flow<LibrarianUi?> = combine(catalog.state, downloads.records) { state, records ->
        state.catalog?.let { uiOf(models(it, dense), records) }
    }

    /** Queues what isn't on the phone, retries what stopped; a problem comes back as a sentence, else null. */
    suspend fun get(): String? {
        val items = catalog.state.value.catalog?.let { models(it, dense) }.orEmpty()
        if (items.isEmpty()) return null
        val all = withContext(io) { volumes.list() }
        val volume = all.firstOrNull { it.root.path == prefs.volumePath } ?: all.firstOrNull()
            ?: return "No storage is available right now."
        val records = downloads.records.first()
        for (item in items) {
            val record = records.firstOrNull { it.itemId == item.id && it.hash == item.sha256 }
            if (record?.state == DownloadState.FAILED) {
                downloads.retry(item.id)
                continue
            }
            when (val result = downloads.enqueue(item, volume.root, prefs.wifiOnly)) {
                EnqueueResult.Queued, EnqueueResult.AlreadyActive, EnqueueResult.AlreadyInstalled -> Unit
                is EnqueueResult.NoSpace -> return "Not enough space on ${volume.label}: free up ${formatBytes(result.neededBytes)}."
                is EnqueueResult.OverCap -> return "That would bring your downloads over the ${formatBytes(result.capBytes)} limit."
            }
        }
        return null
    }

    companion object {
        /** Queued or downloading; a failed record counts as active in the ledger, but isn't moving. */
        private val MOVING = setOf(DownloadState.QUEUED, DownloadState.RUNNING)

        /** The answering model, plus the embedder when dense retrieval is on; empty with no answering model. */
        fun models(catalog: Catalog, dense: Boolean): List<ModelEntry> {
            val answerer = catalog.models.firstOrNull { it.role == ModelRole.SMALL } ?: return emptyList()
            val embedder = if (dense) catalog.models.firstOrNull { it.role == ModelRole.EMBEDDER } else null
            return listOfNotNull(answerer, embedder)
        }

        fun uiOf(items: List<ModelEntry>, records: List<DownloadRecord>): LibrarianUi? {
            if (items.isEmpty()) return null
            val mine = items.map { item -> item to records.firstOrNull { it.itemId == item.id && it.hash == item.sha256 } }
            return LibrarianUi(
                items = items,
                bytes = items.sumOf { it.sizeBytes },
                bytesDone = mine.sumOf { (item, record) ->
                    when {
                        record == null -> 0L
                        record.state == DownloadState.INSTALLED -> item.sizeBytes
                        else -> record.bytesDone
                    }
                },
                downloading = mine.any { (_, record) -> record?.state in MOVING },
                failed = mine.any { (_, record) -> record?.state == DownloadState.FAILED } && mine.none { (_, r) -> r?.state in MOVING },
            )
        }
    }
}
