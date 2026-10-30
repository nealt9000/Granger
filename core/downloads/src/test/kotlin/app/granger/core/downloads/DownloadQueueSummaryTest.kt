package app.granger.core.downloads

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadQueueSummaryTest {
    private val now = 100_000L

    private fun rec(
        id: String, state: DownloadState, size: Long = 1_000, done: Long = 0, order: Long = 0, updated: Long = now,
        wifiOnly: Boolean = true, kind: ItemKind = ItemKind.PACK, path: String = "/phone/packs/$id.zim",
        attempts: Int = 0, retryAt: Long? = null, created: Long = updated,
    ) = DownloadRecord(
        itemId = id, hash = SHA_1, kind = kind, title = "Title $id", urls = listOf("https://a.test/$id"), sizeBytes = size,
        filePath = path, snapshotDate = "", wifiOnly = wifiOnly, state = state, bytesDone = done, createdAt = created,
        updatedAt = updated, queueOrder = order, attempts = attempts, retryAt = retryAt,
    )

    private fun live(
        current: String? = null, done: Long = 0, rate: Long? = null, paused: Boolean = false, since: Long = 50_000, wifi: Boolean = true,
    ) = DownloadLive(QueueProgress(current, current?.let { SHA_1 }, done, rate), paused, since, wifi)

    private fun reasons(s: QueueSummary) = s.waiting.map { it.record.itemId to it.reason }

    @Test fun theRunningFileTheRoundAndTheTimeLeft() {
        val records = listOf(
            rec("old", DownloadState.INSTALLED, updated = 10_000),
            rec("a", DownloadState.INSTALLED, size = 2_000, updated = 60_000),
            rec("b", DownloadState.RUNNING, size = 4_000, done = 1_000, order = 1),
            rec("b.vectors", DownloadState.QUEUED, size = 500, order = 2, kind = ItemKind.VECTORS),
            rec("c", DownloadState.QUEUED, size = 3_000, order = 3),
        )
        val s = DownloadQueueSummary.of(records, live(current = "b", done = 1_500, rate = 100), now)
        assertThat(s.current!!.itemId).isEqualTo("b")
        // The live figure is fresher than the record's.
        assertThat(s.currentBytesDone).isEqualTo(1_500)
        // "old" finished before this round; vectors add bytes but aren't files.
        assertThat(s.files).isEqualTo(3)
        assertThat(s.filesDone).isEqualTo(1)
        assertThat(s.bytes).isEqualTo(9_500)
        assertThat(s.bytesDone).isEqualTo(3_500)
        assertThat(s.bytesToGo).isEqualTo(6_000)
        assertThat(s.timeLeftMillis).isEqualTo(60_000)
        assertThat(reasons(s)).containsExactly("b.vectors" to WaitReason.InLine(1), "c" to WaitReason.InLine(2)).inOrder()
    }

    @Test fun noTimeLeftUntilThereIsASpeed() {
        val s = DownloadQueueSummary.of(listOf(rec("b", DownloadState.RUNNING)), live(current = "b", rate = null), now)
        assertThat(s.timeLeftMillis).isNull()
        assertThat(DownloadQueueSummary.of(listOf(rec("b", DownloadState.RUNNING)), live(current = "b", rate = 0), now).timeLeftMillis).isNull()
    }

    @Test fun pausedEverythingWaitsAsPausedWithNoTimeLeft() {
        val records = listOf(rec("a", DownloadState.QUEUED, done = 300), rec("b", DownloadState.QUEUED, order = 1))
        val s = DownloadQueueSummary.of(records, live(paused = true, rate = 100), now)
        assertThat(s.current).isNull()
        assertThat(s.paused).isTrue()
        assertThat(s.timeLeftMillis).isNull()
        assertThat(reasons(s)).containsExactly("a" to WaitReason.Paused, "b" to WaitReason.Paused).inOrder()
    }

    @Test fun eachWaitingItemHasOneReason() {
        val records = listOf(
            rec("run", DownloadState.RUNNING, size = 600, path = "/sd/packs/run.zim"),
            rec("wifi", DownloadState.QUEUED, order = 1, wifiOnly = true),
            rec("mobile", DownloadState.QUEUED, order = 2, wifiOnly = false),
            rec("flaky", DownloadState.QUEUED, order = 3, wifiOnly = false, attempts = 2, retryAt = now + 120_000),
            rec("big", DownloadState.QUEUED, order = 4, wifiOnly = false, size = 1_000, path = "/sd/packs/big.zim"),
            rec("later", DownloadState.QUEUED, order = 5, wifiOnly = false),
        )
        val free = mapOf("/phone" to Long.MAX_VALUE / 2, "/sd" to FileDownloader.DEFAULT_RESERVE_BYTES + 1_000)
        val s = DownloadQueueSummary.of(records, live(current = "run", wifi = false), now, free)
        assertThat(reasons(s)).containsExactly(
            "wifi" to WaitReason.WaitingForWifi,
            "mobile" to WaitReason.InLine(1),
            "flaky" to WaitReason.Retrying(now + 120_000, attempt = 3),
            // The running file takes 600 of the SD card's spare 1 000 first.
            "big" to WaitReason.NeedsSpace(600),
            "later" to WaitReason.InLine(2),
        ).inOrder()
    }

    @Test fun theRoundStartsAtTheBatchAndAStoppedFileStaysInItUntilNothingWaits() {
        val records = listOf(
            rec("before", DownloadState.INSTALLED, updated = 40_000),
            rec("gone", DownloadState.FAILED, updated = 60_000),
            rec("done", DownloadState.INSTALLED, updated = 70_000),
            rec("next", DownloadState.QUEUED, order = 1),
        )
        val s = DownloadQueueSummary.of(records, live(since = 50_000), now)
        assertThat(s.files).isEqualTo(3)
        assertThat(s.filesDone).isEqualTo(1)
        assertThat(s.stopped.map { it.itemId }).containsExactly("gone")
        assertThat(s.isActive).isTrue()

        // Nothing waiting or running: the round is finished, whatever stopped.
        val finished = DownloadQueueSummary.of(records.dropLast(1), live(since = 50_000), now)
        assertThat(finished.isActive).isFalse()
        assertThat(finished.files).isEqualTo(0)
        assertThat(finished.stopped.map { it.itemId }).containsExactly("gone")
    }

    @Test fun withNoStoredRoundItStartsWithTheOldestWaitingRecord() {
        val records = listOf(
            rec("long-ago", DownloadState.INSTALLED, updated = 1_000),
            rec("today", DownloadState.INSTALLED, updated = 80_000),
            rec("next", DownloadState.QUEUED, created = 70_000, updated = 70_000),
        )
        val s = DownloadQueueSummary.of(records, live(since = 0), now)
        assertThat(s.files).isEqualTo(2)
    }

    @Test fun plainWords() {
        assertThat(QueueText.timeLeft(20_000)).isEqualTo("less than a minute")
        assertThat(QueueText.timeLeft(4 * 60_000L)).isEqualTo("about 4 min")
        assertThat(QueueText.timeLeft(26 * 60_000L)).isEqualTo("about 25 min")
        assertThat(QueueText.timeLeft(3 * 3_600_000L + 10 * 60_000L)).isEqualTo("about 3 h")
        assertThat(QueueText.timeLeft(3 * 86_400_000L)).isEqualTo("about 3 days")
        assertThat(QueueText.inTime(110_000)).isEqualTo("in 2 min")
        assertThat(QueueText.inTime(30_000)).isEqualTo("in 30 s")
        assertThat((1..4).map(QueueText::place)).containsExactly("Next", "2nd in line", "3rd in line", "4th in line").inOrder()
        assertThat(QueueText.place(11)).isEqualTo("11th in line")
        assertThat(QueueText.place(22)).isEqualTo("22nd in line")
    }
}
