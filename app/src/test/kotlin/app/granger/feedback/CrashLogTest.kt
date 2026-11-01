package app.granger.feedback

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private val facts = BuildFacts("0.1.850", 850, "Google Pixel 7", "16 (API 36)")
    private var clock = 1_000L
    private val log by lazy { CrashLog(tmp.root.resolve("crashes"), facts) { clock } }

    @Test fun aCrashIsWrittenWithTheBuildAndTheTrace() {
        log.record(Thread("main"), IllegalStateException("Cannot find class CatalogScope"))

        val report = log.list().single()
        val text = report.file.readText()
        assertThat(report.kind).isEqualTo(CrashKind.JAVA)
        assertThat(report.timestamp).isEqualTo(1_000L)
        assertThat(text).contains("Granger 0.1.850 (850) · Google Pixel 7 · Android 16 (API 36)")
        assertThat(text).contains("Thread: main")
        assertThat(text).contains("java.lang.IllegalStateException: Cannot find class CatalogScope")
        assertThat(text).contains("at app.granger.feedback.CrashLogTest")
        assertThat(report.summary).isEqualTo("java.lang.IllegalStateException: Cannot find class CatalogScope")
    }

    @Test fun onlyTheNewestFiveAreKept() {
        repeat(6) { i ->
            clock = 1_000L + i
            log.record(Thread("main"), RuntimeException("crash $i"))
        }

        assertThat(log.list().map { it.summary }).containsExactly(
            "java.lang.RuntimeException: crash 5",
            "java.lang.RuntimeException: crash 4",
            "java.lang.RuntimeException: crash 3",
            "java.lang.RuntimeException: crash 2",
            "java.lang.RuntimeException: crash 1",
        ).inOrder()
    }

    @Test fun aHugeTraceIsCut() {
        log.record(Thread("main"), RuntimeException("x".repeat(200_000)))

        val text = log.list().single().file.readText()
        assertThat(text.length).isLessThan(CrashLog.MAX_BYTES + 100)
        assertThat(text).endsWith("… (cut at 64 KB)")
    }

    @Test fun pastExitsAreRecordedOnceEach() {
        val exits = listOf(
            PastExit(5_000L, CrashKind.NATIVE, "crash", "Abort message: 'NoSuchMethodError: invoke([F)'"),
            PastExit(4_000L, CrashKind.ANR, "Input dispatching timed out", "\"main\" prio=5 tid=1 Blocked"),
        )

        assertThat(log.recordExits(exits)).isEqualTo(2)
        assertThat(log.recordExits(exits)).isEqualTo(0)

        val reports = log.list()
        assertThat(reports.map { it.kind }).containsExactly(CrashKind.NATIVE, CrashKind.ANR).inOrder()
        assertThat(reports.first().file.readText()).contains("Abort message: 'NoSuchMethodError: invoke([F)'")
        assertThat(reports.first().file.readText()).startsWith("Native crash at ")
    }

    @Test fun anExitOlderThanTheLastRecordedIsSkipped() {
        log.recordExits(listOf(PastExit(5_000L, CrashKind.NATIVE, null, null)))

        val added = log.recordExits(listOf(PastExit(4_500L, CrashKind.ANR, null, null), PastExit(6_000L, CrashKind.ANR, null, null)))

        assertThat(added).isEqualTo(1)
        assertThat(log.list().map { it.timestamp }).containsExactly(6_000L, 5_000L).inOrder()
        assertThat(log.list().last().file.readText()).contains("(no trace kept by the system)")
    }

    @Test fun theHandlerRecordsThenPassesTheCrashOn() {
        val passed = mutableListOf<Throwable>()
        val error = RuntimeException("boom")

        log.handler { _, e -> passed += e }.uncaughtException(Thread("worker"), error)

        assertThat(passed).containsExactly(error)
        assertThat(log.list().single().summary).isEqualTo("java.lang.RuntimeException: boom")
    }

    @Test fun clearRemovesTheReports() {
        log.record(Thread("main"), RuntimeException("boom"))
        log.clear()
        assertThat(log.list()).isEmpty()
    }

    /** Seen on the Pixel 7 (release 849): the list said only "Reason: crash" for a WebView renderer abort. */
    @Test fun aNativeCrashIsSummedUpByItsAbortMessage() {
        val tombstone = "Dgoogle/panther/panther:16/CP1A.260405.005\napp.grangerR\"\nSIGTRAP\n" +
            "[FATAL:third_party/crashpad/crashpad/client/crashpad_client_linux.cc:763] Render process (31383)'s crash wasn't handled\n"
        log.recordExits(listOf(PastExit(5_000L, CrashKind.NATIVE, "crash", tombstone)))

        assertThat(log.list().single().summary).isEqualTo("Render process (31383)'s crash wasn't handled")
        assertThat(CrashLog.abortMessage("Abort message: 'NoSuchMethodError: invoke([F)'")).isEqualTo("NoSuchMethodError: invoke([F)")
        assertThat(CrashLog.abortMessage("SIGTRAP")).isNull()
    }

    @Test fun aNativeTombstoneKeepsItsReadableRuns() {
        val bytes = byteArrayOf(0x0a, 0x12) + "Abort message".toByteArray() + byteArrayOf(0x00, 0x01, 0x41, 0x42) +
            byteArrayOf(0x1a) + "libsherpa-onnx-jni.so".toByteArray()

        assertThat(CrashLog.readableRuns(bytes)).isEqualTo("Abort message\nlibsherpa-onnx-jni.so\n")
    }

    @Test fun readAtMostStopsAtTheLimit() {
        val input = ByteArray(20_000) { 1 }.inputStream()
        assertThat(CrashLog.readAtMost(input, 10_000)).hasLength(10_000)
    }

    /** Plan F1 part 2 Decision 3: Home asks about the newest crash once, until the user answers or a new one comes. */
    @Test fun theNoticeShowsTheNewestUnansweredCrash() {
        assertThat(log.notice.value).isNull()
        log.recordExits(listOf(PastExit(5_000L, CrashKind.NATIVE, null, null)))
        assertThat(log.notice.value?.timestamp).isEqualTo(5_000L)

        log.acknowledge()
        assertThat(log.notice.value).isNull()
        log.recordExits(emptyList())
        assertThat(log.notice.value).isNull()

        log.recordExits(listOf(PastExit(6_000L, CrashKind.ANR, null, null)))
        assertThat(log.notice.value?.kind).isEqualTo(CrashKind.ANR)
        log.clear()
        assertThat(log.notice.value).isNull()
    }

    @Test fun aJavaCrashFromTheLastRunIsNoticedOnTheNextStart() {
        log.record(Thread("main"), RuntimeException("boom"))
        // The next start reads past exits (none native here), which refreshes the notice from the files.
        val next = CrashLog(tmp.root.resolve("crashes"), facts) { clock }
        next.recordExits(emptyList())
        assertThat(next.notice.value?.summary).isEqualTo("java.lang.RuntimeException: boom")
    }
}
