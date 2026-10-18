package app.granger.sources

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.CatalogError
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.CatalogSource
import app.granger.core.catalog.CatalogState
import app.granger.core.catalog.ModelEntry
import app.granger.core.catalog.BookEntry
import app.granger.core.catalog.PackEntry
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.EnqueueResult
import app.granger.core.downloads.Volume
import app.granger.core.downloads.VolumeSource
import app.granger.core.downloads.isActive
import app.granger.core.library.BookIds
import app.granger.core.library.BookOrigin
import app.granger.core.library.Bookshelf
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

enum class SourceTab(val title: String) { BROWSE("Browse"), INSTALLED("Installed"), UPDATES("Updates") }

data class SourceUi(
    val tab: SourceTab = SourceTab.BROWSE,
    val query: String = "",
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

/** One row per volume, with what is still to download onto it. */
fun volumeRows(volumes: List<Volume>, records: List<DownloadRecord>): List<VolumeRow> {
    val active = records.filter { it.isActive }
    return volumes.map { v ->
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
}

/** One archive source (Granger, Kiwix or Models): the catalog items in [scope], with the downloads around them. */
class SourceViewModel(
    private val scope: CatalogScope,
    private val catalog: CatalogSource,
    private val downloads: Downloads,
    private val library: Library,
    private val bookshelf: Bookshelf,
    private val volumes: VolumeSource,
    private val prefs: DownloadPrefs,
    private val tier: DeviceTier,
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class Choices(val volumes: List<Volume>, val selectedPath: String?, val wifiOnly: Boolean, val notice: String?)

    private data class Settings(
        val volumes: List<Volume>,
        val selectedPath: String?,
        val wifiOnly: Boolean,
        val notice: String?,
        val tab: SourceTab,
        val query: String,
    )

    private val volumeList = MutableStateFlow<List<Volume>>(emptyList())
    private val selectedPath = MutableStateFlow(prefs.volumePath)
    private val wifiOnly = MutableStateFlow(prefs.wifiOnly)
    private val notice = MutableStateFlow<String?>(null)
    private val tab = MutableStateFlow(SourceTab.BROWSE)
    private val query = MutableStateFlow("")

    /**
     * The search field's text, as Compose state written synchronously on every keystroke. The field must not read its
     * value from a flow: a value that lags a dispatch behind the keyboard is pushed back into the field and scrambles
     * fast typing (seen on the Pixel 6, 2026-10-17).
     */
    var searchText by mutableStateOf("")
        private set
    private val settings = combine(
        combine(volumeList, selectedPath, wifiOnly, notice, ::Choices),
        tab,
        query,
    ) { c, tab, query -> Settings(c.volumes, c.selectedPath, c.wifiOnly, c.notice, tab, query) }

    val ui: StateFlow<SourceUi> =
        combine(catalog.state, downloads.records, library.packs, settings, ::buildUi)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourceUi())

    init {
        viewModelScope.launch {
            catalog.load()
            catalog.refresh()
        }
        refreshVolumes()
    }

    /** Free space and, from Settings, the download choices change outside this screen; called on resume and after every action. */
    fun refreshVolumes() {
        selectedPath.value = prefs.volumePath
        wifiOnly.value = prefs.wifiOnly
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
            // A book's shelf entry goes with its download; its place and marks stay for a later re-download.
            if (catalog.state.value.catalog?.item(itemId) is BookEntry) {
                bookshelf.remove(BookIds.of(BookOrigin.GRANGER, itemId))
            }
            refreshVolumes()
        }
    }

    fun setTab(value: SourceTab) {
        tab.value = value
    }

    fun setQuery(value: String) {
        searchText = value
        query.value = value
    }

    fun dismissNotice() {
        notice.value = null
    }

    private fun wanted(status: ItemStatus) = status == ItemStatus.NotInstalled || status == ItemStatus.UpdateAvailable

    /** Queues [items] in order and stops at the first one that doesn't fit. Returns the notice to show. */
    private suspend fun enqueue(items: List<CatalogItem>): String? {
        val all = volumeList.value.ifEmpty { withContext(io) { volumes.list() } }
        val volume = all.firstOrNull { it.root.path == prefs.volumePath } ?: all.firstOrNull()
            ?: return "No storage is available right now."
        var queued = 0
        for (item in items) {
            val problem = when (val result = downloads.enqueue(item, volume.root, prefs.wifiOnly)) {
                EnqueueResult.Queued -> null.also { queued++ }
                EnqueueResult.AlreadyActive, EnqueueResult.AlreadyInstalled -> null
                is EnqueueResult.NoSpace ->
                    "Not enough space on ${volume.label} for ${item.title}: free up ${formatBytes(result.neededBytes)}."
                is EnqueueResult.OverCap ->
                    "${item.title} would bring your downloads to ${formatBytes(result.wouldUseBytes)}, " +
                        "over the ${formatBytes(result.capBytes)} limit."
            }
            if (problem != null) return if (queued > 0) "$problem Queued $queued before that." else problem
        }
        return if (items.size > 1 && queued > 0) "Queued $queued downloads." else null
    }

    private fun buildUi(state: CatalogState, records: List<DownloadRecord>, packs: List<Pack>, s: Settings): SourceUi {
        val current = state.catalog
        val volumeRows = volumeRows(s.volumes, records)
        fun row(item: CatalogItem): ItemRow {
            val details = when (item) {
                is PackEntry -> listOf(
                    item.language, "Updated ${item.snapshotDate}",
                    if (item.nonCommercial) "${item.license} · provided by Kiwix" else item.license,
                )
                is BookEntry -> listOf(item.author, item.license)
                is ModelEntry -> listOf(item.license)
            }.filter { it.isNotBlank() }.joinToString(" · ")
            val packId = (item as? PackEntry)?.let { installedPackId(it, records, packs) }
            return ItemRow(item.id, item.title, details, item.sizeBytes, statusOf(item, records, packs, tier), packId)
        }
        val needle = s.query.trim()
        fun shown(item: CatalogItem): Boolean {
            if (scopeOf(item) != scope) return false
            val status = statusOf(item, records, packs, tier)
            val inTab = when (s.tab) {
                SourceTab.BROWSE -> true
                SourceTab.INSTALLED -> status == ItemStatus.Installed || status == ItemStatus.UpdateAvailable ||
                    records.any { it.itemId == item.id && it.isActive }
                SourceTab.UPDATES -> status == ItemStatus.UpdateAvailable
            }
            val description = when (item) {
                is PackEntry -> item.description
                is BookEntry -> item.description
                else -> ""
            }
            return inTab && (needle.isEmpty() || item.title.contains(needle, ignoreCase = true) ||
                description.contains(needle, ignoreCase = true))
        }
        val sections = if (current == null) emptyList() else buildList {
            val models = current.models.filter(::shown)
            if (models.isNotEmpty()) add(Section("AI models", models.map(::row)))
            current.packs.filter(::shown).groupBy { it.category }
                .toList()
                .sortedWith(compareBy({ categoryRank(it.first) }, { it.first }))
                .forEach { (category, entries) -> add(Section(category, entries.map(::row))) }
            // Books come after the packs, grouped by category in catalog order.
            current.books.filter(::shown).groupBy { it.category }
                .forEach { (category, entries) -> add(Section(category, entries.map(::row))) }
        }
        val presets = if (scope != CatalogScope.GRANGER) emptyList() else current?.presets.orEmpty().filter { it.items.isNotEmpty() }.sortedByDescending { it.id == CLASSICS_PRESET }.map { preset ->
            val items = preset.items.mapNotNull { current?.item(it) }
            val todo = items.filter { wanted(statusOf(it, records, packs, tier)) }
            PresetRow(preset.id, preset.title, items.size, todo.size, todo.sumOf { it.sizeBytes })
        }
        return SourceUi(
            tab = s.tab,
            query = s.query,
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
        const val CLASSICS_PRESET = "granger-classics"

        val CATEGORY_ORDER = listOf(
            "Medical", "Reference", "Religion & Philosophy", "Literature & Drama", "Science", "Repair", "Regional",
        )

        fun categoryRank(category: String) = CATEGORY_ORDER.indexOf(category).let { if (it < 0) Int.MAX_VALUE else it }
    }
}
