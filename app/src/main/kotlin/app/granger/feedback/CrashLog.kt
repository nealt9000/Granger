package app.granger.feedback

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import app.granger.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Who made a report: the build and the phone, written at the top of each one (Plan F1 part 2 Decision 1). */
data class BuildFacts(val versionName: String, val versionCode: Int, val device: String, val android: String) {
    fun header(): String = "Granger $versionName ($versionCode) · $device · Android $android"

    companion object {
        fun ofThisPhone() = BuildFacts(
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            "${Build.MANUFACTURER} ${Build.MODEL}",
            "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
    }
}

/** A past run that ended badly, as the system remembers it: a crash in native code or an app-not-responding. */
data class PastExit(val timestamp: Long, val kind: CrashKind, val description: String?, val trace: String?)

enum class CrashKind(val label: String) { JAVA("Crash"), NATIVE("Native crash"), ANR("Not responding") }

/** One saved report, newest first in [CrashLog.list]. */
data class CrashReport(val file: File, val timestamp: Long, val kind: CrashKind, val summary: String)

/**
 * The local crash log (Plan F1 part 2 Decision 1): small text files in [dir], the newest [KEEP] kept, each capped at
 * [MAX_BYTES]. Nothing here leaves the phone; Send feedback attaches them only when the user sends.
 *
 * Java crashes come through [handler]. Native crashes and ANRs never reach a Java handler, so the next start reads them
 * from the system ([recordExits]); [lastSeenFile] keeps each one from being recorded twice.
 */
class CrashLog(
    private val dir: File,
    private val facts: BuildFacts,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lastSeenFile = File(dir, ".last-exit")

    /** Records [error] on [thread], then hands it to [previous] so Android still ends the process the usual way. */
    fun handler(previous: Thread.UncaughtExceptionHandler?) = Thread.UncaughtExceptionHandler { thread, error ->
        runCatching { record(thread, error) }
        previous?.uncaughtException(thread, error)
    }

    fun record(thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        write(now(), CrashKind.JAVA, "Thread: ${thread.name}\n\n$trace")
    }

    /** Records the exits newer than the last one recorded, oldest first; returns how many were new. */
    fun recordExits(exits: List<PastExit>): Int {
        val lastSeen = runCatching { lastSeenFile.readText().trim().toLong() }.getOrDefault(0L)
        val fresh = exits.filter { it.timestamp > lastSeen }.sortedBy { it.timestamp }
        for (exit in fresh) {
            val body = buildString {
                exit.description?.takeIf { it.isNotBlank() }?.let { append("Reason: ").append(it).append("\n\n") }
                append(exit.trace?.takeIf { it.isNotBlank() } ?: "(no trace kept by the system)")
            }
            write(exit.timestamp, exit.kind, body)
        }
        if (fresh.isNotEmpty()) {
            dir.mkdirs()
            lastSeenFile.writeText(fresh.last().timestamp.toString())
        }
        return fresh.size
    }

    fun list(): List<CrashReport> =
        (dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".txt") } ?: emptyArray())
            .mapNotNull { file -> parseName(file)?.let { (time, kind) -> CrashReport(file, time, kind, summaryOf(file)) } }
            .sortedByDescending { it.timestamp }

    fun clear() {
        list().forEach { it.file.delete() }
    }

    private fun write(timestamp: Long, kind: CrashKind, body: String) {
        dir.mkdirs()
        val text = "${kind.label} at ${stamp(timestamp)}\n${facts.header()}\n\n$body"
        val bytes = text.toByteArray()
        val capped = if (bytes.size <= MAX_BYTES) text else String(bytes, 0, MAX_BYTES) + "\n… (cut at ${MAX_BYTES / 1024} KB)"
        File(dir, "$PREFIX${timestamp.toString().padStart(15, '0')}-${kind.name.lowercase()}.txt").writeText(capped)
        list().drop(KEEP).forEach { it.file.delete() }
    }

    private fun parseName(file: File): Pair<Long, CrashKind>? {
        val parts = file.name.removePrefix(PREFIX).removeSuffix(".txt").split('-')
        val time = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val kind = CrashKind.entries.firstOrNull { it.name.lowercase() == parts.getOrNull(1) } ?: return null
        return time to kind
    }

    /** The first line of the trace that says what went wrong, for the list in Settings. */
    private fun summaryOf(file: File): String =
        file.useLines { lines ->
            lines.drop(3).map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("Thread:") } ?: ""
        }.take(160)

    companion object {
        const val KEEP = 5
        const val MAX_BYTES = 64 * 1024
        private const val PREFIX = "crash-"

        fun stamp(timestamp: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(timestamp))

        /**
         * The system's record of this app's recent native crashes and ANRs (Android 11+; empty before). A native
         * crash's trace is a binary tombstone: its readable runs (the abort message, frame names) are kept.
         */
        fun pastExits(context: Context): List<PastExit> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
            val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
            return am.getHistoricalProcessExitReasons(context.packageName, 0, 16).mapNotNull { info ->
                val kind = when (info.reason) {
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> CrashKind.NATIVE
                    ApplicationExitInfo.REASON_ANR -> CrashKind.ANR
                    else -> return@mapNotNull null
                }
                val raw = runCatching { info.traceInputStream?.use { readAtMost(it, MAX_BYTES * 4) } }.getOrNull()
                val trace = raw?.let { if (kind == CrashKind.NATIVE) readableRuns(it) else String(it) }
                PastExit(info.timestamp, kind, info.description, trace)
            }
        }

        /** Up to [limit] bytes of [input] (InputStream.readNBytes needs API 33). */
        fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() < limit) {
                val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
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
