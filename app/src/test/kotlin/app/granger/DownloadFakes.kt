package app.granger

import app.granger.core.catalog.Catalog
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.CatalogSource
import app.granger.core.catalog.CatalogState
import app.granger.core.catalog.ModelEntry
import app.granger.core.catalog.ModelRole
import app.granger.core.catalog.PackEntry
import app.granger.core.catalog.Preset
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.EnqueueResult
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.Volume
import app.granger.core.downloads.VolumeSource
import app.granger.sources.DownloadPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

val SHA_A = "a".repeat(64)
val SHA_B = "b".repeat(64)

fun packEntry(
    id: String,
    category: String = "Medical",
    size: Long = 1_000,
    sha256: String = SHA_A,
    description: String = "",
    urls: List<String> = listOf("https://a.test/$id.zim"),
) = PackEntry(
    id = id, title = "Pack $id", description = description, category = category, language = "eng", sizeBytes = size, sha256 = sha256,
    urls = urls, snapshotDate = "2026-10-10", license = "CC BY-SA 4.0",
    hasFulltextIndex = true, zimUuid = "u-$id", zimName = "n-$id",
)

fun modelEntry(id: String, role: ModelRole = ModelRole.SMALL, size: Long = 2_000, sha256: String = SHA_B) = ModelEntry(
    id = id, title = "Model $id", role = role, fileName = "$id.litertlm", sizeBytes = size, minRamGb = 6,
    sha256 = sha256, url = "https://hf.test/$id", license = "Apache-2.0", evalPassed = true,
)

fun catalogOf(packs: List<PackEntry> = emptyList(), models: List<ModelEntry> = emptyList(), presets: List<Preset> = emptyList()) =
    Catalog(1, "2026-10-16T00:00:00Z", packs, models, presets)

fun record(
    itemId: String,
    state: DownloadState,
    sha256: String = SHA_A,
    kind: ItemKind = ItemKind.PACK,
    filePath: String = "/internal/packs/$itemId.zim",
    sizeBytes: Long = 1_000,
    bytesDone: Long = 0,
    error: String? = null,
    wifiOnly: Boolean = true,
    verifiedAt: Long? = null,
) = DownloadRecord(
    itemId = itemId, sha256 = sha256, kind = kind, title = "Title $itemId", urls = listOf("https://a.test/$itemId"),
    sizeBytes = sizeBytes, filePath = filePath, snapshotDate = "2026-10-10", wifiOnly = wifiOnly, state = state,
    bytesDone = bytesDone, error = error, verifiedAt = verifiedAt, createdAt = 1, updatedAt = 1,
)

fun volume(path: String, label: String, free: Long = 50_000_000_000, total: Long = 128_000_000_000, granger: Long = 0) =
    Volume(File(path), label, removable = label == "SD card", freeBytes = free, totalBytes = total, grangerBytes = granger)

class FakeCatalogSource(catalog: Catalog? = null) : CatalogSource {
    val mutable = MutableStateFlow(CatalogState(catalog = catalog))
    override val state: StateFlow<CatalogState> = mutable
    var loads = 0
    var refreshes = 0
    override suspend fun load() { loads++ }
    override suspend fun refresh() { refreshes++ }
}

class FakeDownloads : Downloads {
    val list = MutableStateFlow<List<DownloadRecord>>(emptyList())
    override val records = list
    var nextResult: EnqueueResult = EnqueueResult.Queued
    val enqueued = mutableListOf<Pair<String, File>>()
    val calls = mutableListOf<String>()

    override suspend fun enqueue(item: CatalogItem, volumeRoot: File, wifiOnly: Boolean): EnqueueResult {
        enqueued += item.id to volumeRoot
        val result = nextResult
        if (result == EnqueueResult.Queued) {
            list.update { it + record(item.id, DownloadState.QUEUED, sha256 = item.sha256, sizeBytes = item.sizeBytes, wifiOnly = wifiOnly) }
        }
        return result
    }

    override suspend fun retry(itemId: String) { calls += "retry:$itemId" }
    override suspend fun cancel(itemId: String) { calls += "cancel:$itemId" }
    override suspend fun remove(itemId: String) { calls += "remove:$itemId" }
    override suspend fun repair(itemId: String) { calls += "repair:$itemId" }
    override suspend fun recordCheck(itemId: String, sha256: String, verified: Boolean) { calls += "check:$itemId:$verified" }
    override suspend fun setWifiOnly(wifiOnly: Boolean) { calls += "wifi:$wifiOnly" }
}

class FakeVolumes(var volumes: List<Volume>) : VolumeSource {
    override fun list() = volumes
}

class FakePrefs(override var wifiOnly: Boolean = true, override var volumePath: String? = null) : DownloadPrefs
