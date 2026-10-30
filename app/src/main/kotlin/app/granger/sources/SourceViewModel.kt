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
import app.granger.library.shelf.sortedByShelfKey
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
    /** The pack's flavours, when it has more than one (Plan KB Decision 8); the [id] is the chosen one's. */
    val flavours: List<FlavourChoice> = emptyList(),
    /** The flavour on the phone, as a row says it ("no pictures"), when the pack has flavours and one is installed. */
    val installedFlavour: String? = null,
    /** Get fetches another flavour than the one on the phone: the action reads Switch. */
    val switches: Boolean = false,
    /** The full licence, for the row's details (its short name is in [details]); blank for anything but a pack. */
    val license: String = "",
    /** A folded family's row (Plan KB Decision 10): the family it opens, its packs A–Z. */
    val opensFamily: String? = null,
    /** The set a set's row is of (Plan WF Decision 3); null for any other row. */
    val setId: String? = null,
    /** A set's row: How much, smallest first; the [id] is the chosen slice's chosen pictures. */
    val slices: List<SliceChoice> = emptyList(),
    /** A set's row: the pictures choices for the chosen slice. */
    val pictures: List<PictureChoice> = emptyList(),
    /** A set's chosen slice is inside a bigger one on the phone: "Inside your Top 1 million", with no Get. */
    val insideOf: String? = null,
    /** A set's Switch, between flavours of one slice: what goes and what comes. */
    val switchFrom: FlavourChoice? = null,
    val switchTo: FlavourChoice? = null,
    /** A set's row: the subjects list it opens with Add a subject ("subjects:wikipedia_en:wikipedia_en_top"). */
    val subjects: String? = null,
    /** A subject's line (Plan WF Decision 4): "Adds about 69,600 articles to your Best 50,000 (95% new)". */
    val adds: String? = null,
    /** The subject is inside the Wikipedia on the phone: Get is quiet. */
    val covered: Boolean = false,
)

/**
 * One slice of a set's row: "Best 50,000 articles · 7.0 GB" for the chosen pictures; [note] when it doesn't offer them
 * ("With pictures only"); [inside] when a bigger slice on the phone holds it.
 */
data class SliceChoice(
    val zimName: String,
    val label: String,
    val sizeBytes: Long,
    val chosen: Boolean,
    val installed: Boolean = false,
    val inside: String? = null,
    val note: String? = null,
)

/** One pictures choice of a set's row; one the chosen slice doesn't offer is not [enabled], with its [reason]. */
data class PictureChoice(val flavour: String, val label: String, val chosen: Boolean, val enabled: Boolean = true, val reason: String? = null)

/** One flavour of a pack's row: "With pictures · 6.9 GB"; [installed] when it is the one on the phone. */
data class FlavourChoice(val id: String, val label: String, val sizeBytes: Long, val chosen: Boolean, val installed: Boolean = false)

/**
 * A Get that asks first (Plan KB Decision 9): [title] is [sizeBytes], [freeBytes] free on [volumeLabel]; [smaller] is
 * the pack's next smaller flavour, offered instead, when it has one.
 */
data class SizeCheck(
    val itemId: String,
    val title: String,
    val sizeBytes: Long,
    val freeBytes: Long,
    val volumeLabel: String,
    val smaller: FlavourChoice? = null,
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
    /** A big Get waiting on its question (Plan KB Decision 9). */
    val sizeCheck: SizeCheck? = null,
    /** One folded family's A–Z list ("Developer docs"), when this screen is one (Plan KB Decision 10); else null. */
    val familyTitle: String? = null,
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
    /** Opened from a folded family's row: that family's packs A–Z, with their own search (Plan KB Decision 10). */
    private val family: String? = null,
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
        val flavours: Map<String, String> = emptyMap(),
        val sizeCheck: SizeCheck? = null,
        val sets: SetChoice = SetChoice(),
    )

    /** Each set's chosen slice (ZIM name) and pictures (flavour), by set id (Plan WF Decision 3). */
    private data class SetChoice(val slices: Map<String, String> = emptyMap(), val pictures: Map<String, String> = emptyMap())

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
    /** Each pack's chosen flavour, by ZIM name (Plan KB Decision 8). */
    private val flavourChoice = MutableStateFlow<Map<String, String>>(emptyMap())
    private val sizeCheck = MutableStateFlow<SizeCheck?>(null)
    private val setChoice = MutableStateFlow(SetChoice())

    /**
     * The search field's text, as Compose state written synchronously on every keystroke. The field must not read its
     * value from a flow: a value that lags a dispatch behind the keyboard is pushed back into the field and scrambles
     * fast typing (seen on the Pixel 6, 2026-10-17).
     */
    var searchText by mutableStateOf("")
        private set
    private val settings = combine(
        combine(
            combine(volumeList, selectedPath, wifiOnly, notice, domainCleared, ::Choices),
            tab,
            query,
            expanded,
            category,
        ) { c, tab, query, expanded, category ->
            Settings(c.volumes, c.selectedPath, c.wifiOnly, c.notice, tab, query, expanded, category, if (c.domainCleared) null else domain)
        },
        flavourChoice,
        sizeCheck,
        setChoice,
    ) { settings, flavours, check, sets -> settings.copy(flavours = flavours, sizeCheck = check, sets = sets) }

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

    /** Gets [itemId]; a pack over [KiwixList.SIZE_CHECK_BYTES] asks first ([SourceUi.sizeCheck]). */
    fun get(itemId: String) {
        val current = catalog.state.value.catalog ?: return
        val item = current.item(itemId) ?: return
        if (item is PackEntry && item.downloadBytes > KiwixList.SIZE_CHECK_BYTES) {
            viewModelScope.launch {
                val all = volumeList.value.ifEmpty { withContext(io) { volumes.list() } }
                val volume = all.firstOrNull { it.root.path == prefs.volumePath } ?: all.firstOrNull()
                val group = KiwixList.group(current.packs.filter { it.zimName == item.zimName }).firstOrNull { it.flavour(item.id) != null }
                val smaller = KiwixList.smallerThan(group, item)?.let {
                    FlavourChoice(it.id, group?.let(KiwixList::flavourLabels)?.get(it.id) ?: KiwixList.flavourLabel(it), it.downloadBytes, chosen = false)
                }
                sizeCheck.value = SizeCheck(
                    item.id, group?.title ?: item.title, item.downloadBytes, volume?.freeBytes ?: 0L, volume?.label ?: "this phone", smaller,
                )
            }
            return
        }
        enqueueOne(item)
    }

    /** Yes to the size check: gets [itemId] (the big one, or the smaller flavour it offered). */
    fun confirmGet(itemId: String) {
        sizeCheck.value = null
        val item = catalog.state.value.catalog?.item(itemId) ?: return
        (item as? PackEntry)?.let { flavourChoice.value = flavourChoice.value + (it.zimName to it.id) }
        enqueueOne(item)
    }

    fun dismissSizeCheck() {
        sizeCheck.value = null
    }

    /** The flavour [itemId] is now its pack's choice: the row shows its size, and Get fetches it. */
    fun chooseFlavour(itemId: String) {
        val item = catalog.state.value.catalog?.item(itemId) as? PackEntry ?: return
        flavourChoice.value = flavourChoice.value + (item.zimName to item.id)
    }

    /** How much of set [setId]: the slice [zimName] is now its row's choice. */
    fun chooseSlice(setId: String, zimName: String) {
        setChoice.value = setChoice.value.let { it.copy(slices = it.slices + (setId to zimName)) }
    }

    /** With pictures, No pictures or Mini ([flavour]) for set [setId]'s row. */
    fun choosePictures(setId: String, flavour: String) {
        setChoice.value = setChoice.value.let { it.copy(pictures = it.pictures + (setId to flavour)) }
    }

    private fun enqueueOne(item: CatalogItem) {
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
        val kiwixBooks = scope == CatalogScope.KIWIX_BOOKS
        val volumeRows = volumeRows(s.volumes, records)
        // With the whole Kiwix catalog listed, each item's status is worked out once, from its own records only.
        val recordsById = records.groupBy { it.itemId }
        val statuses = HashMap<String, ItemStatus>()
        val installedVectorParents = records
            .filter { it.kind == ItemKind.VECTORS && it.state == DownloadState.INSTALLED }
            .mapNotNullTo(HashSet()) { VectorDownloads.parentOf(it.itemId) }
        fun status(item: CatalogItem): ItemStatus =
            statuses.getOrPut(item.id) { statusOf(item, recordsById[item.id].orEmpty(), packs, tier) }
        val installedStates = setOf(ItemStatus.Installed, ItemStatus.UpdateAvailable)
        // Plan WF: the subjects list's own set and slice, from its token "subjects:<set>:<slice ZIM name>".
        val subjectsToken = family?.takeIf { scope == CatalogScope.KIWIX && it.startsWith(SUBJECTS_PREFIX) }
            ?.removePrefix(SUBJECTS_PREFIX)?.split(':')
        val subjectsSet = subjectsToken?.getOrNull(0)
        val subjectsSlice = subjectsToken?.getOrNull(1)
        val freeBytes = volumeRows.firstOrNull { it.path == s.selectedPath }?.freeBytes ?: volumeRows.firstOrNull()?.freeBytes
        val setGroups = HashMap<String, PackGroup?>()
        fun setGroup(setId: String): PackGroup? = setGroups.getOrPut(setId) {
            current?.packs?.filter { it.setId == setId && it.isSlice }
                ?.let { KiwixList.group(it, freeBytes, sets = true).firstOrNull { g -> g.setId == setId } }
        }
        /** The slice a subject's line is about: the biggest on the phone, else the one chosen, else the set's default. */
        fun referenceSlice(setId: String): Pair<PackEntry, Boolean>? {
            val group = setGroup(setId) ?: return null
            group.flavours.filter { status(it) in installedStates }.maxByOrNull { it.setRank }?.let { return it to true }
            val zim = s.sets.slices[setId] ?: subjectsSlice?.takeIf { setId == subjectsSet } ?: group.default.zimName
            return (group.flavours.firstOrNull { it.zimName == zim } ?: group.default) to false
        }
        fun addsOf(subject: PackEntry): KiwixList.Adds? {
            if (!subject.isSubject) return null
            val (slice, installed) = referenceSlice(subject.subjectOf) ?: return null
            val group = setGroup(subject.subjectOf) ?: return null
            return KiwixList.adds(subject, slice, group.flavours.maxOf { it.setRank }, group.title, installed)
        }
        fun row(item: CatalogItem): ItemRow {
            val details = when (item) {
                // Plain words (Plan KB Decision 9): "English · Updated March 2026 · CC BY-SA".
                is PackEntry -> listOf(KiwixList.details(item))
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
            val adds = (item as? PackEntry)?.let(::addsOf)
            return ItemRow(
                item.id, title, details, description, item.downloadBytes, status(item), packId, ask,
                license = (item as? PackEntry)?.license.orEmpty(),
                adds = adds?.text,
                covered = adds?.covered ?: false,
            )
        }
        /** A folded family's row: "Developer docs", "243 packs". */
        fun familyRow(folded: KiwixList.Folded, count: Int) = ItemRow(
            "family:${folded.family}", folded.label, "$count ${folded.noun}", "", 0L, ItemStatus.NotInstalled, null,
            opensFamily = folded.family,
        )
        /**
         * A set's one row (Plan WF Decision 3): How much (the slices) × Pictures. The chosen slice is the one chosen, else
         * the biggest on the phone, else the default; its pictures the ones chosen, else those on the phone, else the
         * default's. A slice a bigger one on the phone holds reads "Inside your Top 1 million" (Decision 5).
         */
        fun setRow(group: PackGroup): ItemRow {
            val setId = group.setId!!
            val slices = KiwixList.slices(group)
            val installed = group.flavours.filter { status(it) in installedStates }
            val top = installed.maxByOrNull { it.setRank }
            val chosenSlice = slices.firstOrNull { it.zimName == s.sets.slices[setId] }
                ?: slices.firstOrNull { it.zimName == top?.zimName }
                ?: slices.first { it.zimName == group.default.zimName }
            val onPhone = chosenSlice.entries.firstOrNull { it in installed }
            val pictures = s.sets.pictures[setId] ?: onPhone?.let(KiwixList::picturesOf)
                ?: top?.let(KiwixList::picturesOf) ?: KiwixList.picturesOf(group.default)
            val entry = KiwixList.entryFor(chosenSlice, pictures)
            val base = row(entry)
            fun bigger(slice: KiwixList.Slice): PackEntry? =
                installed.filter { it.setRank > slice.rank }.maxByOrNull { it.setRank }?.takeIf { slice.entries.none { e -> e in installed } }
            fun insideText(e: PackEntry) = "Inside your ${KiwixList.shortLabel(e.setLabel)}"
            fun choiceLabel(e: PackEntry) = "${e.setLabel}, ${KiwixList.flavourLabel(KiwixList.picturesOf(e))}"
            val covering = bigger(chosenSlice)
            val switches = onPhone != null && onPhone.id != entry.id && status(entry) == ItemStatus.NotInstalled
            val offered = group.flavours.mapTo(HashSet(), KiwixList::picturesOf)
            val chosenOffers = chosenSlice.entries.mapTo(HashSet(), KiwixList::picturesOf)
            return base.copy(
                title = group.title,
                setId = setId,
                description = KiwixList.setDescription(setId, group.flavours),
                slices = slices.map { slice ->
                    val e = KiwixList.entryFor(slice, pictures)
                    SliceChoice(
                        slice.zimName, slice.label, e.downloadBytes, chosen = slice === chosenSlice,
                        installed = slice.entries.any { it in installed },
                        inside = bigger(slice)?.let(::insideText),
                        note = if (KiwixList.picturesOf(e) != pictures) "${KiwixList.flavourLabel(KiwixList.picturesOf(e))} only" else null,
                    )
                },
                pictures = PICTURES.filter { it in offered }.map { f ->
                    PictureChoice(
                        f, KiwixList.flavourLabel(f), chosen = KiwixList.picturesOf(entry) == f, enabled = f in chosenOffers,
                        reason = if (f in chosenOffers) null else "Not offered for ${chosenSlice.label}",
                    )
                },
                insideOf = covering?.let(::insideText),
                switches = switches,
                switchFrom = onPhone?.takeIf { switches }?.let { FlavourChoice(it.id, choiceLabel(it), it.downloadBytes, chosen = false, installed = true) },
                switchTo = entry.takeIf { switches }?.let { FlavourChoice(it.id, choiceLabel(it), it.downloadBytes, chosen = true) },
                packId = base.packId ?: (if (switches) onPhone else covering)?.let { installedPackId(it, recordsById[it.id].orEmpty(), packs) },
                subjects = if (current?.packs?.any { it.subjectOf == setId && !it.sample } == true) {
                    "$SUBJECTS_PREFIX$setId:${chosenSlice.zimName}"
                } else null,
            )
        }
        /** One row for a pack and its flavours (Plan KB Decision 8): the chosen flavour, else the installed, else the default. */
        fun groupRow(group: PackGroup): ItemRow {
            if (group.isSet) return setRow(group)
            if (group.flavours.size == 1) return row(group.default)
            val installed = group.flavours.firstOrNull { status(it) in installedStates }
            val chosenId = s.flavours[group.zimName]?.takeIf { group.flavour(it) != null } ?: installed?.id ?: group.default.id
            val entry = group.flavour(chosenId)!!
            val base = row(entry)
            val switches = installed != null && installed.id != entry.id && status(entry) == ItemStatus.NotInstalled
            val labels = KiwixList.flavourLabels(group)
            return base.copy(
                title = group.title,
                flavours = group.flavours.map {
                    FlavourChoice(it.id, labels.getValue(it.id), it.downloadBytes, it.id == entry.id, installed = it.id == installed?.id)
                },
                installedFlavour = installed?.let { labels.getValue(it.id).lowercase() },
                switches = switches,
                packId = base.packId ?: if (switches) installedPackId(installed!!, recordsById[installed.id].orEmpty(), packs) else null,
            )
        }
        val needle = s.query.trim()
        val kiwix = scope == CatalogScope.KIWIX
        // A domain the catalog's model doesn't name shows the whole list, with no chip (Plan H1 Task 5).
        val domainId = s.domain?.takeIf { kiwix }
        val domainTitle = domainId?.let { id -> current?.readiness?.domains?.firstOrNull { it.id == id }?.title }
        val inDomain = domainTitle != null
        val familyView = family?.takeIf { kiwix }?.let(KiwixList::folded)
        val words = app.granger.core.library.SearchText.words(needle)
        /** Whether [item] belongs on this list at all, before the tab and the search. */
        fun inList(item: CatalogItem): Boolean {
            // The 2 MB Ray Charles sample is a test pack (Plan WF Decision 4).
            if (item is PackEntry && item.sample) return false
            // The subjects list (Plan WF Decision 4): its set's subject packs.
            if (subjectsSet != null) return item is PackEntry && item.subjectOf == subjectsSet
            // A readiness domain lists its packs whichever scope they're offered in: Food keeps Water and Food for
            // Preppers, which are offered under Books (Plan KB Decision 6).
            if (scopeOf(item) != scope && !(inDomain && scopeOf(item) in PACK_SCOPES)) return false
            if (familyView != null && (item !is PackEntry || item.family != familyView.family)) return false
            return !(inDomain && (item !is PackEntry || item.domain != domainId))
        }
        fun inTab(item: CatalogItem): Boolean {
            val status = status(item)
            return when (s.tab) {
                SourceTab.BROWSE -> true
                SourceTab.INSTALLED -> status == ItemStatus.Installed || status == ItemStatus.UpdateAvailable ||
                    recordsById[item.id].orEmpty().any { it.isActive }
                SourceTab.UPDATES -> status == ItemStatus.UpdateAvailable
            }
        }
        fun shown(item: CatalogItem): Boolean {
            if (!inList(item)) return false
            val inTab = inTab(item)
            val texts = when (item) {
                // The ZIM name too, so "stackoverflow" finds Stack Overflow.
                is PackEntry -> listOf(item.title, item.description, item.zimName)
                is BookEntry -> listOf(item.title, item.author, item.description)
                else -> listOf(item.title)
            }
            return inTab && (needle.isEmpty() || texts.any { it.contains(needle, ignoreCase = true) })
        }
        val categories = if (!kiwix || current == null || inDomain || familyView != null || subjectsSet != null) emptyList() else
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
            if (kiwix) {
                // One row per pack, whatever its flavours (Plan KB Decision 8): chips, search and Recommended work on them.
                val free = volumeRows.firstOrNull { it.path == s.selectedPath }?.freeBytes ?: volumeRows.firstOrNull()?.freeBytes
                // Search: every word, in any order, anywhere in a pack's words; title hits first (Plan KB Decision 11).
                // Plan WF: a set (Wikipedia, Wikivoyage) is one row; its subjects leave the list for Add a subject until a
                // search or a domain looks for them, but for WikiMed, Medicine's essential.
                val subjectsAway = needle.isEmpty() && !inDomain && familyView == null && subjectsSet == null
                val groups = KiwixList.group(
                    current.packs.filter { inList(it) && (chosen == null || it.category == chosen) }, free, sets = true,
                ).filter { group -> group.flavours.any(::inTab) && KiwixList.matches(group, words) }
                    .filterNot { subjectsAway && it.default.isSubject && it.default.zimName !in OWN_ROW_SUBJECTS }
                    .sortedByDescending { KiwixList.titleMatches(it, words) }
                if (subjectsSet != null) {
                    // Add a subject's list: A–Z, for the letter strip, each saying what it adds, named without "by Wikipedia".
                    val setTitle = setGroup(subjectsSet)?.title ?: "Wikipedia"
                    fun subjectTitle(title: String) = title.removeSuffix(" by $setTitle").removeSuffix(" ($setTitle)").trim()
                    val rows = groups.map { g -> groupRow(g).let { it.copy(title = subjectTitle(it.title)) } }
                        .sortedByShelfKey({ app.granger.core.library.ShelfKey.of(it.title) })
                    if (rows.isNotEmpty()) add(Section(SUBJECTS, rows))
                    return@buildList
                }
                if (familyView != null) {
                    // A folded family's own list: its packs A–Z, for the letter strip.
                    val rows = groups.sortedByShelfKey({ app.granger.core.library.ShelfKey.of(it.title) }).map(::groupRow)
                    if (rows.isNotEmpty()) add(Section(familyView.label, rows))
                    return@buildList
                }
                val (featured, rest) = if (inDomain) {
                    val domain = current.readiness!!.domains.first { it.id == domainId }
                    val tierIds = (domain.essential + domain.comprehensive).distinct()
                    val byFlavour = groups.flatMap { g -> g.flavours.map { it.id to g } }.toMap()
                    val tier = tierIds.mapNotNull { byFlavour[it] }.distinct()
                        .sortedBy { g -> g.flavours.any { status(it) == ItemStatus.Installed } }
                    tier to groups.filterNot { it in tier }
                } else {
                    groups.partition { chosen == null && it.featured }
                }
                if (featured.isNotEmpty()) add(Section(RECOMMENDED, featured.map(::groupRow)))
                // The big families fold to one row each, in their category, until a search looks inside (Decision 10).
                val foldFamilies = needle.isEmpty() && !inDomain
                rest.groupBy { it.default.category }
                    .toList()
                    .sortedWith(compareBy({ categoryRank(it.first) }, { it.first }))
                    .forEach { (category, entries) ->
                        val familyRows = if (!foldFamilies) emptyList() else entries
                            .mapNotNull { g -> KiwixList.folded(g.default.family) }.distinct()
                            .map { f -> familyRow(f, entries.count { it.default.family == f.family }) }
                        val loose = if (!foldFamilies) entries else entries.filter { KiwixList.folded(it.default.family) == null }
                        val total = familyRows.size + loose.size
                        val open = !folds || category in s.expanded || total <= FOLDED_ROWS
                        val rows = familyRows + (if (open) loose else loose.take((FOLDED_ROWS - familyRows.size).coerceAtLeast(0))).map(::groupRow)
                        add(Section(category, rows, total, foldable = folds && total > FOLDED_ROWS))
                    }
                return@buildList
            }
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
            if (kiwixBooks) {
                // Kiwix libraries (Plan KB Decision 7): the nautilus packs A–Z, each with its size.
                if (packs.isNotEmpty()) add(Section(LIBRARIES, packs.sortedBy { it.title.lowercase() }.map(::row)))
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
            sizeCheck = s.sizeCheck,
            familyTitle = familyView?.label ?: subjectsSet?.let { set -> "${setGroup(set)?.title ?: "Wikipedia"} subjects" },
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
        const val LIBRARIES = "Libraries"
        /** A subjects list's family token: "subjects:<set id>:<slice ZIM name>" (Plan WF Decision 4). */
        const val SUBJECTS_PREFIX = "subjects:"
        /** Subjects that keep their own row in the Kiwix list (Plan WF Decision 4): WikiMed, Medicine's essential. */
        val OWN_ROW_SUBJECTS = setOf("wikipedia_en_medicine")
        private val PICTURES = listOf("maxi", "nopic", "mini")
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
