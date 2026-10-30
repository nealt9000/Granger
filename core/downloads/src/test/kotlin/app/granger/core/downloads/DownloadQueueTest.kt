package app.granger.core.downloads

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

/** Plan DL Decision 1–4: one queue, one file at a time, in a stored order, with pause, Download next and retries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadQueueTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = inMemoryDownloads()
    private val dao = db.downloadDao()
    private var clock = 1_000L
    private var unmetered = true
    private val prefs = MemoryQueuePrefs()
    private val root by lazy { tmp.newFolder("vol") }

    @After fun close() = db.close()

    /** Runs a record as the real runner would mark it, without the network. */
    private inner class FakeRunner : RecordRunner {
        val ran = mutableListOf<String>()
        var running = 0
        var mostAtOnce = 0

        /** Each item's next outcomes; INSTALLED once they run out. */
        val outcomes = mutableMapOf<String, ArrayDeque<RunOutcome>>()

        /** A held item's run waits here until it is completed (or the run is cancelled). */
        val holds = mutableMapOf<String, CompletableDeferred<Unit>>()
        val started = mutableMapOf<String, CompletableDeferred<Unit>>()

        /** Runs as an item installs, like the app's library refresh (and VectorDownloads after it). */
        var onInstalled: suspend (String) -> Unit = {}

        override suspend fun run(itemId: String, hash: String, onProgress: (Long, Long) -> Unit): RunOutcome {
            ran += itemId
            running++
            mostAtOnce = maxOf(mostAtOnce, running)
            try {
                if (dao.setActiveState(itemId, hash, DownloadState.RUNNING, null, 0, 0, clock) == 0) return RunOutcome.GONE
                started.getOrPut(itemId) { CompletableDeferred() }.complete(Unit)
                holds[itemId]?.await()
                return when (val outcome = outcomes[itemId]?.removeFirstOrNull() ?: RunOutcome.INSTALLED) {
                    RunOutcome.INSTALLED -> outcome.also { dao.markInstalled(itemId, hash, clock); onInstalled(itemId) }
                    RunOutcome.RETRY -> outcome.also { dao.setActiveState(itemId, hash, DownloadState.QUEUED, "a.test answered 503", 0, 0, clock) }
                    else -> outcome.also { dao.setActiveState(itemId, hash, DownloadState.FAILED, "a.test answered 403", 0, 0, clock) }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { dao.requeueIfRunning(itemId, hash, clock) }
                throw e
            } finally {
                running--
            }
        }

        override suspend fun giveUp(itemId: String, hash: String) {
            val record = dao.get(itemId, hash) ?: return
            dao.setActiveState(itemId, hash, DownloadState.FAILED, "Gave up after repeated failures: ${record.error}", 0, 0, clock)
        }
    }

    private val runner = FakeRunner()
    private lateinit var queue: DownloadQueue
    private val scheduler = FakeScheduler { queue }
    private val manager = managerOf(scheduler)

    private fun managerOf(scheduler: DownloadScheduler) = DownloadManager(
        dao, scheduler, prefs, installedPackBytes = { 0 }, freeBytes = { Long.MAX_VALUE / 2 }, io = Dispatchers.Unconfined, now = { clock },
    )

    private fun queueOf(recordRunner: RecordRunner, networkCheckMillis: Long = Long.MAX_VALUE) =
        DownloadQueue(dao, recordRunner, prefs, unmetered = { unmetered }, now = { clock }, networkCheckMillis = networkCheckMillis)

    init {
        queue = queueOf(runner)
    }

    private fun spec(id: String, group: String? = null, kind: ItemKind = ItemKind.PACK) = DownloadSpec(
        itemId = id, kind = kind, title = "Title $id", urls = listOf("https://a.test/$id.zim"), sizeBytes = 100, hash = SHA_1,
        hashAlgorithm = HashAlgorithm.SHA256, relativePath = "packs/$id.zim", snapshotDate = "", capped = false, groupId = group,
    )

    private suspend fun group(id: String, vararg items: String, wifiOnly: Boolean = true) =
        manager.enqueueGroup(id, items.map { spec(it, id) }, root, wifiOnly)

    private suspend fun waiting() = dao.waiting().map { it.itemId }

    private fun TestScope.drainInBackground() = launch { queue.drain() }

    @Test fun oneFileAtATimeInQueueOrder() = runTest {
        // Ids that don't sort in list order: the stored order, not the id, decides.
        group("onboarding:1", "voice", "classics", "knots", "atlas")
        assertThat(queue.drain()).isEqualTo(QueueRest.Idle)
        assertThat(runner.ran).containsExactly("voice", "classics", "knots", "atlas").inOrder()
        assertThat(runner.mostAtOnce).isEqualTo(1)
        assertThat(dao.getAll().map { it.state }.distinct()).containsExactly(DownloadState.INSTALLED)
    }

    @Test fun aGroupKeepsItsOrderThroughAWifiToggleAndRetryAll() = runTest {
        group("onboarding:1", "voice", "classics", "knots", "atlas")
        dao.setState("classics", SHA_1, DownloadState.FAILED, "boom", 0, 0, clock)
        dao.setState("atlas", SHA_1, DownloadState.FAILED, "boom", 0, 0, clock)
        manager.setWifiOnly(false)
        assertThat(waiting()).containsExactly("voice", "knots").inOrder()
        manager.retryGroup("onboarding:1")
        manager.setWifiOnly(true)
        assertThat(waiting()).containsExactly("voice", "classics", "knots", "atlas").inOrder()
        assertThat(dao.forGroup("onboarding:1").map { it.itemId }).containsExactly("voice", "classics", "knots", "atlas").inOrder()
        queue.drain()
        assertThat(runner.ran).containsExactly("voice", "classics", "knots", "atlas").inOrder()
    }

    @Test fun somethingYouStartGoesNextAndVectorsFollowTheirParent() = runTest {
        group("onboarding:1", "med", "knots", "atlas")
        runner.onInstalled = { id -> if (id == "med") manager.enqueue(spec(VectorDownloads.itemIdOf("med"), kind = ItemKind.VECTORS), root, true) }
        runner.holds["knots"] = CompletableDeferred()
        val drain = drainInBackground()
        runner.started.getOrPut("knots") { CompletableDeferred() }.await()
        // Get, while knots downloads: it goes right after knots, ahead of atlas.
        manager.enqueue(spec("cooking"), root, true)
        assertThat(waiting()).containsExactly("knots", "cooking", "atlas").inOrder()
        runner.holds.getValue("knots").complete(Unit)
        drain.join()
        assertThat(runner.ran).containsExactly("med", "med.vectors", "knots", "cooking", "atlas").inOrder()
    }

    @Test fun downloadNextStartsRightAfterTheCurrentFileWithoutCuttingItOff() = runTest {
        group("onboarding:1", "a", "b", "c", "d")
        runner.holds["a"] = CompletableDeferred()
        val drain = drainInBackground()
        runner.started.getOrPut("a") { CompletableDeferred() }.await()
        manager.downloadNext("d")
        assertThat(waiting()).containsExactly("a", "d", "b", "c").inOrder()
        assertThat(dao.get("a", SHA_1)!!.state).isEqualTo(DownloadState.RUNNING)
        runner.holds.getValue("a").complete(Unit)
        drain.join()
        assertThat(runner.ran).containsExactly("a", "d", "b", "c").inOrder()
    }

    @Test fun cancellingTheRunningFileMovesOn() = runTest {
        group("onboarding:1", "a", "b")
        runner.holds["a"] = CompletableDeferred()
        val drain = drainInBackground()
        runner.started.getOrPut("a") { CompletableDeferred() }.await()
        manager.cancel("a")
        drain.join()
        assertThat(runner.ran).containsExactly("a", "b").inOrder()
        assertThat(dao.getAll().map { it.itemId }).containsExactly("b")
    }

    @Test fun aTransientFailureMovesOnAndComesBackAfterItsRetryTime() = runTest {
        group("onboarding:1", "flaky", "steady")
        runner.outcomes["flaky"] = ArrayDeque(listOf(RunOutcome.RETRY))
        val rest = queue.drain()
        assertThat(runner.ran).containsExactly("flaky", "steady").inOrder()
        val flaky = dao.get("flaky", SHA_1)!!
        assertThat(flaky.state).isEqualTo(DownloadState.QUEUED)
        assertThat(flaky.attempts).isEqualTo(1)
        assertThat(flaky.retryAt).isEqualTo(clock + 30_000)
        assertThat(flaky.error).isEqualTo("a.test answered 503")
        // Only a retry is left: the queue books its next look for then.
        assertThat(rest).isEqualTo(QueueRest.Later(30_000, unmeteredOnly = false))
        clock += 30_000
        queue.drain()
        assertThat(runner.ran).containsExactly("flaky", "steady", "flaky").inOrder()
        assertThat(dao.get("flaky", SHA_1)!!.state).isEqualTo(DownloadState.INSTALLED)
    }

    @Test fun tenFailedTriesFailTheRecord() = runTest {
        group("onboarding:1", "flaky")
        runner.outcomes["flaky"] = ArrayDeque(List(MAX_ATTEMPTS) { RunOutcome.RETRY })
        repeat(MAX_ATTEMPTS) {
            queue.drain()
            clock += 6 * 60 * 60 * 1000L
        }
        assertThat(runner.ran).hasSize(MAX_ATTEMPTS)
        val record = dao.get("flaky", SHA_1)!!
        assertThat(record.state).isEqualTo(DownloadState.FAILED)
        assertThat(record.error).isEqualTo("Gave up after repeated failures: a.test answered 503")
        assertThat(queue.drain()).isEqualTo(QueueRest.Idle)
    }

    @Test fun theBackoffDoublesFrom30sUpTo5Hours() {
        assertThat(DownloadQueue.backoffMillis(1)).isEqualTo(30_000)
        assertThat(DownloadQueue.backoffMillis(2)).isEqualTo(60_000)
        assertThat(DownloadQueue.backoffMillis(3)).isEqualTo(120_000)
        assertThat(DownloadQueue.backoffMillis(12)).isEqualTo(5 * 60 * 60 * 1000L)
    }

    @Test fun aWifiOnlyRecordIsSkippedOnMobileData() = runTest {
        group("onboarding:1", "big", wifiOnly = true)
        manager.enqueue(spec("small"), root, wifiOnly = false)
        unmetered = false
        val rest = queue.drain()
        assertThat(runner.ran).containsExactly("small")
        assertThat(rest).isEqualTo(QueueRest.Later(0, unmeteredOnly = true))
        unmetered = true
        queue.drain()
        assertThat(runner.ran).containsExactly("small", "big").inOrder()
    }

    @Test fun losingWifiSendsAWifiOnlyRunBackToWaiting() = runTest {
        queue = queueOf(runner, networkCheckMillis = 1_000)
        group("onboarding:1", "big", wifiOnly = true)
        runner.holds["big"] = CompletableDeferred()
        var rest: QueueRest? = null
        val drain = launch { rest = queue.drain() }
        runner.started.getOrPut("big") { CompletableDeferred() }.await()
        unmetered = false
        advanceTimeBy(1_001)
        drain.join()
        assertThat(dao.get("big", SHA_1)!!.state).isEqualTo(DownloadState.QUEUED)
        assertThat(rest).isEqualTo(QueueRest.Later(0, unmeteredOnly = true))
    }

    @Test fun pauseStopsTheQueueAndSurvivesARestart() = runTest {
        group("onboarding:1", "a", "b")
        runner.holds["a"] = CompletableDeferred()
        val drain = drainInBackground()
        runner.started.getOrPut("a") { CompletableDeferred() }.await()
        manager.pause()
        drain.join()
        assertThat(scheduler.queueStops).isEqualTo(1)
        assertThat(manager.paused.value).isTrue()
        assertThat(dao.get("a", SHA_1)!!.state).isEqualTo(DownloadState.QUEUED)
        assertThat(queue.drain()).isEqualTo(QueueRest.Paused)
        // A restart: Pause all is still on, so the queue isn't started.
        val after = FakeScheduler { queue }
        val restarted = managerOf(after)
        restarted.recover()
        assertThat(after.ensured).isEmpty()
        assertThat(restarted.paused.value).isTrue()
        runner.holds.remove("a")
        restarted.resume()
        assertThat(after.ensured).hasSize(1)
        queue.drain()
        assertThat(runner.ran).containsExactly("a", "a", "b").inOrder()
    }

    @Test fun pauseKeepsThePartFileAndResumeAsksForTheRest() = runTest {
        val server = FakeServer()
        val content = Random(3).nextBytes(40_000)
        val url = "https://a.test/med.zim"
        server.files[url] = content
        server.hang += url
        val realRunner = DownloadRunner(dao, FileDownloader(server.client(), freeBytes = { Long.MAX_VALUE / 2 }, reserveBytes = 0), onInstalled = {})
        queue = queueOf(realRunner)
        manager.enqueue(
            spec("med").copy(urls = listOf(url), sizeBytes = content.size.toLong(), hash = sha256(content)), root, true,
        )
        val record = dao.forItem("med").single()
        // What an earlier run left.
        record.partFile.apply { parentFile!!.mkdirs(); writeBytes(content.copyOf(15_000)) }
        val drain = launch(Dispatchers.Default) { queue.drain() }
        while (server.requests.isEmpty()) Thread.sleep(5)
        manager.pause()
        drain.join()
        assertThat(dao.get("med", record.hash)!!.state).isEqualTo(DownloadState.QUEUED)
        assertThat(record.partFile.length()).isEqualTo(15_000L)
        server.hang.clear()
        // Killed and started again: the new process recovers, then Resume carries on.
        val after = FakeScheduler { queue }
        managerOf(after).apply { recover(); resume() }
        queue.drain()
        assertThat(dao.get("med", record.hash)!!.state).isEqualTo(DownloadState.INSTALLED)
        assertThat(File(record.filePath).readBytes()).isEqualTo(content)
        assertThat(server.rangesRequested(url)).containsExactly("bytes=15000-", "bytes=15000-").inOrder()
    }

    @Test fun aGroupRecordLeftRunningIsRecoveredAtStartup() = runTest {
        group("onboarding:1", "a", "b")
        // The process died mid-file, before Plan DL: the group's chain is gone and the record still says RUNNING.
        dao.setState("a", SHA_1, DownloadState.RUNNING, null, 40, 0, clock)
        val after = FakeScheduler { queue }
        managerOf(after).recover()
        assertThat(dao.get("a", SHA_1)!!.state).isEqualTo(DownloadState.QUEUED)
        assertThat(dao.get("a", SHA_1)!!.bytesDone).isEqualTo(40)
        assertThat(after.ensured).containsExactly(true)
        queue.drain()
        assertThat(runner.ran).containsExactly("a", "b").inOrder()
    }

    @Test fun wakeWhileDrainingAsksTheDrainToLookAgain() = runTest {
        group("onboarding:1", "a")
        runner.holds["a"] = CompletableDeferred()
        val drain = drainInBackground()
        runner.started.getOrPut("a") { CompletableDeferred() }.await()
        assertThat(queue.wake()).isFalse()
        runner.holds.getValue("a").complete(Unit)
        drain.join()
        assertThat(queue.wake()).isTrue()
    }
}
