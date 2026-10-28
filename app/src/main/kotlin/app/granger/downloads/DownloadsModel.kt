package app.granger.downloads

import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.VectorDownloads
import app.granger.core.downloads.isActive
import app.granger.core.library.Book
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.formatBytes
import app.granger.home.HomeViewModel
import java.io.File

/** Where a row stands: the worst of its files decides (a failure, then one running, then one waiting). */
enum class RowState { RUNNING, QUEUED, FAILED, DONE }

/** What a finished row opens: an installed pack or book. */
sealed interface OpenTarget {
    data class Pack(val packId: String) : OpenTarget
    data class Book(val bookId: String) : OpenTarget
}

/** One file of a group row, shown when the row is opened. */
data class DownloadFile(val name: String, val state: DownloadState)

/**
 * One row of Downloads: one item, or one group (onboarding's setup, an album) folded into a row (Plan U3b Decision 1).
 * An item's search vectors are part of its row ([withIndex]); when the item itself isn't shown, the row is its index
 * alone ([indexOnly]), still under the item's title.
 */
data class DownloadRow(
    val key: String,
    val title: String,
    val state: RowState,
    val bytesDone: Long,
    val bytes: Long,
    /** The group Cancel and Retry act on, for a group row. */
    val groupId: String? = null,
    /** A group's files, in queue order; empty for an item. */
    val files: List<DownloadFile> = emptyList(),
    val failed: Int = 0,
    /** The items outside a group that Cancel and Retry act on: the item and its vectors. */
    val itemIds: List<String> = emptyList(),
    val withIndex: Boolean = false,
    val indexOnly: Boolean = false,
    val error: String? = null,
    val wifiOnly: Boolean = false,
    val open: OpenTarget? = null,
    /** When it joined the queue: its oldest unfinished file. */
    val queuedAt: Long = 0,
    /** When its last file changed; for a finished row, when it finished. */
    val finishedAt: Long = 0,
) {
    val isGroup: Boolean get() = groupId != null
    val fraction: Float get() = if (bytes > 0) (bytesDone.toFloat() / bytes).coerceIn(0f, 1f) else 0f
}

/** Downloads' three sections. */
data class DownloadsUi(
    val now: List<DownloadRow> = emptyList(),
    val stopped: List<DownloadRow> = emptyList(),
    val doneToday: List<DownloadRow> = emptyList(),
) {
    val isEmpty: Boolean get() = now.isEmpty() && stopped.isEmpty() && doneToday.isEmpty()

    /** The Library header's badge: what's still coming or needs a tap. */
    val badge: Int get() = now.size + stopped.size
}

object DownloadsModel {
    /** What a group from onboarding is called (its ids are `onboarding:<epoch>`). */
    const val SETUP_TITLE = "Setting up"

    /**
     * Builds the sections from every download record. A record counts while it's unfinished, or once it was installed
     * at or after [since] (midnight today). [packs] and [books] give an installed item its library title and what Open
     * opens.
     */
    fun of(records: List<DownloadRecord>, since: Long, packs: List<Pack> = emptyList(), books: List<Book> = emptyList()): DownloadsUi {
        fun shown(r: DownloadRecord) = r.isActive || r.updatedAt >= since
        val packByPath = packs.associateBy { it.filePath }
        val bookByPath = books.associateBy { it.filePath }
        fun titleOf(r: DownloadRecord) = packByPath[r.filePath]?.title ?: bookByPath[r.filePath]?.title ?: r.title

        val (vectors, own) = records.partition { it.kind == ItemKind.VECTORS }
        // Each item's or group's records, in the order they came (the ledger's, oldest first).
        val units = LinkedHashMap<String, MutableList<DownloadRecord>>()
        for (r in own) {
            val group = r.groupId
            if (group != null) units.getOrPut(groupKey(group)) { mutableListOf() } += r
            else if (shown(r)) units.getOrPut(itemKey(r.itemId)) { mutableListOf() } += r
        }
        val unitOfItem = own.associate { it.itemId to (it.groupId?.let(::groupKey) ?: itemKey(it.itemId)) }
        val shownUnits = units.filterValues { list -> list.any(::shown) }.keys

        val folded = HashMap<String, MutableList<DownloadRecord>>()
        val indexOnly = LinkedHashMap<String, MutableList<DownloadRecord>>()
        for (v in vectors.filter(::shown)) {
            val parentUnit = VectorDownloads.parentOf(v.itemId)?.let(unitOfItem::get)
            if (parentUnit != null && parentUnit in shownUnits) folded.getOrPut(parentUnit) { mutableListOf() } += v
            else indexOnly.getOrPut(v.itemId) { mutableListOf() } += v
        }

        val rows = shownUnits.map { key ->
            val main = units.getValue(key)
            val group = main.first().groupId
            val title = when {
                group == null -> titleOf(main.first())
                group.startsWith(HomeViewModel.SETUP_GROUP) -> SETUP_TITLE
                else -> main.first().title
            }
            val vecs = folded[key].orEmpty()
            row(key, title, main, vecs, group, open = if (group == null) openOf(main.first(), packByPath, bookByPath) else null)
        } + indexOnly.map { (itemId, vecs) ->
            val parent = VectorDownloads.parentOf(itemId)?.let { p -> own.firstOrNull { it.itemId == p } }
            row(
                indexKey(itemId), parent?.let(::titleOf) ?: vecs.first().title, emptyList(), vecs, group = null,
                open = parent?.let { openOf(it, packByPath, bookByPath) },
            )
        }
        val byQueue = compareBy<DownloadRow> { it.queuedAt }
        return DownloadsUi(
            now = rows.filter { it.state == RowState.RUNNING }.sortedWith(byQueue) +
                rows.filter { it.state == RowState.QUEUED }.sortedWith(byQueue),
            stopped = rows.filter { it.state == RowState.FAILED }.sortedWith(byQueue),
            doneToday = rows.filter { it.state == RowState.DONE }.sortedByDescending { it.finishedAt },
        )
    }

    private fun groupKey(groupId: String) = "group:$groupId"
    private fun itemKey(itemId: String) = "item:$itemId"
    private fun indexKey(itemId: String) = "index:$itemId"

    private fun row(
        key: String, title: String, main: List<DownloadRecord>, vecs: List<DownloadRecord>, group: String?, open: OpenTarget?,
    ): DownloadRow {
        val all = main + vecs
        val state = when {
            all.any { it.state == DownloadState.FAILED } -> RowState.FAILED
            all.any { it.state == DownloadState.RUNNING } -> RowState.RUNNING
            all.any { it.state == DownloadState.QUEUED } -> RowState.QUEUED
            else -> RowState.DONE
        }
        return DownloadRow(
            key = key,
            title = title,
            state = state,
            bytesDone = all.sumOf { if (it.state == DownloadState.INSTALLED) it.sizeBytes else it.bytesDone },
            bytes = all.sumOf { it.sizeBytes },
            groupId = group,
            files = if (group == null) emptyList() else main.map { DownloadFile(fileName(it), it.state) },
            failed = all.count { it.state == DownloadState.FAILED },
            itemIds = ((if (group == null) main else emptyList()) + vecs).map { it.itemId }.distinct(),
            withIndex = main.isNotEmpty() && vecs.isNotEmpty(),
            indexOnly = main.isEmpty(),
            error = all.firstOrNull { it.state == DownloadState.FAILED }?.error,
            wifiOnly = all.any { it.isActive && it.wifiOnly },
            open = if (state == RowState.DONE) open else null,
            queuedAt = all.filter { it.isActive }.minOfOrNull { it.createdAt } ?: Long.MAX_VALUE,
            finishedAt = all.maxOf { it.updatedAt },
        )
    }

    /** An album's files all carry the album's title: they are told apart by name. */
    private fun fileName(r: DownloadRecord) = if (r.kind == ItemKind.AUDIO) File(r.filePath).name else r.title

    private fun openOf(r: DownloadRecord, packs: Map<String, Pack>, books: Map<String, Book>): OpenTarget? = when (r.kind) {
        ItemKind.PACK -> packs[r.filePath]?.takeIf { it.status == PackStatus.OK }?.let { OpenTarget.Pack(it.id) }
        ItemKind.BOOK -> books[r.filePath]?.takeIf { it.status == PackStatus.OK }?.let { OpenTarget.Book(it.id) }
        else -> null
    }
}

/** The line under a row's title: how far it is, or why it stopped. */
fun downloadsText(row: DownloadRow): String {
    val parts = mutableListOf<String>()
    if (row.indexOnly) parts += "Index for Ask"
    if (row.isGroup) parts += "${row.files.count { it.state == DownloadState.INSTALLED }} of ${row.files.size}"
    parts += when (row.state) {
        RowState.RUNNING -> "${formatBytes(row.bytesDone)} of ${formatBytes(row.bytes)}"
        RowState.QUEUED -> if (row.wifiOnly) "Waiting for Wi-Fi" else "Queued"
        RowState.FAILED -> when {
            row.isGroup -> "${row.failed} failed"
            else -> row.error ?: "Failed"
        }
        RowState.DONE -> formatBytes(row.bytes)
    }
    val line = parts.joinToString(" · ")
    return if (row.withIndex) "$line + index for Ask" else line
}
