package app.granger

import app.granger.core.catalog.Catalog
import app.granger.core.catalog.BookEntry
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.CatalogSource
import app.granger.core.catalog.CatalogState
import app.granger.core.catalog.ModelEntry
import app.granger.core.catalog.ModelRole
import app.granger.core.catalog.PackEntry
import app.granger.core.catalog.Preset
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadSpec
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.EnqueueResult
import app.granger.core.downloads.HashAlgorithm
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.Volume
import app.granger.core.downloads.VolumeSource
import app.granger.core.downloads.isActive
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
    license: String = "CC BY-SA 4.0",
    nonCommercial: Boolean = false,
    featured: Boolean = false,
    zimName: String = "n-$id",
) = PackEntry(
    id = id, title = "Pack $id", description = description, category = category, language = "eng", sizeBytes = size, sha256 = sha256,
    urls = urls, snapshotDate = "2026-10-10", license = license,
    hasFulltextIndex = true, zimUuid = "u-$id", zimName = zimName, nonCommercial = nonCommercial, featured = featured,
)

fun modelEntry(id: String, role: ModelRole = ModelRole.SMALL, size: Long = 2_000, sha256: String = SHA_B) = ModelEntry(
    id = id, title = "Model $id", role = role, fileName = "$id.litertlm", sizeBytes = size, minRamGb = 6,
    sha256 = sha256, url = "https://hf.test/$id", license = "Apache-2.0", evalPassed = true,
)

fun bookEntry(id: String, category: String = "Novels", sha256: String = SHA_A, author: String = "An Author") = BookEntry(
    id = id, title = "Book $id", author = author, category = category, language = "eng", sizeBytes = 500, sha256 = sha256,
    urls = listOf("https://a.test/$id.epub"), releaseDate = "2026-10-10", license = "Public domain", collection = "classics",
)

fun catalogOf(
    packs: List<PackEntry> = emptyList(),
    models: List<ModelEntry> = emptyList(),
    presets: List<Preset> = emptyList(),
    books: List<BookEntry> = emptyList(),
) = Catalog(1, "2026-10-16T00:00:00Z", packs, models, books = books, presets = presets)

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
    title: String = "Title $itemId",
    hashAlgorithm: HashAlgorithm = HashAlgorithm.SHA256,
) = DownloadRecord(
    itemId = itemId, hash = sha256, hashAlgorithm = hashAlgorithm, kind = kind, title = title, urls = listOf("https://a.test/$itemId"),
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
    /** Runs on each refresh, e.g. to set the state a failed fetch leaves. */
    var onRefresh: () -> Unit = {}
    override suspend fun load() { loads++ }
    override suspend fun refresh() { refreshes++; onRefresh() }
}

class FakeDownloads : Downloads {
    val list = MutableStateFlow<List<DownloadRecord>>(emptyList())
    override val records = list
    var nextResult: EnqueueResult = EnqueueResult.Queued
    val enqueued = mutableListOf<Pair<String, File>>()
    val enqueuedSpecs = mutableListOf<DownloadSpec>()
    val calls = mutableListOf<String>()

    /** Runs as a spec is enqueued, before the result is decided. */
    var onEnqueue: (DownloadSpec) -> Unit = {}

    override suspend fun enqueue(spec: DownloadSpec, volumeRoot: File, wifiOnly: Boolean): EnqueueResult {
        onEnqueue(spec)
        enqueuedSpecs += spec
        enqueued += spec.itemId to volumeRoot
        val result = nextResult
        if (result == EnqueueResult.Queued) {
            list.update {
                it + record(
                    spec.itemId, DownloadState.QUEUED, sha256 = spec.hash, kind = spec.kind,
                    filePath = File(volumeRoot, spec.relativePath).path, sizeBytes = spec.sizeBytes, wifiOnly = wifiOnly,
                    title = spec.title, hashAlgorithm = spec.hashAlgorithm,
                )
            }
        }
        return result
    }

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
    override suspend fun removeVersion(itemId: String, hash: String) { calls += "removeVersion:$itemId:$hash" }
    override suspend fun repair(itemId: String) { calls += "repair:$itemId" }
    override suspend fun recordCheck(itemId: String, hash: String, verified: Boolean) { calls += "check:$itemId:$verified" }
    override suspend fun setWifiOnly(wifiOnly: Boolean) { calls += "wifi:$wifiOnly" }
    override suspend fun resume() { calls += "resume" }

    /** Each enqueueGroup call: the group id, its specs in order, and the volume. */
    val enqueuedGroups = mutableListOf<Triple<String, List<DownloadSpec>, File>>()

    override suspend fun enqueueGroup(groupId: String, specs: List<DownloadSpec>, volumeRoot: File, wifiOnly: Boolean): EnqueueResult {
        specs.forEach(onEnqueue)
        enqueuedGroups += Triple(groupId, specs, volumeRoot)
        calls += "enqueueGroup:$groupId"
        val result = nextResult
        if (result == EnqueueResult.Queued) {
            list.update {
                it + specs.map { spec ->
                    record(
                        spec.itemId, DownloadState.QUEUED, sha256 = spec.hash, kind = spec.kind,
                        filePath = File(volumeRoot, spec.relativePath).path, sizeBytes = spec.sizeBytes, wifiOnly = wifiOnly,
                        title = spec.title, hashAlgorithm = spec.hashAlgorithm,
                    ).copy(groupId = groupId)
                }
            }
        }
        return result
    }

    /** As the real one: the group's unfinished records go, the installed ones stay. */
    override suspend fun cancelGroup(groupId: String) {
        calls += "cancelGroup:$groupId"
        list.update { all -> all.filterNot { it.groupId == groupId && it.isActive } }
    }

    /** As the real one: every record of the group goes. */
    override suspend fun removeGroup(groupId: String) {
        calls += "removeGroup:$groupId"
        list.update { all -> all.filterNot { it.groupId == groupId } }
    }
    override suspend fun retryGroup(groupId: String) { calls += "retryGroup:$groupId" }
}

class FakeVolumes(var volumes: List<Volume>) : VolumeSource {
    override fun list() = volumes
}

class FakePrefs(
    override var wifiOnly: Boolean = true,
    override var volumePath: String? = null,
    override var askBeforeLarge: Boolean = true,
) : DownloadPrefs
