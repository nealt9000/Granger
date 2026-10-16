package app.granger.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.CatalogError
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.CatalogSource
import app.granger.core.catalog.CatalogState
import app.granger.core.catalog.ModelEntry
import app.granger.core.catalog.PackEntry
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.EnqueueResult
import app.granger.core.downloads.Volume
import app.granger.core.downloads.VolumeSource
import app.granger.core.downloads.isActive
import app.granger.core.library.Library
import app.granger.core.library.Pack
import app.granger.formatBytes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class VolumeRow(
    val path: String,
    val label: String,
    val removable: Boolean,
    val freeBytes: Long,
    val totalBytes: Long,
    val grangerBytes: Long,
    /** Bytes still to download onto this volume. */
    val queuedBytes: Long,
)

data class ItemRow(
    val id: String,
    val title: String,
    val details: String,
    val sizeBytes: Long,
    val status: ItemStatus,
    /** The library pack this entry is installed as, if any. */
    val packId: String?,
)

data class Section(val title: String, val rows: List<ItemRow>)

data class PresetRow(val id: String, val title: String, val itemCount: Int, val remainingCount: Int, val remainingBytes: Long)

data class DownloadsUi(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val catalogProblem: String? = null,
    val volumes: List<VolumeRow> = emptyList(),
    val selectedVolume: String? = null,
    val wifiOnly: Boolean = true,
    val presets: List<PresetRow> = emptyList(),
    val sections: List<Section> = emptyList(),
    val notice: String? = null,
)

class DownloadsViewModel(
    private val catalog: CatalogSource,
    private val downloads: Downloads,
    private val library: Library,
    private val volumes: VolumeSource,
    private val prefs: DownloadPrefs,
    private val tier: DeviceTier,
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class Settings(val volumes: List<Volume>, val selectedPath: String?, val wifiOnly: Boolean, val notice: String?)

    private val volumeList = MutableStateFlow<List<Volume>>(emptyList())
    private val selectedPath = MutableStateFlow(prefs.volumePath)
    private val wifiOnly = MutableStateFlow(prefs.wifiOnly)
    private val notice = MutableStateFlow<String?>(null)
    private val settings = combine(volumeList, selectedPath, wifiOnly, notice, ::Settings)

    val ui: StateFlow<DownloadsUi> =
        combine(catalog.state, downloads.records, library.packs, settings, ::buildUi)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUi())

    init {
        viewModelScope.launch {
            catalog.load()
            catalog.refresh()
        }
        refreshVolumes()
    }

    /** Free space changes outside the app; called on resume and after every action. */
    fun refreshVolumes() {
        viewModelScope.launch { volumeList.value = withContext(io) { volumes.list() } }
    }

    fun refreshCatalog() {
        viewModelScope.launch { catalog.refresh() }
    }

    fun get(itemId: String) {
        val item = catalog.state.value.catalog?.item(itemId) ?: return
        appScope.launch {
            notice.value = enqueue(listOf(item))
            refreshVolumes()
        }
    }

    fun getPreset(presetId: String) {
        val current = catalog.state.value.catalog ?: return
        val preset = current.presets.firstOrNull { it.id == presetId } ?: return
        appScope.launch {
            val records = downloads.records.first()
            val packs = library.packs.first()
            val todo = preset.items.mapNotNull { current.item(it) }.filter { wanted(statusOf(it, records, packs, tier)) }
            notice.value = if (todo.isEmpty()) {
                "Everything in ${preset.title} is already here or on its way."
            } else {
                enqueue(todo)
            }
            refreshVolumes()
        }
    }

    fun cancel(itemId: String) {
        appScope.launch {
            downloads.cancel(itemId)
            refreshVolumes()
        }
    }

    fun retry(itemId: String) {
        appScope.launch { downloads.retry(itemId) }
    }

    /** Deletes an installed item. For packs the library goes first, so its bookmarks go with it. */
    fun remove(itemId: String) {
        appScope.launch {
            val entry = catalog.state.value.catalog?.item(itemId) as? PackEntry
            val packId = entry?.let { installedPackId(it, downloads.records.first(), library.packs.first()) }
            if (packId != null && !library.remove(packId)) {
                notice.value = "Couldn't delete ${entry.title}."
                return@launch
            }
            downloads.remove(itemId)
            refreshVolumes()
        }
    }

    fun setWifiOnly(value: Boolean) {
        wifiOnly.value = value
        prefs.wifiOnly = value
        appScope.launch { downloads.setWifiOnly(value) }
    }

    fun selectVolume(path: String) {
        selectedPath.value = path
        prefs.volumePath = path
    }

    fun dismissNotice() {
        notice.value = null
    }

    private fun wanted(status: ItemStatus) = status == ItemStatus.NotInstalled || status == ItemStatus.UpdateAvailable

    /** Queues [items] in order and stops at the first one that doesn't fit. Returns the notice to show. */
    private suspend fun enqueue(items: List<CatalogItem>): String? {
        val all = volumeList.value.ifEmpty { withContext(io) { volumes.list() } }
        val volume = all.firstOrNull { it.root.path == selectedPath.value } ?: all.firstOrNull()
            ?: return "No storage is available right now."
        var queued = 0
        for (item in items) {
            val problem = when (val result = downloads.enqueue(item, volume.root, wifiOnly.value)) {
                EnqueueResult.Queued -> null.also { queued++ }
                EnqueueResult.AlreadyActive, EnqueueResult.AlreadyInstalled -> null
                is EnqueueResult.NoSpace ->
                    "Not enough space on ${volume.label} for ${item.title}: free up ${formatBytes(result.neededBytes)}."
                is EnqueueResult.OverCap ->
                    "${item.title} would bring your packs to ${formatBytes(result.wouldUseBytes)}, " +
                        "over the ${formatBytes(result.capBytes)} limit."
            }
            if (problem != null) return if (queued > 0) "$problem Queued $queued before that." else problem
        }
        return if (items.size > 1 && queued > 0) "Queued $queued downloads." else null
    }

    private fun buildUi(state: CatalogState, records: List<DownloadRecord>, packs: List<Pack>, s: Settings): DownloadsUi {
        val current = state.catalog
        val active = records.filter { it.isActive }
        val volumeRows = s.volumes.map { v ->
            VolumeRow(
                path = v.root.path,
                label = v.label,
                removable = v.removable,
                freeBytes = v.freeBytes,
                totalBytes = v.totalBytes,
                grangerBytes = v.grangerBytes,
                queuedBytes = active.filter { it.filePath.startsWith(v.root.path + File.separator) }
                    .sumOf { it.sizeBytes - it.bytesDone },
            )
        }
        fun row(item: CatalogItem): ItemRow {
            val details = when (item) {
                is PackEntry -> listOf(item.language, "Updated ${item.snapshotDate}", item.license)
                is ModelEntry -> listOf(item.license)
            }.filter { it.isNotBlank() }.joinToString(" · ")
            val packId = (item as? PackEntry)?.let { installedPackId(it, records, packs) }
            return ItemRow(item.id, item.title, details, item.sizeBytes, statusOf(item, records, packs, tier), packId)
        }
        val sections = if (current == null) emptyList() else buildList {
            if (current.models.isNotEmpty()) add(Section("AI models", current.models.map(::row)))
            current.packs.groupBy { it.category }
                .toList()
                .sortedWith(compareBy({ categoryRank(it.first) }, { it.first }))
                .forEach { (category, entries) -> add(Section(category, entries.map(::row))) }
        }
        val presets = current?.presets.orEmpty().filter { it.items.isNotEmpty() }.map { preset ->
            val items = preset.items.mapNotNull { current?.item(it) }
            val todo = items.filter { wanted(statusOf(it, records, packs, tier)) }
            PresetRow(preset.id, preset.title, items.size, todo.size, todo.sumOf { it.sizeBytes })
        }
        return DownloadsUi(
            loading = current == null && state.error == null,
            refreshing = state.refreshing,
            catalogProblem = catalogProblem(state),
            volumes = volumeRows,
            selectedVolume = volumeRows.firstOrNull { it.path == s.selectedPath }?.path ?: volumeRows.firstOrNull()?.path,
            wifiOnly = s.wifiOnly,
            presets = presets,
            sections = sections,
            notice = s.notice,
        )
    }

    private fun catalogProblem(state: CatalogState): String? {
        if (state.catalog == null) {
            return if (state.error != null) "Couldn't load the catalog. Connect to the internet and try again." else null
        }
        return when (state.error) {
            CatalogError.NETWORK -> "Offline: showing the catalog from your last connection."
            CatalogError.BAD_SIGNATURE -> "The latest catalog failed its security check. Showing the last good copy."
            CatalogError.UNSUPPORTED_FORMAT -> "A newer catalog needs an app update. Showing the last copy that works."
            CatalogError.MALFORMED -> "Couldn't read the latest catalog. Showing the last good copy."
            CatalogError.OLDER_THAN_CACHED, null -> null
        }
    }

    private companion object {
        val CATEGORY_ORDER = listOf(
            "Medical", "Reference", "Religion & Philosophy", "Literature & Drama", "Science", "Repair", "Regional",
        )

        fun categoryRank(category: String) = CATEGORY_ORDER.indexOf(category).let { if (it < 0) Int.MAX_VALUE else it }
    }
}
