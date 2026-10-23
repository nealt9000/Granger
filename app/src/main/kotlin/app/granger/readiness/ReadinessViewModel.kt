package app.granger.readiness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.Catalog
import app.granger.core.catalog.CatalogSource
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.Integrity
import app.granger.core.downloads.IntegrityChecker
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.VectorDownloads
import app.granger.core.downloads.Volume
import app.granger.core.downloads.VolumeSource
import app.granger.core.library.Book
import app.granger.core.library.Bookshelf
import app.granger.core.library.Library
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.sources.VolumeRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate

enum class CheckStatus { VERIFIED, SIZE_OK, INCOMPLETE, CORRUPTED, MISSING, DAMAGED, NOT_FROM_CATALOG }

data class CheckRow(
    val key: String,
    val title: String,
    val isModel: Boolean,
    val snapshotDate: String,
    val sizeBytes: Long,
    val status: CheckStatus,
    val verifiedAt: Long?,
    /** 0..1 while a full check runs. */
    val progress: Float?,
    val canVerify: Boolean,
    val canRepair: Boolean,
    /** The storage row it's listed under (Plan H4 walkthrough). */
    val source: Source,
)

data class ReadinessUi(
    val loading: Boolean = true,
    val tier: DeviceTier = DeviceTier.LIBRARY_ONLY,
    val totalRamBytes: Long = 0,
    val volumes: List<VolumeRow> = emptyList(),
    val models: List<CheckRow> = emptyList(),
    /** Downloaded read-aloud voices (Plan V1), checked against their manifests. */
    val voices: List<CheckRow> = emptyList(),
    val packs: List<CheckRow> = emptyList(),
    /** One row per downloaded book or album (its files' sizes summed). */
    val media: List<CheckRow> = emptyList(),
    /** What the phone holds per knowledge domain, the signals and storage by source (Plan H1). */
    val report: ReadinessReport = ReadinessReport.EMPTY,
)

class ReadinessViewModel(
    private val downloads: Downloads,
    private val library: Library,
    private val bookshelf: Bookshelf,
    private val catalog: CatalogSource,
    private val volumes: VolumeSource,
    private val tier: DeviceTier,
    private val totalRamBytes: Long,
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val quick: (DownloadRecord) -> Integrity = IntegrityChecker::quick,
    private val full: suspend (DownloadRecord, (Float) -> Unit) -> Integrity = { record, progress ->
        IntegrityChecker.full(record, io, progress)
    },
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    private val volumeList = MutableStateFlow<List<Volume>>(emptyList())
    private val progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val results = MutableStateFlow<Map<String, Integrity>>(emptyMap())
    private val checks = mutableMapOf<String, Job>()

    private data class Checks(val vols: List<Volume>, val prog: Map<String, Float>, val res: Map<String, Integrity>)
    private data class Sources(val catalog: Catalog?, val books: List<Book>)

    private val checkState = combine(volumeList, progress, results, ::Checks)
    private val sources = combine(catalog.state, bookshelf.books) { state, books -> Sources(state.catalog, books) }

    val ui: StateFlow<ReadinessUi> =
        combine(downloads.records, library.packs, sources, checkState) { records, packs, src, chk ->
            // The quick check reads file sizes.
            withContext(io) { build(records, packs, src, chk) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadinessUi(tier = tier, totalRamBytes = totalRamBytes))

    init {
        // The cached catalog, never the network: the coverage rows read it (spec §8).
        viewModelScope.launch { catalog.load() }
        refreshVolumes()
    }

    fun refreshVolumes() {
        viewModelScope.launch { volumeList.value = withContext(io) { volumes.list() } }
    }

    /** Re-hashes the whole file. Runs while the screen is open; leaving it stops the check. */
    /** Re-hashes the whole file (every file of a media item, one after another). Runs while the screen is open; leaving it stops the check. */
    fun verify(key: String) {
        if (checks[key]?.isActive == true) return
        checks[key] = viewModelScope.launch {
            val files = filesOf(key)
            if (files.isEmpty()) return@launch
            progress.update { it + (key to 0f) }
            try {
                var shown = 0f
                for ((i, record) in files.withIndex()) {
                    val result = try {
                        full(record) { p ->
                            val overall = (i + p) / files.size
                            if (overall - shown >= 0.01f || overall >= 1f) {
                                shown = overall
                                progress.update { it + (key to overall) }
                            }
                        }
                    } catch (e: IOException) {
                        // The file vanished or the card was pulled mid-check.
                        withContext(io) { quick(record) }
                    }
                    results.update { it + (keyOf(record) to result) }
                    downloads.recordCheck(record.itemId, record.hash, result == Integrity.VERIFIED)
                }
            } finally {
                progress.update { it - key }
            }
        }
    }

    /** Downloads the file again; for a media item, only its damaged files. */
    fun repair(key: String) {
        appScope.launch {
            val files = filesOf(key)
            // A media item, or an item with its search vectors: only the damaged files.
            val damaged = if (files.size > 1) {
                val res = results.value
                withContext(io) {
                    files.filter { r -> res[keyOf(r)] == Integrity.CORRUPTED || quick(r).let { it == Integrity.MISSING || it == Integrity.INCOMPLETE } }
                }
            } else {
                files
            }
            for (record in damaged) {
                results.update { it - keyOf(record) }
                downloads.repair(record.itemId)
            }
        }
    }

    /** The installed files a row stands for: one with its search vectors (Plan R1), or every file of a media group. */
    private suspend fun filesOf(key: String): List<DownloadRecord> {
        val records = downloads.records.first()
        return if (key.startsWith(GROUP)) {
            val group = key.removePrefix(GROUP)
            records.filter { it.groupId == group && it.state == DownloadState.INSTALLED }
        } else {
            val record = records.firstOrNull { keyOf(it) == key } ?: return emptyList()
            listOf(record) + vectorsOf(record, records.filter { it.state == DownloadState.INSTALLED })
        }
    }

    /** [record]'s installed search vectors, when it is a pack or a book. */
    private fun vectorsOf(record: DownloadRecord, installed: List<DownloadRecord>): List<DownloadRecord> =
        if (record.kind != ItemKind.PACK && record.kind != ItemKind.BOOK) emptyList()
        else installed.filter { it.kind == ItemKind.VECTORS && VectorDownloads.parentOf(it.itemId) == record.itemId }

    private fun build(records: List<DownloadRecord>, packs: List<Pack>, src: Sources, chk: Checks): ReadinessUi {
        val (vols, prog, res) = chk
        val all = records.filter { it.state == DownloadState.INSTALLED }
        // Search vectors (Plan R1) are part of their item's row, not rows of their own.
        val (grouped, installed) = all.filter { it.kind != ItemKind.VECTORS }.partition { it.kind == ItemKind.AUDIO && it.groupId != null }
        val media = grouped.groupBy { it.groupId!! }.map { (group, files) -> groupRow(group, files, prog, res) }
        val packByPath = packs.associateBy { it.filePath }
        val voiceKeys = installed.filter { it.kind == ItemKind.VOICE }.mapTo(HashSet()) { keyOf(it) }
        val rows = installed.map { record ->
            val key = keyOf(record)
            val pack = packByPath[record.filePath]
            val live = quick(record)
            val own = when {
                // A file that has since gone or shrunk beats any earlier full check.
                live == Integrity.MISSING || live == Integrity.INCOMPLETE -> live.toStatus()
                res[key] == Integrity.CORRUPTED -> CheckStatus.CORRUPTED
                pack?.status == PackStatus.CORRUPT -> CheckStatus.DAMAGED
                else -> (res[key] ?: live).toStatus()
            }
            // Its search vectors count in its size and its status: the worst file decides, a damaged pack first.
            val vectors = vectorsOf(record, all)
            val statuses = listOf(own) + vectors.map { v ->
                val q = quick(v)
                if (q == Integrity.MISSING || q == Integrity.INCOMPLETE) q.toStatus() else (res[keyOf(v)] ?: q).toStatus()
            }
            val status = if (own == CheckStatus.DAMAGED) own else WORST_FIRST.firstOrNull { it in statuses } ?: own
            val files = listOf(record) + vectors
            CheckRow(
                key = key,
                title = pack?.title ?: record.title,
                isModel = record.kind == ItemKind.MODEL,
                snapshotDate = record.snapshotDate,
                sizeBytes = files.sumOf { it.sizeBytes },
                status = status,
                verifiedAt = if (files.all { it.verifiedAt != null }) files.minOf { it.verifiedAt!! } else null,
                progress = prog[key],
                canVerify = status != CheckStatus.MISSING && key !in prog,
                canRepair = status in REPAIRABLE,
                source = ReadinessModel.sourceOf(record),
            )
        }
        val recordedPaths = all.mapTo(HashSet()) { it.filePath }
        val byHand = packs.filter { it.filePath !in recordedPaths }.map { pack ->
            CheckRow(
                key = "pack:${pack.id}",
                title = pack.title,
                isModel = false,
                snapshotDate = pack.date,
                sizeBytes = pack.sizeBytes,
                status = when (pack.status) {
                    PackStatus.OK -> CheckStatus.NOT_FROM_CATALOG
                    PackStatus.MISSING -> CheckStatus.MISSING
                    PackStatus.CORRUPT -> CheckStatus.DAMAGED
                },
                verifiedAt = null,
                progress = null,
                canVerify = false,
                canRepair = false,
                source = ReadinessModel.sourceOf(pack, src.catalog),
            )
        }
        return ReadinessUi(
            loading = false,
            tier = tier,
            totalRamBytes = totalRamBytes,
            volumes = vols.map { VolumeRow(it.root.path, it.label, it.removable, it.freeBytes, it.totalBytes, it.grangerBytes, 0) },
            models = rows.filter { it.isModel },
            voices = rows.filter { it.key in voiceKeys },
            packs = rows.filterNot { it.isModel || it.key in voiceKeys } + byHand,
            media = media.sortedBy { it.title.lowercase() },
            report = ReadinessModel.report(src.catalog, packs, src.books, records, today()),
        )
    }

    /** A media item's row: its worst file decides (a corrupted file, then a missing one, then one of the wrong size). */
    private fun groupRow(group: String, files: List<DownloadRecord>, prog: Map<String, Float>, res: Map<String, Integrity>): CheckRow {
        val key = GROUP + group
        val statuses = files.map { record ->
            val live = quick(record)
            when {
                live == Integrity.MISSING || live == Integrity.INCOMPLETE -> live.toStatus()
                else -> (res[keyOf(record)] ?: live).toStatus()
            }
        }
        val status = WORST_FIRST.firstOrNull { it in statuses } ?: CheckStatus.VERIFIED
        return CheckRow(
            key = key,
            title = files.first().title,
            isModel = false,
            snapshotDate = "",
            sizeBytes = files.sumOf { it.sizeBytes },
            status = status,
            verifiedAt = if (files.all { it.verifiedAt != null }) files.minOf { it.verifiedAt!! } else null,
            progress = prog[key],
            canVerify = statuses.any { it != CheckStatus.MISSING } && key !in prog,
            canRepair = status in REPAIRABLE,
            source = ReadinessModel.sourceOf(files.first()),
        )
    }

    private fun Integrity.toStatus(): CheckStatus = when (this) {
        Integrity.VERIFIED -> CheckStatus.VERIFIED
        Integrity.SIZE_OK -> CheckStatus.SIZE_OK
        Integrity.INCOMPLETE -> CheckStatus.INCOMPLETE
        Integrity.CORRUPTED -> CheckStatus.CORRUPTED
        Integrity.MISSING -> CheckStatus.MISSING
    }

    private companion object {
        val REPAIRABLE = setOf(CheckStatus.INCOMPLETE, CheckStatus.CORRUPTED, CheckStatus.DAMAGED, CheckStatus.MISSING)

        fun keyOf(record: DownloadRecord) = "${record.itemId}@${record.hash}"

        /** A media group's row key: `group:<groupId>`. */
        const val GROUP = "group:"

        val WORST_FIRST = listOf(CheckStatus.CORRUPTED, CheckStatus.MISSING, CheckStatus.INCOMPLETE, CheckStatus.SIZE_OK, CheckStatus.VERIFIED)
    }
}
