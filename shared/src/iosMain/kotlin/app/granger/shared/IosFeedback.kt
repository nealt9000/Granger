package app.granger.shared

import app.granger.feedback.CrashKind
import app.granger.feedback.CrashRecords
import app.granger.feedback.CrashStore
import app.granger.settings.KnowledgePrefs
import kotlin.native.ReportUnhandledExceptionHook
import kotlin.native.setUnhandledExceptionHook
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUserDefaults
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone

/** The crash log's folder on the iPhone (Plan I7 Task 1): text files in [dir], as Android's `crashes/`. */
class IosCrashStore(private val dir: String) : CrashStore {
    override fun names(): List<String> = runCatching { SystemFileSystem.list(Path(dir)).map { it.name } }.getOrDefault(emptyList())

    override fun read(name: String): String? = runCatching {
        val p = Path(dir, name)
        if (SystemFileSystem.metadataOrNull(p)?.isRegularFile != true) null
        else SystemFileSystem.source(p).buffered().use { it.readString() }
    }.getOrNull()

    override fun write(name: String, text: String) {
        SystemFileSystem.createDirectories(Path(dir))
        SystemFileSystem.sink(Path(dir, name)).buffered().use { it.writeString(text) }
    }

    override fun delete(name: String) {
        runCatching { SystemFileSystem.delete(Path(dir, name), mustExist = false) }
    }

    override fun pathOf(name: String): String = Path(dir, name).toString()
}

/** A crash report's time as Android writes it: "2026-11-22 10:15:00 GMT", in the phone's zone. */
fun crashStamp(timestamp: Long): String = NSDateFormatter().apply {
    dateFormat = "yyyy-MM-dd HH:mm:ss z"
    locale = NSLocale(localeIdentifier = "en_US_POSIX")
    timeZone = NSTimeZone.localTimeZone
}.stringFromDate(NSDate.dateWithTimeIntervalSince1970(timestamp / 1000.0))

/** The knowledge languages in UserDefaults (Android's SharedPreferences "knowledge"), comma-joined, main first. */
class DefaultsKnowledgePrefs : KnowledgePrefs {
    private val d = NSUserDefaults.standardUserDefaults
    override var languages: List<String>?
        get() = d.stringForKey(KEY)?.split(',')?.filter { it.isNotBlank() }?.ifEmpty { null }
        set(value) {
            if (value.isNullOrEmpty()) d.removeObjectForKey(KEY) else d.setObject(value.joinToString(","), KEY)
        }

    private companion object {
        const val KEY = "knowledge.languages"
    }
}

/**
 * Kotlin's crashes on the iPhone (Plan I7 Task 4, call 4): an exception no Kotlin code caught is written to [records] at
 * once, as Android's Java handler writes it ("Thread: …", then the trace), before Kotlin/Native ends the process. Swift
 * installs it at start, beside its own `NSException` handler.
 */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
fun installCrashHook(records: CrashRecords, afterRecord: () -> Unit) {
    var previous: ReportUnhandledExceptionHook? = null
    previous = setUnhandledExceptionHook { error ->
        runCatching {
            val thread = if (platform.Foundation.NSThread.isMainThread) "main" else "background"
            records.record(CrashKind.JAVA, "Thread: $thread\n\n${error.stackTraceToString()}")
        }
        // Swift's say after the report is written: nothing in a release build; debug builds' forced test crash ends the
        // process cleanly here, so XCTest doesn't count the app's crash against the test that asked for it.
        afterRecord()
        previous?.invoke(error)
    }
}

/** Debug builds' forced crash (`-debugCrash kotlin`, the UI tests): an exception thrown through to Swift, uncaught. */
fun debugCrash(): Unit = throw IllegalStateException("Granger debug crash (forced for a test)")
