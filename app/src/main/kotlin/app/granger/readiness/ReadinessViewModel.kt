package app.granger.readiness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.ai.DeviceTier
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.Integrity
import app.granger.core.downloads.IntegrityChecker
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.Volume
import app.granger.core.downloads.VolumeSource
import app.granger.core.library.Library
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.downloads.VolumeRow
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
)

data class ReadinessUi(
    val loading: Boolean = true,
    val tier: DeviceTier = DeviceTier.LIBRARY_ONLY,
    val totalRamBytes: Long = 0,
    val volumes: List<VolumeRow> = emptyList(),
    val models: List<CheckRow> = emptyList(),
    val packs: List<CheckRow> = emptyList(),
)

class ReadinessViewModel(
    private val downloads: Downloads,
    private val library: Library,
    private val volumes: VolumeSource,
    private val tier: DeviceTier,
    private val totalRamBytes: Long,
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val quick: (DownloadRecord) -> Integrity = IntegrityChecker::quick,
    private val full: suspend (DownloadRecord, (Float) -> Unit) -> Integrity = { record, progress ->
        IntegrityChecker.full(record, io, progress)
    },
) : ViewModel() {
    private val volumeList = MutableStateFlow<List<Volume>>(emptyList())
    private val progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val results = MutableStateFlow<Map<String, Integrity>>(emptyMap())
    private val checks = mutableMapOf<String, Job>()

    val ui: StateFlow<ReadinessUi> =
        combine(downloads.records, library.packs, volumeList, progress, results) { records, packs, vols, prog, res ->
            // The quick check reads file sizes.
            withContext(io) { build(records, packs, vols, prog, res) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadinessUi(tier = tier, totalRamBytes = totalRamBytes))

    init {
        refreshVolumes()
    }

    fun refreshVolumes() {
        viewModelScope.launch { volumeList.value = withContext(io) { volumes.list() } }
    }

    /** Re-hashes the whole file. Runs while the screen is open; leaving it stops the check. */
    fun verify(key: String) {
        if (checks[key]?.isActive == true) return
        checks[key] = viewModelScope.launch {
            val record = downloads.records.first().firstOrNull { keyOf(it) == key } ?: return@launch
            progress.update { it + (key to 0f) }
            try {
                var shown = 0f
                val result = try {
                    full(record) { p ->
                        if (p - shown >= 0.01f || p >= 1f) {
                            shown = p
                            progress.update { it + (key to p) }
                        }
                    }
                } catch (e: IOException) {
                    // The file vanished or the card was pulled mid-check.
                    withContext(io) { quick(record) }
                }
                results.update { it + (key to result) }
                downloads.recordCheck(record.itemId, record.sha256, result == Integrity.VERIFIED)
            } finally {
                progress.update { it - key }
            }
        }
    }

    fun repair(key: String) {
        appScope.launch {
            val record = downloads.records.first().firstOrNull { keyOf(it) == key } ?: return@launch
            results.update { it - key }
            downloads.repair(record.itemId)
        }
    }

    private fun build(
        records: List<DownloadRecord>,
        packs: List<Pack>,
        vols: List<Volume>,
        prog: Map<String, Float>,
        res: Map<String, Integrity>,
    ): ReadinessUi {
        val installed = records.filter { it.state == DownloadState.INSTALLED }
        val packByPath = packs.associateBy { it.filePath }
        val rows = installed.map { record ->
            val key = keyOf(record)
            val pack = packByPath[record.filePath]
            val live = quick(record)
            val status = when {
                // A file that has since gone or shrunk beats any earlier full check.
                live == Integrity.MISSING || live == Integrity.INCOMPLETE -> live.toStatus()
                res[key] == Integrity.CORRUPTED -> CheckStatus.CORRUPTED
                pack?.status == PackStatus.CORRUPT -> CheckStatus.DAMAGED
                else -> (res[key] ?: live).toStatus()
            }
            CheckRow(
                key = key,
                title = pack?.title ?: record.title,
                isModel = record.kind == ItemKind.MODEL,
                snapshotDate = record.snapshotDate,
                sizeBytes = record.sizeBytes,
                status = status,
                verifiedAt = record.verifiedAt,
                progress = prog[key],
                canVerify = status != CheckStatus.MISSING && key !in prog,
                canRepair = status in REPAIRABLE,
            )
        }
        val recordedPaths = installed.mapTo(HashSet()) { it.filePath }
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
            )
        }
        return ReadinessUi(
            loading = false,
            tier = tier,
            totalRamBytes = totalRamBytes,
            volumes = vols.map { VolumeRow(it.root.path, it.label, it.removable, it.freeBytes, it.totalBytes, it.grangerBytes, 0) },
            models = rows.filter { it.isModel },
            packs = rows.filterNot { it.isModel } + byHand,
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

        fun keyOf(record: DownloadRecord) = "${record.itemId}@${record.sha256}"
    }
}
