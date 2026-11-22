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
import kotlinx.coroutines.flow.StateFlow

/** This phone's build and model (the shared [BuildFacts], Plan I7 Task 1). */
fun BuildFacts.Companion.ofThisPhone() = BuildFacts(
    BuildConfig.VERSION_NAME,
    BuildConfig.VERSION_CODE,
    "${Build.MANUFACTURER} ${Build.MODEL}",
    "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
)

/** A report's file on this phone. */
val CrashReport.file: File get() = File(path)

/** A report from its [file], as Android code and tests made them before the report was shared. */
fun CrashReport(file: File, timestamp: Long, kind: CrashKind, summary: String) = CrashReport(file.path, timestamp, kind, summary)

/** The crash log's folder as files in [dir]. */
class FileCrashStore(private val dir: File) : CrashStore {
    override fun names(): List<String> = dir.list()?.toList().orEmpty()
    override fun read(name: String): String? = File(dir, name).takeIf { it.isFile }?.readText()
    override fun write(name: String, text: String) {
        dir.mkdirs()
        File(dir, name).writeText(text)
    }
    override fun delete(name: String) {
        File(dir, name).delete()
    }
    override fun pathOf(name: String): String = File(dir, name).path
}

/**
 * The local crash log (Plan F1 part 2 Decision 1): small text files in [dir], the newest [KEEP] kept, each capped at
 * [MAX_BYTES]. Nothing here leaves the phone; Send feedback attaches them only when the user sends. The rules are the
 * shared [CrashRecords] (Plan I7 Task 1); this wrapper adds Android's sources.
 *
 * Java crashes come through [handler]. Native crashes and ANRs never reach a Java handler, so the next start reads them
 * from the system ([recordExits]); a mark keeps each one from being recorded twice.
 */
class CrashLog(
    dir: File,
    facts: BuildFacts,
    now: () -> Long = System::currentTimeMillis,
) {
    private val records = CrashRecords(FileCrashStore(dir), facts, now) { stamp(it) }

    /**
     * The newest report the user hasn't answered yet, for Home's "Granger closed unexpectedly" line (Decision 3).
     * Filled once the start has read past exits ([recordExits]); null after [acknowledge] or [clear].
     */
    val notice: StateFlow<CrashReport?> = records.notice

    /** Records [error] on [thread], then hands it to [previous] so Android still ends the process the usual way. */
    fun handler(previous: Thread.UncaughtExceptionHandler?) = Thread.UncaughtExceptionHandler { thread, error ->
        runCatching { record(thread, error) }
        previous?.uncaughtException(thread, error)
    }

    fun record(thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        records.record(CrashKind.JAVA, "Thread: ${thread.name}\n\n$trace")
    }

    /** [CrashRecords.recordExits]: the exits newer than the last one recorded; how many were new. */
    fun recordExits(exits: List<PastExit>): Int = records.recordExits(exits)

    /** The user sent a report or said not now: Home stops asking until the next crash. */
    fun acknowledge() = records.acknowledge()

    fun refreshNotice() = records.refreshNotice()

    fun list(): List<CrashReport> = records.list()

    fun clear() = records.clear()

    companion object {
        const val KEEP = CrashRecords.KEEP
        const val MAX_BYTES = CrashRecords.MAX_BYTES

        /** [CrashRecords.abortMessage]. */
        fun abortMessage(line: String): String? = CrashRecords.abortMessage(line)

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

        /** [CrashRecords.readableRuns]. */
        fun readableRuns(bytes: ByteArray): String = CrashRecords.readableRuns(bytes)
    }
}
