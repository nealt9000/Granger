package app.granger.sources

import app.granger.core.catalog.downloadBytes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.DENSE_RETRIEVAL
import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.CatalogError
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.CatalogSource
import app.granger.core.catalog.CatalogState
import app.granger.core.catalog.ModelEntry
import app.granger.core.catalog.ModelRole
import app.granger.core.catalog.BookEntry
import app.granger.core.catalog.PackEntry
import app.granger.core.catalog.VoiceEntry
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.EnqueueResult
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.VectorDownloads
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
    /** What the entry is, from the catalog; blank when it has none. */
    val description: String,
    val sizeBytes: Long,
    val status: ItemStatus,
    /** The library pack this entry is installed as, if any: Open opens it (Plan U3b Decision 3). */
    val packId: String?,
    /** How Ask can search this pack; null for anything else, and for maps. */
    val ask: AskReach? = null,
)

/**
 * A heading and the rows shown under it. A [foldable] section shows its first [SourceViewModel.FOLDED_ROWS] rows until
 * Show all; [total] counts all of them, which are [noun] ("Show all 40 books").
 */
data class Section(
    val title: String,
    val rows: List<ItemRow>,
    val total: Int = rows.size,
    val foldable: Boolean = false,
    val noun: String = "packs",
)

/**
 * A preset's items counted by kind, with their size: [packs], [books] and [others] (models and voices). [onPhone] are
 * installed, [downloading] queued or downloading; [remainingCount] of [remainingBytes] are what Get all would queue.
 */
data class PresetRow(
    val id: String,
    val title: String,
    val itemCount: Int,
    val remainingCount: Int,
    val remainingBytes: Long,
    val packs: Int = 0,
    val books: Int = 0,
    val others: Int = 0,
    val totalBytes: Long = 0,
    val onPhone: Int = 0,
    val downloading: Int = 0,
)

/** A preset's lines (Plan U3b Decision 13). */
object PresetText {
    /** "12 packs and 347 books · 17.7 GB". */
    fun summary(row: PresetRow): String {
        val kinds = listOfNotNull(
            count(row.packs, "pack", "packs"), count(row.books, "book", "books"), count(row.others, "model", "models"),
        )
        val joined = if (kinds.size <= 1) kinds.joinToString() else kinds.dropLast(1).joinToString(", ") + " and " + kinds.last()
        return "$joined · ${formatBytes(row.totalBytes)}"
    }

    /** "All on this phone"; else "3 downloading", "9 of 12 to get", or both. */
    fun status(row: PresetRow): String {
        if (row.onPhone >= row.itemCount) return "All on this phone"
        val toGet = row.itemCount - row.onPhone - row.downloading
        return listOfNotNull(
            if (row.downloading > 0) "${row.downloading} downloading" else null,
            if (toGet > 0) "$toGet of ${row.itemCount} to get" else null,
        ).joinToString(" · ")
    }

    private fun count(n: Int, one: String, many: String) = when (n) {
        0 -> null
        1 -> "1 $one"
        else -> "$n $many"
    }
}

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
    /** The Kiwix screen's category chips, in order; empty elsewhere. */
    val categories: List<String> = emptyList(),
    /** The chosen chip; null is All. */
    val category: String? = null,
    /** The readiness domain the Kiwix list is filtered to (Plan H1, spec §4.1); null when it isn't. */
    val domainTitle: String? = null,
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
    /** Opened from Readiness: the Kiwix list filtered to this domain until the chip is closed. */
    private val domain: String? = null,
) : ViewModel() {
    private data class Choices(val volumes: List<Volume>, val selectedPath: String?, val wifiOnly: Boolean, val notice: String?, val domainCleared: Boolean)

    private data class Settings(
        val volumes: List<Volume>,
        val selectedPath: String?,
        val wifiOnly: Boolean,
        val notice: String?,
        val tab: SourceTab,
        val query: String,
        val expanded: Set<String>,
        val category: String?,
        val domain: String?,
    )

    private val volumeList = MutableStateFlow<List<Volume>>(emptyList())
    private val selectedPath = MutableStateFlow(prefs.volumePath)
    private val wifiOnly = MutableStateFlow(prefs.wifiOnly)
    private val notice = MutableStateFlow<String?>(null)
    private val tab = MutableStateFlow(SourceTab.BROWSE)
    private val query = MutableStateFlow("")
    /** Sections opened with Show all, by title; closed again by a new query. */
    private val expanded = MutableStateFlow<Set<String>>(emptySet())
    private val category = MutableStateFlow<String?>(null)
    private val domainCleared = MutableStateFlow(false)

    /**
     * The search field's text, as Compose state written synchronously on every keystroke. The field must not read its
     * value from a flow: a value that lags a dispatch behind the keyboard is pushed back into the field and scrambles
     * fast typing (seen on the Pixel 6, 2026-10-17).
     */
    var searchText by mutableStateOf("")
        private set
    private val settings = combine(
        combine(volumeList, selectedPath, wifiOnly, notice, domainCleared, ::Choices),
        tab,
        query,
        expanded,
        category,
    ) { c, tab, query, expanded, category ->
        Settings(c.volumes, c.selectedPath, c.wifiOnly, c.notice, tab, query, expanded, category, if (c.domainCleared) null else domain)
    }

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
        expanded.value = emptySet()
    }

    /** Shows only [value]'s packs, unfolded; null shows them all again. */
    /** The ✕ on the domain chip: back to the whole Kiwix catalog with its category chips. */
    fun clearDomain() {
        domainCleared.value = true
    }

    fun setCategory(value: String?) {
        category.value = value
    }

    /** Show all, or Show fewer, for the section titled [title]. */
    fun toggleSection(title: String) {
        expanded.value = expanded.value.let { if (title in it) it - title else it + title }
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
        val gutenberg = scope == CatalogScope.GUTENBERG
        val volumeRows = volumeRows(s.volumes, records)
        // With the whole Kiwix catalog listed, each item's status is worked out once, from its own records only.
        val recordsById = records.groupBy { it.itemId }
        val statuses = HashMap<String, ItemStatus>()
        val installedVectorParents = records
            .filter { it.kind == ItemKind.VECTORS && it.state == DownloadState.INSTALLED }
            .mapNotNullTo(HashSet()) { VectorDownloads.parentOf(it.itemId) }
        fun status(item: CatalogItem): ItemStatus =
            statuses.getOrPut(item.id) { statusOf(item, recordsById[item.id].orEmpty(), packs, tier) }
        fun row(item: CatalogItem): ItemRow {
            val details = when (item) {
                is PackEntry -> listOf(
                    item.language, "Updated ${item.snapshotDate}",
                    if (item.nonCommercial) "${item.license} · provided by Kiwix" else item.license,
                )
                is BookEntry -> listOf(item.author, item.license)
                is ModelEntry -> listOf(item.license)
                is VoiceEntry -> listOf("Read-aloud voice", item.language, item.license)
            }.filter { it.isNotBlank() }.joinToString(" · ")
            val packId = (item as? PackEntry)?.let { installedPackId(it, recordsById[it.id].orEmpty(), packs) }
            val description = when (item) {
                is PackEntry -> item.description
                is BookEntry -> item.description
                // Research embeds its questions with it once dense retrieval is on (Plan R1); until then nothing uses it.
                // The large model's catalog entry stays, but Research no longer uses it (Plan R5 Task 8).
                is ModelEntry -> when (item.role) {
                    ModelRole.SMALL -> ""
                    ModelRole.LARGE -> "Not used"
                    ModelRole.EMBEDDER -> if (DENSE_RETRIEVAL) "Used by Ask" else "Not used yet"
                }
                is VoiceEntry -> item.attribution
            }
            val ask = (item as? PackEntry)?.let { askReach(it, installedVectorParents) }
            // On Project Gutenberg's own screen, each pack is its subject alone.
            val title = if (gutenberg) item.title.removeSuffix(GUTENBERG_SUFFIX) else item.title
            return ItemRow(item.id, title, details, description, item.downloadBytes, status(item), packId, ask)
        }
        val needle = s.query.trim()
        val kiwix = scope == CatalogScope.KIWIX
        // A domain the catalog's model doesn't name shows the whole list, with no chip (Plan H1 Task 5).
        val domainId = s.domain?.takeIf { kiwix }
        val domainTitle = domainId?.let { id -> current?.readiness?.domains?.firstOrNull { it.id == id }?.title }
        val inDomain = domainTitle != null
        fun shown(item: CatalogItem): Boolean {
            if (scopeOf(item) != scope) return false
            if (inDomain && (item !is PackEntry || item.domain != domainId)) return false
            val status = status(item)
            val inTab = when (s.tab) {
                SourceTab.BROWSE -> true
                SourceTab.INSTALLED -> status == ItemStatus.Installed || status == ItemStatus.UpdateAvailable ||
                    recordsById[item.id].orEmpty().any { it.isActive }
                SourceTab.UPDATES -> status == ItemStatus.UpdateAvailable
            }
            val texts = when (item) {
                // The ZIM name too, so "stackoverflow" finds Stack Overflow.
                is PackEntry -> listOf(item.title, item.description, item.zimName)
                is BookEntry -> listOf(item.title, item.author, item.description)
                else -> listOf(item.title)
            }
            return inTab && (needle.isEmpty() || texts.any { it.contains(needle, ignoreCase = true) })
        }
        val categories = if (!kiwix || current == null || inDomain) emptyList() else
            current.packs.filter { scopeOf(it) == scope }.map { it.category }.distinct()
                .sortedWith(compareBy({ categoryRank(it) }, { it }))
        // A chip shows one category whole; a category that has left the catalog falls back to All.
        val chosen = s.category?.takeIf { it in categories }
        // The Kiwix screen lists the whole catalog, so its categories fold until Show all, except while searching.
        val folds = kiwix && needle.isEmpty() && chosen == null && !inDomain
        // Granger's book categories fold the same way, so Classics' ~350 books don't load as one list (Plan U3b Decision 13).
        val booksFold = scope == CatalogScope.GRANGER && needle.isEmpty()
        fun section(title: String, entries: List<CatalogItem>, folding: Boolean = folds, noun: String = "packs"): Section {
            val open = !folding || title in s.expanded || entries.size <= FOLDED_ROWS
            val rows = (if (open) entries else entries.take(FOLDED_ROWS)).map(::row)
            return Section(title, rows, entries.size, foldable = folding && entries.size > FOLDED_ROWS, noun = noun)
        }
        val sections = if (current == null) emptyList() else buildList {
            val models = current.models.filter(::shown)
            if (models.isNotEmpty()) add(Section("AI models", models.map(::row)))
            val packs = current.packs.filter { shown(it) && (chosen == null || it.category == chosen) }
            val (featured, rest) = if (inDomain) {
                // Recommended in a domain: its tier items (essential first), the uninstalled before the installed.
                val domain = current.readiness!!.domains.first { it.id == domainId }
                val tierIds = (domain.essential + domain.comprehensive).distinct()
                val byId = packs.associateBy { it.id }
                val tier = tierIds.mapNotNull { byId[it] }.sortedBy { status(it) == ItemStatus.Installed }
                tier to packs.filterNot { it.id in tierIds }
            } else {
                packs.partition { kiwix && chosen == null && it.featured }
            }
            if (gutenberg) {
                // Project Gutenberg (Plan GB Decision 7): the subject packs A–Z, then the two everything-packs, each with its size.
                val (everything, subjects) = packs.partition { it.zimName.endsWith("_all") }
                if (subjects.isNotEmpty()) add(Section(SUBJECTS, subjects.sortedBy { it.title.lowercase() }.map(::row)))
                if (everything.isNotEmpty()) add(Section(EVERYTHING, everything.sortedBy { it.sizeBytes }.map(::row)))
                return@buildList
            }
            if (featured.isNotEmpty()) add(Section(RECOMMENDED, featured.map(::row)))
            rest.groupBy { it.category }
                .toList()
                .sortedWith(compareBy({ categoryRank(it.first) }, { it.first }))
                .forEach { (category, entries) -> add(section(category, entries)) }
            // Books come after the packs, grouped by category in catalog order.
            current.books.filter(::shown).groupBy { it.category }
                .forEach { (category, entries) -> add(section(category, entries, folding = booksFold, noun = "books")) }
        }
        // Granger's screen is Get more's Granger Classics, under Books: only a set of books is offered there. Starter,
        // Efficient and Comprehensive bring Kiwix's wikis and the models, which aren't books (Plan GB walkthrough).
        val presets = if (scope != CatalogScope.GRANGER) emptyList() else current?.presets.orEmpty().filter { preset ->
            preset.items.isNotEmpty() && preset.items.all { current?.item(it) is BookEntry }
        }.sortedByDescending { it.id == CLASSICS_PRESET }.map { preset ->
            val items = preset.items.mapNotNull { current?.item(it) }
            val statuses = items.map(::status)
            val todo = items.filterIndexed { i, _ -> wanted(statuses[i]) }
            PresetRow(
                preset.id, preset.title, items.size, todo.size, todo.sumOf { it.downloadBytes },
                packs = items.count { it is PackEntry }, books = items.count { it is BookEntry },
                others = items.count { it !is PackEntry && it !is BookEntry }, totalBytes = items.sumOf { it.downloadBytes },
                onPhone = statuses.count { it == ItemStatus.Installed },
                downloading = statuses.count { it is ItemStatus.Queued || it is ItemStatus.Downloading },
            )
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
            categories = categories,
            category = chosen,
            domainTitle = domainTitle,
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

    companion object {
        /** Rows a folded section shows before Show all. */
        const val FOLDED_ROWS = 8
        const val RECOMMENDED = "Recommended"
        const val SUBJECTS = "Subjects"
        const val EVERYTHING = "Everything"
        private const val GUTENBERG_SUFFIX = " · Project Gutenberg"
        private const val CLASSICS_PRESET = "granger-classics"

        /** The family table's categories (tools/catalog-gen/kiwix_families.json); any other sorts after, by name. */
        private val CATEGORY_ORDER = listOf(
            "Medical", "Reference", "Religion & Philosophy", "Literature & Drama", "Science & Math", "How-to",
            "Regional", "Q&A", "Maps", "Programming", "Games & Hobbies", "Other",
        )

        private fun categoryRank(category: String) = CATEGORY_ORDER.indexOf(category).let { if (it < 0) Int.MAX_VALUE else it }
    }
}
