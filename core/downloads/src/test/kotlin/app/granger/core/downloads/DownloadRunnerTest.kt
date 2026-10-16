package app.granger.core.downloads

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadRunnerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = inMemoryDownloads()
    private val dao = db.downloadDao()
    private val server = FakeServer()
    private var free = Long.MAX_VALUE / 2
    private var clock = 100L
    private val installed = mutableListOf<DownloadRecord>()
    private val runner = DownloadRunner(
        dao,
        FileDownloader(server.client(), freeBytes = { free }, reserveBytes = 0),
        onInstalled = { installed += it },
        now = { clock },
        progressEveryMillis = 0,
    )
    private val content = Random(11).nextBytes(50_000)
    private val url = "https://a.test/med.zim"
    private val root by lazy { tmp.newFolder("vol") }

    @After fun close() = db.close()

    private suspend fun queued(
        sha256: String = sha256(content),
        fileName: String = "packs/med_2026-10-10.zim",
        kind: ItemKind = ItemKind.PACK,
        state: DownloadState = DownloadState.QUEUED,
        size: Long = content.size.toLong(),
    ): DownloadRecord {
        val record = DownloadRecord(
            itemId = "med", sha256 = sha256, kind = kind, title = "WikiMed", urls = listOf(url), sizeBytes = size,
            filePath = File(root, fileName).path, snapshotDate = "2026-10-10", wifiOnly = true, state = state,
            createdAt = 1, updatedAt = 1,
        )
        dao.upsert(record)
        return record
    }

    private suspend fun record(sha256: String = sha256(content)) = dao.get("med", sha256)

    @Test fun installsAVerifiedFile() = runTest {
        server.files[url] = content
        val record = queued()
        assertThat(runner.run("med", record.sha256)).isEqualTo(RunOutcome.INSTALLED)
        assertThat(record.file.readBytes()).isEqualTo(content)
        assertThat(record.partFile.exists()).isFalse()
        val stored = record()!!
        assertThat(stored.state).isEqualTo(DownloadState.INSTALLED)
        assertThat(stored.bytesDone).isEqualTo(content.size.toLong())
        assertThat(stored.verifiedAt).isEqualTo(100L)
        assertThat(installed.map { it.itemId }).containsExactly("med")
    }

    @Test fun anUnverifiedFileIsNeverInstalled() = runTest {
        server.files[url] = Random(1).nextBytes(content.size)
        val record = queued()
        assertThat(runner.run("med", record.sha256)).isEqualTo(RunOutcome.FAILED)
        assertThat(record.file.exists()).isFalse()
        assertThat(record.partFile.exists()).isFalse()
        val stored = record()!!
        assertThat(stored.state).isEqualTo(DownloadState.FAILED)
        assertThat(stored.hashFailures).isEqualTo(2)
        assertThat(stored.error).contains("Checksum")
        assertThat(installed).isEmpty()
        assertThat(server.requests).hasSize(2) // retried once
    }

    @Test fun oneMismatchThenAGoodDownloadInstalls() = runTest {
        server.once[url] = Random(1).nextBytes(content.size)
        server.files[url] = content
        val record = queued()
        assertThat(runner.run("med", record.sha256)).isEqualTo(RunOutcome.INSTALLED)
        assertThat(record.file.readBytes()).isEqualTo(content)
    }

    @Test fun networkFailureAsksForARetryAndKeepsTheBytes() = runTest {
        server.files[url] = content
        server.failAfter[url] = 20_000
        val record = queued()
        assertThat(runner.run("med", record.sha256)).isEqualTo(RunOutcome.RETRY)
        val stored = record()!!
        assertThat(stored.state).isEqualTo(DownloadState.QUEUED)
        assertThat(stored.error).isNotNull()
        assertThat(stored.bytesDone).isEqualTo(20_000L)
        assertThat(record.partFile.length()).isEqualTo(20_000L)

        server.failAfter.remove(url)
        assertThat(runner.run("med", record.sha256)).isEqualTo(RunOutcome.INSTALLED)
        assertThat(server.rangesRequested(url).last()).isEqualTo("bytes=20000-")
    }

    @Test fun noSpaceFails() = runTest {
        server.files[url] = content
        free = 0
        val record = queued()
        assertThat(runner.run("med", record.sha256)).isEqualTo(RunOutcome.FAILED)
        assertThat(record()!!.error).contains("space")
    }

    @Test fun rerunAfterInstallDoesNothing() = runTest {
        server.files[url] = content
        val record = queued()
        runner.run("med", record.sha256)
        val requests = server.requests.size
        assertThat(runner.run("med", record.sha256)).isEqualTo(RunOutcome.INSTALLED)
        assertThat(server.requests.size).isEqualTo(requests)
        assertThat(installed).hasSize(1)
    }

    @Test fun aMissingRecordIsGone() = runTest {
        assertThat(runner.run("nope", sha256(content))).isEqualTo(RunOutcome.GONE)
    }

    @Test fun updateDeletesTheOldFileOnlyAfterVerifying() = runTest {
        val oldFile = File(root, "packs/med_2026-09-01.zim").apply { parentFile!!.mkdirs(); writeText("old snapshot") }
        dao.upsert(
            DownloadRecord("med", SHA_1, ItemKind.PACK, "WikiMed", listOf(url), 12, oldFile.path, "2026-09-01", true,
                DownloadState.INSTALLED, 12, createdAt = 1, updatedAt = 1),
        )
        server.files[url] = content
        val record = queued()
        var oldExistedDuringDownload = false
        runner.run("med", record.sha256) { _, _ -> oldExistedDuringDownload = oldFile.exists() }
        assertThat(oldExistedDuringDownload).isTrue()
        assertThat(oldFile.exists()).isFalse()
        assertThat(dao.forItem("med").map { it.sha256 }).containsExactly(record.sha256)
    }

    @Test fun failedUpdateKeepsTheOldVersion() = runTest {
        val oldFile = File(root, "packs/med_2026-09-01.zim").apply { parentFile!!.mkdirs(); writeText("old snapshot") }
        dao.upsert(
            DownloadRecord("med", SHA_1, ItemKind.PACK, "WikiMed", listOf(url), 12, oldFile.path, "2026-09-01", true,
                DownloadState.INSTALLED, 12, createdAt = 1, updatedAt = 1),
        )
        server.files[url] = Random(2).nextBytes(content.size)
        val record = queued()
        assertThat(runner.run("med", record.sha256)).isEqualTo(RunOutcome.FAILED)
        assertThat(oldFile.readText()).isEqualTo("old snapshot")
        assertThat(dao.get("med", SHA_1)!!.state).isEqualTo(DownloadState.INSTALLED)
    }

    @Test fun modelUpdateAtTheSamePathKeepsTheNewFile() = runTest {
        val path = "models/e2b.litertlm"
        val file = File(root, path).apply { parentFile!!.mkdirs(); writeText("old model") }
        dao.upsert(
            DownloadRecord("med", SHA_1, ItemKind.MODEL, "E2B", listOf(url), 9, file.path, "", true,
                DownloadState.INSTALLED, 9, createdAt = 1, updatedAt = 1),
        )
        server.files[url] = content
        val record = queued(fileName = path, kind = ItemKind.MODEL)
        assertThat(runner.run("med", record.sha256)).isEqualTo(RunOutcome.INSTALLED)
        assertThat(file.readBytes()).isEqualTo(content)
        assertThat(dao.forItem("med").map { it.sha256 }).containsExactly(record.sha256)
    }

    @Test fun stoppedWorkerGoesBackToQueued() = runTest {
        server.files[url] = content
        server.failAfter[url] = Int.MAX_VALUE // never drops; the job is cancelled instead
        val record = queued(size = content.size.toLong())
        lateinit var job: Job
        job = launch { runner.run("med", record.sha256) { done, _ -> if (done > 0) job.cancel() } }
        job.join()
        assertThat(record()!!.state).isEqualTo(DownloadState.QUEUED)
        assertThat(record.file.exists()).isFalse()
    }

    @Test fun cancelledRecordIsNotResurrected() = runTest {
        server.files[url] = content
        val record = queued()
        val outcome = runner.run("med", record.sha256) { done, total ->
            if (done == total) runBlocking { dao.delete("med", record.sha256) }
        }
        assertThat(outcome).isEqualTo(RunOutcome.GONE)
        assertThat(dao.forItem("med")).isEmpty()
        assertThat(record.file.exists()).isFalse()
        assertThat(installed).isEmpty()
    }
}
