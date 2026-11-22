package app.granger.feedback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The crash log's shared rules (Plan I7 Task 1) over a folder in memory; Android's `CrashLogTest` runs them on files. */
class CrashRecordsTest {
    private class MemoryStore : CrashStore {
        val files = mutableMapOf<String, String>()
        override fun names() = files.keys.toList()
        override fun read(name: String) = files[name]
        override fun write(name: String, text: String) { files[name] = text }
        override fun delete(name: String) { files.remove(name) }
        override fun pathOf(name: String) = "/crashes/$name"
    }

    private val facts = BuildFacts("0.1.850", 850, "iPhone 16", "18.2", "iOS")
    private var clock = 1_000L
    private val store = MemoryStore()
    private fun log(f: BuildFacts = facts) = CrashRecords(store, f, { clock }, { "t$it" })

    @Test fun aCrashIsWrittenWithItsHeaderAndListedNewestFirst() {
        val log = log()
        log.record(CrashKind.JAVA, "Thread: main\n\nkotlin.IllegalStateException: boom\n\tat x")
        clock = 2_000L
        log.record(CrashKind.JAVA, "Thread: main\n\nkotlin.Error: second")

        val reports = log.list()
        assertEquals(listOf(2_000L, 1_000L), reports.map { it.timestamp })
        assertEquals("kotlin.Error: second", reports[0].summary)
        assertTrue(reports[0].path.startsWith("/crashes/crash-"))
        assertEquals("Crash at t1000\nGranger 0.1.850 (850) · iPhone 16 · iOS 18.2\n\nThread: main", log.read(reports[1])!!.lines().take(4).joinToString("\n"))
    }

    @Test fun onlyTheNewestFiveAreKept() {
        val log = log()
        repeat(7) { clock = 1_000L + it; log.record(CrashKind.JAVA, "crash $it") }
        assertEquals(CrashRecords.KEEP, log.list().size)
        assertEquals("crash 6", log.list().first().summary)
        assertEquals("crash 2", log.list().last().summary)
    }

    @Test fun aLongReportIsCut() {
        val log = log()
        log.record(CrashKind.JAVA, "x".repeat(CrashRecords.MAX_BYTES * 2))
        val text = log.read(log.list().single())!!
        assertTrue(text.length < CrashRecords.MAX_BYTES + 100)
        assertTrue(text.endsWith("(cut at 64 KB)"))
    }

    @Test fun pastExitsAreRecordedOnceAndTheFirstReadStartsFromNow() {
        val log = log()
        clock = 5_000L
        assertEquals(0, log.recordExits(listOf(PastExit(4_000L, CrashKind.ANR, "old", null))))
        assertEquals(1, log.recordExits(listOf(PastExit(4_000L, CrashKind.ANR, null, null), PastExit(6_000L, CrashKind.NATIVE, "crash", "Abort message: 'boom'"))))
        assertEquals(0, log.recordExits(listOf(PastExit(6_000L, CrashKind.NATIVE, "crash", null))))
        assertEquals("boom", log.list().single().summary)
    }

    @Test fun homeAsksOnceAboutACrashInThisBuild() {
        val log = log()
        log.recordExits(emptyList())
        clock = 2_000L
        log.recordExits(listOf(PastExit(1_500L, CrashKind.ANR, null, null)))
        assertNotNull(log.notice.value)
        log.acknowledge()
        assertNull(log.notice.value)

        clock = 3_000L
        log.recordExits(listOf(PastExit(2_500L, CrashKind.ANR, null, null)))
        val newer = log(facts.copy(versionName = "0.1.851", versionCode = 851))
        newer.refreshNotice()
        assertNull(newer.notice.value) // from an older build: kept for Send feedback, not asked about
        newer.clear()
        assertTrue(newer.list().isEmpty())
    }

    @Test fun aDiskWriteReportIsKeptButHomeDoesNotAsk() {
        val log = log()
        log.recordExits(emptyList())
        clock = 2_000L
        log.recordExits(listOf(PastExit(1_500L, CrashKind.DISK, "Writes: 1.2 GB in 24 h", null)))
        assertEquals(CrashKind.DISK, log.list().single().kind)
        assertNull(log.notice.value)
        clock = 3_000L
        log.recordExits(listOf(PastExit(2_500L, CrashKind.NATIVE, null, null), PastExit(2_600L, CrashKind.DISK, null, null)))
        assertEquals(2_500L, log.notice.value?.timestamp) // the close behind the newer disk report
        log.acknowledge()
        assertNull(log.notice.value)
    }

    @Test fun anAbortMessageIsTheSummary() {
        assertEquals("NoSuchMethodError: invoke([F)", CrashRecords.abortMessage("Abort message: 'NoSuchMethodError: invoke([F)'"))
        assertEquals("Uncaught exception", CrashRecords.abortMessage("[FATAL:file.cc:93] Uncaught exception"))
        assertNull(CrashRecords.abortMessage("SIGTRAP"))
        assertEquals("Abort message\n", CrashRecords.readableRuns("\u0001Abort message\u0002ab".encodeToByteArray()))
    }
}
