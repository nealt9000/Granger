package app.granger.downloads

import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.MAX_ATTEMPTS
import app.granger.core.downloads.QueueSummary
import app.granger.core.downloads.QueueText
import app.granger.core.downloads.VectorDownloads
import app.granger.core.downloads.WaitReason
import app.granger.core.downloads.isActive
import app.granger.core.downloads.isFromCatalog
import app.granger.core.downloads.isGone
import app.granger.core.library.Book
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.formatBytes
import app.granger.home.HomeViewModel
import java.io.File

/** Where a row stands. */
enum class RowState { RUNNING, QUEUED, FAILED, DONE }

/** What a finished row opens: an installed pack or book. */
sealed interface OpenTarget {
    data class Pack(val packId: String) : OpenTarget
    data class Book(val bookId: String) : OpenTarget
}

/**
 * One row of Downloads (Plan DL Decision 8): one file, with its search vectors folded in ([withIndex]); when the item
 * itself isn't shown, the row is its index alone ([indexOnly]), still under the item's title. A group's files are rows
 * of their own, labelled by the group ([groupLabel]); only Done today folds a group into one row ([groupFiles]).
 */
data class DownloadRow(
    val key: String,
    val title: String,
    val state: RowState,
    val bytesDone: Long,
    val bytes: Long,
    /** The group the file belongs to, for Cancel all; null outside a group. */
    val groupId: String? = null,
    /** "Setting up", or the album's title. */
    val groupLabel: String? = null,
    /** What Download next, Cancel, Retry and Remove act on: the item and its vectors. */
    val itemIds: List<String> = emptyList(),
    val withIndex: Boolean = false,
    val indexOnly: Boolean = false,
    /** Why a waiting row waits (Plan DL Decision 4). */
    val reason: WaitReason? = null,
    val error: String? = null,
    val open: OpenTarget? = null,
    /** Every address answered 404 or 410: Retry won't help, so the row offers Remove (Plan DL Decision 10). */
    val gone: Boolean = false,
    /** A catalog item that a newer catalog no longer lists, still waiting or stopped: it offers Remove. */
    val notInCatalog: Boolean = false,
    /** Done today, a group folded into one row: how many of its files landed. */
    val groupFiles: Int = 0,
    /** When its last file changed; for a finished row, when it finished. */
    val finishedAt: Long = 0,
) {
    val isGroup: Boolean get() = groupFiles > 0

    /** Remove instead of Retry: the file can't be fetched again. */
    val removeOnly: Boolean get() = gone || notInCatalog

    val fraction: Float get() = if (bytes > 0) (bytesDone.toFloat() / bytes).coerceIn(0f, 1f) else 0f
}

/** The summary header: the round's bar and figures, the speed, Pause all or Resume, and the storage line. */
data class DownloadsHeader(
    val paused: Boolean,
    val files: Int,
    val filesDone: Int,
    val bytes: Long,
    val bytesDone: Long,
    val waiting: Int,
    val bytesPerSecond: Long? = null,
    val timeLeftMillis: Long? = null,
    val bytesToGo: Long = 0,
    val freeBytes: Long? = null,
    val volumeLabel: String? = null,
) {
    val fraction: Float get() = if (bytes > 0) (bytesDone.toFloat() / bytes).coerceIn(0f, 1f) else 0f
}

/** Downloads' sections (Plan DL Decision 8). */
data class DownloadsUi(
    val header: DownloadsHeader? = null,
    val now: DownloadRow? = null,
    val upNext: List<DownloadRow> = emptyList(),
    val stopped: List<DownloadRow> = emptyList(),
    val doneToday: List<DownloadRow> = emptyList(),
) {
    val isEmpty: Boolean get() = header == null && now == null && upNext.isEmpty() && stopped.isEmpty() && doneToday.isEmpty()

    /** Stopped rows Retry all queues again: not the ones that can only be removed. */
    val retriable: List<DownloadRow> get() = stopped.filterNot { it.removeOnly }
}

/** Where a waiting row's free space is counted: a volume's root, its label and its free bytes. */
data class VolumeSpace(val root: String, val label: String, val freeBytes: Long)

object DownloadsModel {
    /** What a group from onboarding is called (its ids are `onboarding:<epoch>`). */
    const val SETUP_TITLE = "Setting up"

    /** Up next shows this many rows before "Show all n". */
    const val UP_NEXT_SHOWN = 20

    /**
     * Builds the page from every download record (in queue order) and the queue's [summary]. A record installed at or
     * after [since] (midnight today) is Done today. [packs] and [books] give an installed item its library title and
     * what Open opens; [catalogIds] is every item the current catalog lists, null while there is none.
     */
    fun of(
        records: List<DownloadRecord>,
        summary: QueueSummary,
        since: Long,
        packs: List<Pack> = emptyList(),
        books: List<Book> = emptyList(),
        catalogIds: Set<String>? = null,
        volumes: List<VolumeSpace> = emptyList(),
    ): DownloadsUi {
        val packByPath = packs.associateBy { it.filePath }
        val bookByPath = books.associateBy { it.filePath }
        val byItem = records.filter { it.kind != ItemKind.VECTORS }.associateBy { it.itemId }
        fun titleOf(r: DownloadRecord): String {
            if (r.kind == ItemKind.VECTORS) return VectorDownloads.parentOf(r.itemId)?.let(byItem::get)?.let(::titleOf) ?: r.title
            return packByPath[r.filePath]?.title ?: bookByPath[r.filePath]?.title ?: fileName(r)
        }
        fun notInCatalog(r: DownloadRecord) = catalogIds != null && r.isActive && r.isFromCatalog && r.itemId !in catalogIds

        // A vectors record folds into its parent's row when that row is in the same section.
        fun rows(list: List<DownloadRecord>, state: RowState, reasons: Map<DownloadRecord, WaitReason> = emptyMap()): List<DownloadRow> {
            val shown = list.filter { it.kind != ItemKind.VECTORS }.mapTo(HashSet()) { it.itemId }
            val folded = list.filter { r -> r.kind == ItemKind.VECTORS && VectorDownloads.parentOf(r.itemId) in shown }
                .groupBy { VectorDownloads.parentOf(it.itemId)!! }
            val foldedAway = folded.values.flatten().toHashSet()
            return list.filter { it !in foldedAway }.map { r ->
                val vecs = if (r.kind == ItemKind.VECTORS) emptyList() else folded[r.itemId].orEmpty()
                val all = listOf(r) + vecs
                DownloadRow(
                    key = (if (r.kind == ItemKind.VECTORS) "index:" else "item:") + r.itemId,
                    title = titleOf(r),
                    state = state,
                    bytesDone = all.sumOf { if (it.state == DownloadState.INSTALLED) it.sizeBytes else it.bytesDone },
                    bytes = all.sumOf { it.sizeBytes },
                    groupId = r.groupId,
                    groupLabel = r.groupId?.let { groupLabel(it, r) },
                    itemIds = all.map { it.itemId },
                    withIndex = vecs.isNotEmpty(),
                    indexOnly = r.kind == ItemKind.VECTORS,
                    reason = reasons[r],
                    error = all.firstOrNull { it.state == DownloadState.FAILED }?.error,
                    open = if (state == RowState.DONE) openOf(r, packByPath, bookByPath) else null,
                    gone = r.isGone,
                    notInCatalog = notInCatalog(r),
                    finishedAt = all.maxOf { it.updatedAt },
                )
            }
        }

        val current = summary.current
        val now = current?.let { c ->
            rows(listOf(c), RowState.RUNNING).single().copy(bytesDone = summary.currentBytesDone)
        }
        val reasons = summary.waiting.associate { it.record to it.reason }
        val upNext = rows(summary.waiting.map { it.record }, RowState.QUEUED, reasons)
        val stopped = rows(summary.stopped, RowState.FAILED)

        // Done today: an item's row, or a group folded into one, with its files' vectors.
        val done = records.filter { it.state == DownloadState.INSTALLED && it.updatedAt >= since }
        val groupOf = done.filter { it.groupId != null }.associate { it.itemId to it.groupId!! }
        val (groupVectors, singles) = done.filter { it.groupId == null }
            .partition { it.kind == ItemKind.VECTORS && VectorDownloads.parentOf(it.itemId) in groupOf }
        val vectorsOfGroup = groupVectors.groupBy { groupOf.getValue(VectorDownloads.parentOf(it.itemId)!!) }
        val doneToday = rows(singles, RowState.DONE) +
            done.filter { it.groupId != null }.groupBy { it.groupId!! }.map { (group, files) ->
                val all = files + vectorsOfGroup[group].orEmpty()
                DownloadRow(
                    key = "group:$group", title = groupLabel(group, files.first()), state = RowState.DONE,
                    bytesDone = all.sumOf { it.sizeBytes }, bytes = all.sumOf { it.sizeBytes }, groupId = group,
                    itemIds = files.map { it.itemId }, withIndex = all.size > files.size, groupFiles = files.size,
                    finishedAt = all.maxOf { it.updatedAt },
                )
            }

        val header = if (!summary.isActive) null else {
            val first = current ?: summary.waiting.firstOrNull()?.record
            val volume = first?.let { r -> volumes.firstOrNull { r.filePath.startsWith(it.root.trimEnd(File.separatorChar) + File.separator) } }
            DownloadsHeader(
                paused = summary.paused,
                files = summary.files,
                filesDone = summary.filesDone,
                bytes = summary.bytes,
                bytesDone = summary.bytesDone,
                waiting = summary.waiting.size,
                bytesPerSecond = summary.bytesPerSecond,
                timeLeftMillis = summary.timeLeftMillis,
                bytesToGo = summary.bytesToGo,
                freeBytes = volume?.freeBytes,
                volumeLabel = volume?.label,
            )
        }
        return DownloadsUi(
            header = header,
            now = now,
            upNext = upNext,
            stopped = stopped,
            doneToday = doneToday.sortedByDescending { it.finishedAt },
        )
    }

    /**
     * The Library header's badge: what's still coming or stopped, a group counted once (an onboarding round is one
     * thing coming, not 376).
     */
    fun badge(records: List<DownloadRecord>): Int =
        records.filter { it.isActive && it.kind != ItemKind.VECTORS }.distinctBy { it.groupId ?: it.itemId }.size

    fun groupLabel(groupId: String, r: DownloadRecord): String =
        if (groupId.startsWith(HomeViewModel.SETUP_GROUP)) SETUP_TITLE else r.title

    /** An album's files all carry the album's title: they are told apart by name. */
    private fun fileName(r: DownloadRecord) = if (r.kind == ItemKind.AUDIO) File(r.filePath).name else r.title

    private fun openOf(r: DownloadRecord, packs: Map<String, Pack>, books: Map<String, Book>): OpenTarget? = when (r.kind) {
        ItemKind.PACK -> packs[r.filePath]?.takeIf { it.status == PackStatus.OK }?.let { OpenTarget.Pack(it.id) }
        ItemKind.BOOK -> books[r.filePath]?.takeIf { it.status == PackStatus.OK }?.let { OpenTarget.Book(it.id) }
        else -> null
    }
}

/** Said of an item a newer catalog dropped (Plan DL Decision 10). */
const val NOT_IN_CATALOG = "No longer in the catalog"

/** A waiting row's reason, as it reads (Plan DL Decision 4). [now] is for "Trying again in 2 min". */
fun reasonText(reason: WaitReason, now: Long): String = when (reason) {
    WaitReason.Paused -> "Paused"
    WaitReason.WaitingForWifi -> "Waiting for Wi-Fi"
    is WaitReason.Retrying -> "Trying again ${QueueText.inTime(reason.at - now)} (attempt ${reason.attempt} of $MAX_ATTEMPTS)"
    is WaitReason.NeedsSpace -> "Needs ${formatBytes(reason.bytes)} more space"
    is WaitReason.InLine -> QueueText.place(reason.place)
}

/** "312 of 980 MB" once a file has begun, its size before. */
private fun sizeText(row: DownloadRow) =
    if (row.bytesDone > 0 && row.bytesDone < row.bytes) "${formatBytes(row.bytesDone)} of ${formatBytes(row.bytes)}" else formatBytes(row.bytes)

/** The line under a row's title: how far it is, why it waits, or why it stopped. [speed] is the running file's. */
fun downloadsText(row: DownloadRow, now: Long = System.currentTimeMillis(), speed: Long? = null): String {
    val parts = mutableListOf<String>()
    if (row.indexOnly) parts += "Index for Ask"
    when (row.state) {
        RowState.RUNNING -> {
            parts += "${formatBytes(row.bytesDone)} of ${formatBytes(row.bytes)}"
            speed?.let { parts += "${formatBytes(it)}/s" }
        }
        RowState.QUEUED -> {
            parts += sizeText(row)
            parts += if (row.notInCatalog) NOT_IN_CATALOG else row.reason?.let { reasonText(it, now) } ?: "Queued"
        }
        RowState.FAILED -> parts += when {
            row.notInCatalog && !row.gone -> NOT_IN_CATALOG
            else -> row.error ?: "Failed"
        }
        RowState.DONE -> {
            if (row.isGroup) parts += "${row.groupFiles} ${if (row.groupFiles == 1) "file" else "files"}"
            parts += formatBytes(row.bytes)
        }
    }
    val line = parts.joinToString(" · ")
    return if (row.withIndex) "$line + index for Ask" else line
}

/** The header's first line: "3 of 14 · 1.2 of 9.8 GB · about 25 min left", or "Paused · 11 waiting". */
fun headerText(h: DownloadsHeader): String {
    if (h.paused) return "Paused · ${h.waiting} waiting"
    val parts = mutableListOf("${h.filesDone} of ${h.files}", "${formatBytes(h.bytesDone)} of ${formatBytes(h.bytes)}")
    h.timeLeftMillis?.let { parts += "${QueueText.timeLeft(it)} left" }
    return parts.joinToString(" · ")
}

/** The storage line: "9.8 GB to go · 41 GB free on Phone". */
fun storageText(h: DownloadsHeader): String {
    val togo = "${formatBytes(h.bytesToGo)} to go"
    val free = h.freeBytes ?: return togo
    return "$togo · ${formatBytes(free)} free on ${h.volumeLabel ?: "this phone"}"
}
