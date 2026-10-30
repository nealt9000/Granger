package app.granger.core.downloads

import java.io.File
import kotlin.math.roundToLong

/** What the queue is doing this moment, in memory only: the file downloading, how far, and how fast. */
data class QueueProgress(
    val itemId: String? = null,
    val hash: String? = null,
    /** Fresher than the record's bytesDone, which is written at most once a second. */
    val bytesDone: Long = 0,
    val bytesPerSecond: Long? = null,
)

/** [QueueProgress] with the queue's settings and the network: everything [DownloadQueueSummary] needs beyond the records. */
data class DownloadLive(
    val progress: QueueProgress = QueueProgress(),
    val paused: Boolean = false,
    /** Where this round of downloads began (Plan DL Decision 6). */
    val batchStartedAt: Long = 0,
    /** Whether the phone is on Wi-Fi (an unmetered network). */
    val unmetered: Boolean = true,
)

/** Why a waiting item isn't downloading: one reason each (Plan DL Decision 4). */
sealed interface WaitReason {
    data object Paused : WaitReason
    data object WaitingForWifi : WaitReason

    /** [attempt] is the try that comes next, of [MAX_ATTEMPTS]. */
    data class Retrying(val at: Long, val attempt: Int) : WaitReason

    data class NeedsSpace(val bytes: Long) : WaitReason

    /** 1 is next. */
    data class InLine(val place: Int) : WaitReason
}

data class WaitingItem(val record: DownloadRecord, val reason: WaitReason)

/**
 * The queue as Downloads, Home and the notification show it. The round is everything queued since [DownloadLive.batchStartedAt]
 * that is still to come or finished since; vectors add their bytes but aren't counted as files.
 */
data class QueueSummary(
    val current: DownloadRecord? = null,
    val currentBytesDone: Long = 0,
    val waiting: List<WaitingItem> = emptyList(),
    /** Every failed record, in queue order. */
    val stopped: List<DownloadRecord> = emptyList(),
    val files: Int = 0,
    val filesDone: Int = 0,
    val bytes: Long = 0,
    val bytesDone: Long = 0,
    val bytesToGo: Long = 0,
    val bytesPerSecond: Long? = null,
    val timeLeftMillis: Long? = null,
    val paused: Boolean = false,
) {
    /** Something is still to come: the round isn't finished. */
    val isActive: Boolean get() = current != null || waiting.isNotEmpty()

    val fraction: Float get() = if (bytes > 0) (bytesDone.toFloat() / bytes).coerceIn(0f, 1f) else 0f
}

object DownloadQueueSummary {
    /**
     * [records] in queue order (as [DownloadDao.observeAll] gives them); [freeBytes] maps a volume's root path to its
     * free bytes, for "Needs more space".
     */
    fun of(records: List<DownloadRecord>, live: DownloadLive, now: Long, freeBytes: Map<String, Long> = emptyMap()): QueueSummary {
        val active = records.filter { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }
        val p = live.progress
        val current = active.firstOrNull { it.itemId == p.itemId && it.hash == p.hash }
            ?: active.firstOrNull { it.state == DownloadState.RUNNING }.takeUnless { live.paused }
        fun done(r: DownloadRecord) = if (r === current && p.itemId == r.itemId) maxOf(p.bytesDone, r.bytesDone) else r.bytesDone

        // Space: what each volume must still take, in queue order, the running file first.
        val owed = HashMap<String, Long>()
        fun rootOf(r: DownloadRecord) = freeBytes.keys.firstOrNull { r.filePath.startsWith(it.trimEnd(File.separatorChar) + File.separator) }
        current?.let { c -> rootOf(c)?.let { owed[it] = c.sizeBytes - done(c) } }
        var place = 0
        val waiting = active.filter { it !== current }.map { r ->
            val root = rootOf(r)
            val needs = root?.let {
                val total = (owed[it] ?: 0) + r.sizeBytes - r.bytesDone
                owed[it] = total
                total + FileDownloader.DEFAULT_RESERVE_BYTES - freeBytes.getValue(it)
            } ?: 0
            val reason = when {
                live.paused -> WaitReason.Paused
                r.wifiOnly && !live.unmetered -> WaitReason.WaitingForWifi
                (r.retryAt ?: 0) > now -> WaitReason.Retrying(r.retryAt!!, r.attempts + 1)
                needs > 0 -> WaitReason.NeedsSpace(needs)
                else -> WaitReason.InLine(++place)
            }
            WaitingItem(r, reason)
        }

        // Before any round was stored (an update mid-download), the round starts with the oldest waiting record.
        val since = live.batchStartedAt.takeIf { it > 0 } ?: active.minOfOrNull { it.createdAt } ?: 0
        val round = if (active.isEmpty()) emptyList() else records.filter {
            it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING ||
                (it.updatedAt >= since && (it.state == DownloadState.INSTALLED || it.state == DownloadState.FAILED))
        }
        val files = round.filter { it.kind != ItemKind.VECTORS }
        val bytesToGo = active.sumOf { (it.sizeBytes - done(it)).coerceAtLeast(0) }
        val rate = p.bytesPerSecond?.takeIf { it > 0 && !live.paused }
        return QueueSummary(
            current = current,
            currentBytesDone = current?.let(::done) ?: 0,
            waiting = waiting,
            stopped = records.filter { it.state == DownloadState.FAILED },
            files = files.size,
            filesDone = files.count { it.state == DownloadState.INSTALLED },
            bytes = round.sumOf { it.sizeBytes },
            bytesDone = round.sumOf { if (it.state == DownloadState.INSTALLED) it.sizeBytes else done(it) },
            bytesToGo = bytesToGo,
            bytesPerSecond = rate,
            timeLeftMillis = rate?.takeIf { active.isNotEmpty() }?.let { bytesToGo * 1000 / it },
            paused = live.paused,
        )
    }
}

/** Plain words for the queue's figures, shared by the screens and the notification. */
object QueueText {
    /** Rounded so it doesn't jump about: "about 25 min", "about 3 h". */
    fun timeLeft(millis: Long): String {
        val minutes = millis / 60_000.0
        return when {
            minutes < 1 -> "less than a minute"
            minutes < 10 -> "about ${minutes.roundToLong().coerceAtLeast(1)} min"
            minutes < 55 -> "about ${(minutes / 5).roundToLong() * 5} min"
            minutes < 47.5 * 60 -> "about ${(minutes / 60).roundToLong().coerceAtLeast(1)} h"
            else -> "about ${(minutes / 1440).roundToLong()} days"
        }
    }

    /** "in 40 s", "in 2 min", "in 3 h". */
    fun inTime(millis: Long): String {
        val s = (millis / 1000).coerceAtLeast(1)
        return when {
            s < 60 -> "in $s s"
            s < 3600 -> "in ${(s + 30) / 60} min"
            else -> "in ${(s + 1800) / 3600} h"
        }
    }

    /** 1 is "Next", then "2nd in line", "3rd in line", "4th in line", "11th in line"… */
    fun place(n: Int): String {
        if (n <= 1) return "Next"
        val suffix = when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$n$suffix in line"
    }
}
