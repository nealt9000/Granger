package app.granger.feedback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A past run that ended badly, as the system remembers it: a crash in native code or an app-not-responding. */
data class PastExit(val timestamp: Long, val kind: CrashKind, val description: String?, val trace: String?)

/**
 * [label] is the crash file's English, as a log; the screens name a kind in the phone's language. [DISK] is iOS's
 * MetricKit report of too many disk writes (Plan I7 Task 4): kept with the others, but not a close, so Home doesn't ask.
 */
enum class CrashKind(val label: String) { JAVA("Crash"), NATIVE("Native crash"), ANR("Not responding"), DISK("Too many disk writes") }

/** One saved report, newest first in [CrashRecords.list]; [path] is the file's (Android adds `file`). */
data class CrashReport(val path: String, val timestamp: Long, val kind: CrashKind, val summary: String)

/**
 * The crash log's folder (Plan I7 Task 1): small text files by name. Android's is a `File` folder, the iPhone's one
 * in Application Support. Names starting with a dot are the log's own marks.
 */
interface CrashStore {
    /** Every file's name in the folder, in any order; empty when there's no folder yet. */
    fun names(): List<String>
    /** The file's text; null when it doesn't exist. */
    fun read(name: String): String?
    /** Writes [text] to the file, making the folder first. */
    fun write(name: String, text: String)
    fun delete(name: String)
    /** The file's full path, for [CrashReport.path]. */
    fun pathOf(name: String): String
}

/**
 * The local crash log's rules (Plan F1 part 2 Decision 1, shared since Plan I7 Task 1): small text files in [store],
 * the newest [KEEP] kept, each capped at [MAX_BYTES]. Nothing here leaves the phone; Send feedback attaches them only
 * when the user sends. Android's `CrashLog` is a thin wrapper that adds its Java handler and the system's exit records.
 *
 * A crash caught as it happens is written with [record]. Crashes the process can't catch (native ones, ANRs, iOS's
 * MetricKit payloads) arrive on a later start through [recordExits]; a mark keeps each one from being recorded twice.
 */
class CrashRecords(
    private val store: CrashStore,
    private val facts: BuildFacts,
    private val now: () -> Long,
    /** A time as a report's first line names it: "2026-11-22 10:15:00 GMT", in the phone's zone. */
    private val stamp: (Long) -> String,
) {
    private val _notice = MutableStateFlow<CrashReport?>(null)

    /**
     * The newest report the user hasn't answered yet, for Home's "Granger closed unexpectedly" line (Decision 3).
     * Filled once the start has read past exits ([recordExits]); null after [acknowledge] or [clear].
     */
    val notice: StateFlow<CrashReport?> = _notice.asStateFlow()

    /** Records a crash of [kind] now, with [body] (the thread and the trace) under the header. */
    fun record(kind: CrashKind, body: String) {
        write(now(), kind, body)
    }

    /**
     * Records the exits newer than the last one recorded, oldest first; returns how many were new. The first read
     * (a new install, or storage cleared) records none and starts from now: the system keeps exits across a cleared
     * storage, and those crashes belong to the data that's gone (found on the Pixel 7, release 853).
     */
    fun recordExits(exits: List<PastExit>): Int {
        if (store.read(LAST_SEEN) == null) store.write(LAST_SEEN, now().toString())
        val lastSeen = store.read(LAST_SEEN)?.trim()?.toLongOrNull() ?: 0L
        val fresh = exits.filter { it.timestamp > lastSeen }.sortedBy { it.timestamp }
        for (exit in fresh) {
            val body = buildString {
                exit.description?.takeIf { it.isNotBlank() }?.let { append("Reason: ").append(it).append("\n\n") }
                append(exit.trace?.takeIf { it.isNotBlank() } ?: "(no trace kept by the system)")
            }
            write(exit.timestamp, exit.kind, body)
        }
        if (fresh.isNotEmpty()) store.write(LAST_SEEN, fresh.last().timestamp.toString())
        refreshNotice()
        return fresh.size
    }

    /** The user sent a report or said not now: Home stops asking until the next crash. */
    fun acknowledge() {
        list().firstOrNull { it.closed }?.let { store.write(ACKNOWLEDGED, it.timestamp.toString()) }
        refreshNotice()
    }

    /**
     * Home asks only about a crash in the build that's running: one from an older build was likely fixed by the update,
     * and asking about it builds later (an ANR from 1194 still showing on 1218, 2026-11-08) only nags. The report
     * stays in Settings' Send feedback.
     */
    fun refreshNotice() {
        val acknowledged = store.read(ACKNOWLEDGED)?.trim()?.toLongOrNull() ?: 0L
        _notice.value = list().firstOrNull { it.closed }?.takeIf { it.timestamp > acknowledged && fromThisBuild(it) }
    }

    /** A crash or a hang closed the app; a disk-write report didn't. */
    private val CrashReport.closed: Boolean get() = kind != CrashKind.DISK

    /** The report's header line (its second) names this build's version code: "Granger 0.1.1218 (1218) · …". */
    private fun fromThisBuild(report: CrashReport): Boolean =
        read(report)?.lineSequence()?.drop(1)?.firstOrNull()?.contains("(${facts.versionCode})") == true

    /** The saved reports, newest first. */
    fun list(): List<CrashReport> =
        store.names().filter { it.startsWith(PREFIX) && it.endsWith(".txt") }
            .mapNotNull { name -> parseName(name)?.let { (time, kind) -> CrashReport(store.pathOf(name), time, kind, summaryOf(name)) } }
            .sortedByDescending { it.timestamp }

    /** A report's whole text, for viewing and the attachment; null when it's gone. */
    fun read(report: CrashReport): String? = store.read(nameOf(report))

    fun clear() {
        list().forEach { store.delete(nameOf(it)) }
        refreshNotice()
    }

    private fun write(timestamp: Long, kind: CrashKind, body: String) {
        val text = "${kind.label} at ${stamp(timestamp)}\n${facts.header()}\n\n$body"
        store.write("$PREFIX${timestamp.toString().padStart(15, '0')}-${kind.name.lowercase()}.txt", cap(text))
        list().drop(KEEP).forEach { store.delete(nameOf(it)) }
    }

    private fun nameOf(report: CrashReport) = report.path.substringAfterLast('/')

    private fun parseName(name: String): Pair<Long, CrashKind>? {
        val parts = name.removePrefix(PREFIX).removeSuffix(".txt").split('-')
        val time = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val kind = CrashKind.entries.firstOrNull { it.name.lowercase() == parts.getOrNull(1) } ?: return null
        return time to kind
    }

    /**
     * The line of the trace that says what went wrong, for the list in Settings: a native crash's abort message when
     * the tombstone has one (`Reason: crash` says nothing), else the first line of the trace.
     */
    private fun summaryOf(name: String): String {
        val body = store.read(name).orEmpty().lineSequence().drop(3).map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("Thread:") }.toList()
        return (body.firstNotNullOfOrNull(::abortMessage) ?: body.firstOrNull() ?: "").take(160)
    }

    companion object {
        const val KEEP = 5
        const val MAX_BYTES = 64 * 1024
        private const val PREFIX = "crash-"
        private const val LAST_SEEN = ".last-exit"
        private const val ACKNOWLEDGED = ".acknowledged"

        /** [text] cut to [MAX_BYTES] of UTF-8, with a line saying so. */
        fun cap(text: String): String {
            val bytes = text.encodeToByteArray()
            return if (bytes.size <= MAX_BYTES) text else bytes.decodeToString(0, MAX_BYTES) + "\n… (cut at ${MAX_BYTES / 1024} KB)"
        }

        /** `[FATAL:file.cc:93] Uncaught exception` or `Abort message: '…'` as the message alone; null for other lines. */
        fun abortMessage(line: String): String? = when {
            line.startsWith("[FATAL:") -> line.substringAfter("] ", "").takeIf { it.isNotBlank() }
            line.startsWith("Abort message:") -> line.removePrefix("Abort message:").trim().trim('\'').takeIf { it.isNotBlank() }
            else -> null
        }

        /** Runs of four or more printable characters, one per line: what a person can read in a binary tombstone. */
        fun readableRuns(bytes: ByteArray): String {
            val out = StringBuilder()
            val run = StringBuilder()
            fun flush() {
                if (run.length >= 4) out.append(run).append('\n')
                run.clear()
            }
            for (b in bytes) {
                val c = b.toInt() and 0xff
                if (c in 0x20..0x7e) run.append(c.toChar()) else flush()
            }
            flush()
            return out.toString()
        }
    }
}
