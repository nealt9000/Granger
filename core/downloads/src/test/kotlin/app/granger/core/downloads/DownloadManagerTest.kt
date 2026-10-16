package app.granger.core.downloads

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = inMemoryDownloads()
    private val dao = db.downloadDao()
    private val scheduler = FakeScheduler()
    private val dispatcher = StandardTestDispatcher()
    private var installedPackBytes = 0L
    private var cap = Long.MAX_VALUE
    private val free = mutableMapOf<String, Long>()
    private val internal by lazy { tmp.newFolder("internal") }
    private val sd by lazy { tmp.newFolder("sd") }
    private val manager = DownloadManager(
        dao, scheduler,
        installedPackBytes = { installedPackBytes },
        capBytes = { cap },
        freeBytes = { free[it.path] ?: Long.MAX_VALUE / 2 },
        io = dispatcher,
        now = { 7L },
    )

    @After fun close() = db.close()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private suspend fun records() = manager.records.first()

    private suspend fun installed(entry: app.granger.core.catalog.CatalogItem, filePath: String) {
        dao.upsert(
            DownloadRecord(
                itemId = entry.id, sha256 = entry.sha256, kind = ItemKind.PACK, title = entry.title, urls = entry.urls,
                sizeBytes = entry.sizeBytes, filePath = filePath, snapshotDate = "2026-09-01", wifiOnly = true,
                state = DownloadState.INSTALLED, bytesDone = entry.sizeBytes, createdAt = 1, updatedAt = 1,
            ),
        )
    }

    @Test fun enqueueWritesARecordAndSchedulesIt() = test {
        assertThat(manager.enqueue(packEntry(), internal, wifiOnly = true)).isEqualTo(EnqueueResult.Queued)
        val record = records().single()
        assertThat(record.state).isEqualTo(DownloadState.QUEUED)
        assertThat(record.kind).isEqualTo(ItemKind.PACK)
        assertThat(record.filePath).isEqualTo(File(internal, "packs/med_2026-10-10.zim").path)
        assertThat(record.partFile.path).isEqualTo(File(internal, "packs/med_2026-10-10.zim.part").path)
        assertThat(record.urls).containsExactly("https://a.test/med.zim")
        assertThat(record.wifiOnly).isTrue()
        assertThat(record.createdAt).isEqualTo(7L)
        assertThat(scheduler.scheduled.single()).isEqualTo(record to false)
    }

    @Test fun modelsGoToTheModelsFolder() = test {
        manager.enqueue(modelEntry(), sd, wifiOnly = false)
        val record = records().single()
        assertThat(record.kind).isEqualTo(ItemKind.MODEL)
        assertThat(record.filePath).isEqualTo(File(sd, "models/e2b.litertlm").path)
        assertThat(record.wifiOnly).isFalse()
    }

    @Test fun enqueueTwiceIsOneDownload() = test {
        manager.enqueue(packEntry(), internal, true)
        assertThat(manager.enqueue(packEntry(), internal, true)).isEqualTo(EnqueueResult.AlreadyActive)
        assertThat(records()).hasSize(1)
        assertThat(scheduler.scheduled).hasSize(1)
    }

    @Test fun anInstalledVersionIsNotDownloadedAgain() = test {
        installed(packEntry(), "/x/med.zim")
        assertThat(manager.enqueue(packEntry(), internal, true)).isEqualTo(EnqueueResult.AlreadyInstalled)
        assertThat(scheduler.scheduled).isEmpty()
    }

    @Test fun aNewerVersionQueuesNextToTheInstalledOne() = test {
        installed(packEntry(sha256 = SHA_1), "/x/med_2026-09-01.zim")
        assertThat(manager.enqueue(packEntry(sha256 = SHA_2), internal, true)).isEqualTo(EnqueueResult.Queued)
        assertThat(records().map { it.sha256 to it.state })
            .containsExactly(SHA_1 to DownloadState.INSTALLED, SHA_2 to DownloadState.QUEUED)
    }

    @Test fun capRefusesPacksButNotModels() = test {
        cap = 1_500
        installedPackBytes = 1_000
        assertThat(manager.enqueue(packEntry(size = 1_000), internal, true)).isEqualTo(EnqueueResult.OverCap(1_500, 2_000))
        assertThat(manager.enqueue(modelEntry(size = 5_000), internal, true)).isEqualTo(EnqueueResult.Queued)
    }

    @Test fun queuedPacksCountTowardsTheCap() = test {
        cap = 2_500
        manager.enqueue(packEntry(id = "a", size = 1_000), internal, true)
        manager.enqueue(packEntry(id = "b", size = 1_000), internal, true)
        assertThat(manager.enqueue(packEntry(id = "c", size = 1_000), internal, true))
            .isEqualTo(EnqueueResult.OverCap(2_500, 3_000))
    }

    @Test fun queuedDownloadsOnTheSameVolumeCount() = test {
        free[internal.path] = QueuePolicy.RESERVE_BYTES + 1_500
        free[sd.path] = QueuePolicy.RESERVE_BYTES + 1_500
        assertThat(manager.enqueue(packEntry(id = "a"), internal, true)).isEqualTo(EnqueueResult.Queued)
        assertThat(manager.enqueue(packEntry(id = "b"), internal, true)).isEqualTo(EnqueueResult.NoSpace(500))
        assertThat(manager.enqueue(packEntry(id = "b"), sd, true)).isEqualTo(EnqueueResult.Queued)
    }

    @Test fun cancelDeletesPartAndRecord() = test {
        manager.enqueue(packEntry(), internal, true)
        val record = records().single()
        record.partFile.parentFile!!.mkdirs()
        record.partFile.writeText("half")
        manager.cancel("med")
        assertThat(records()).isEmpty()
        assertThat(record.partFile.exists()).isFalse()
        assertThat(scheduler.cancelled).containsExactly("med")
    }

    @Test fun cancelKeepsTheInstalledVersion() = test {
        installed(packEntry(sha256 = SHA_1), "/x/med_2026-09-01.zim")
        manager.enqueue(packEntry(sha256 = SHA_2), internal, true)
        manager.cancel("med")
        assertThat(records().map { it.sha256 }).containsExactly(SHA_1)
    }

    @Test fun retryRequeuesAFailedDownload() = test {
        manager.enqueue(packEntry(), internal, true)
        dao.setState("med", SHA_1, DownloadState.FAILED, "Checksum mismatch", 0, 2, 9)
        manager.retry("med")
        val record = records().single()
        assertThat(record.state).isEqualTo(DownloadState.QUEUED)
        assertThat(record.error).isNull()
        assertThat(record.hashFailures).isEqualTo(0)
        assertThat(scheduler.scheduled.last().second).isTrue()
    }

    @Test fun removeDeletesEveryVersionAndFile() = test {
        val old = File(internal, "packs/med_2026-09-01.zim").apply { parentFile!!.mkdirs(); writeText("old") }
        installed(packEntry(sha256 = SHA_1), old.path)
        manager.enqueue(packEntry(sha256 = SHA_2), internal, true)
        val part = records().first { it.sha256 == SHA_2 }.partFile.apply { writeText("new") }
        manager.remove("med")
        assertThat(records()).isEmpty()
        assertThat(old.exists()).isFalse()
        assertThat(part.exists()).isFalse()
        assertThat(scheduler.cancelled).contains("med")
    }

    @Test fun repairRequeuesAnInstalledItem() = test {
        installed(packEntry(), File(internal, "packs/med.zim").path)
        dao.setVerifiedAt("med", SHA_1, 5)
        manager.repair("med")
        val record = records().single()
        assertThat(record.state).isEqualTo(DownloadState.QUEUED)
        assertThat(record.bytesDone).isEqualTo(0)
        assertThat(record.verifiedAt).isNull()
        assertThat(scheduler.scheduled.single().second).isTrue()
    }

    @Test fun cancellingARepairKeepsTheInstalledRecord() = test {
        val file = File(internal, "packs/med.zim").apply { parentFile!!.mkdirs(); writeText("data") }
        installed(packEntry(), file.path)
        manager.repair("med")
        File(file.path + ".part").writeText("half")
        manager.cancel("med")
        val record = records().single()
        assertThat(record.state).isEqualTo(DownloadState.INSTALLED)
        assertThat(record.bytesDone).isEqualTo(record.sizeBytes)
        assertThat(record.verifiedAt).isNull()
        assertThat(file.exists()).isTrue()
        assertThat(File(file.path + ".part").exists()).isFalse()
    }

    @Test fun repairWhileAnUpdateIsActiveDoesNothing() = test {
        installed(packEntry(sha256 = SHA_1), File(internal, "packs/med_2026-09-01.zim").path)
        manager.enqueue(packEntry(sha256 = SHA_2), internal, true)
        val before = records()
        val scheduledBefore = scheduler.scheduled.size
        manager.repair("med")
        assertThat(records()).isEqualTo(before)
        assertThat(scheduler.scheduled).hasSize(scheduledBefore)
    }

    @Test fun setWifiOnlyReschedulesActiveDownloads() = test {
        installed(packEntry(id = "old"), "/x/old.zim")
        manager.enqueue(packEntry(id = "a"), internal, wifiOnly = true)
        manager.enqueue(packEntry(id = "f"), internal, wifiOnly = true)
        dao.setState("f", SHA_1, DownloadState.FAILED, "boom", 0, 0, 9)
        manager.setWifiOnly(false)
        assertThat(records().first { it.itemId == "a" }.wifiOnly).isFalse()
        assertThat(records().first { it.itemId == "old" }.wifiOnly).isTrue()
        assertThat(scheduler.scheduled.last().let { it.first.itemId to it.second }).isEqualTo("a" to true)
        assertThat(scheduler.scheduled.last().first.wifiOnly).isFalse()
        assertThat(records().first { it.itemId == "f" }.wifiOnly).isFalse()
        assertThat(records().first { it.itemId == "f" }.state).isEqualTo(DownloadState.FAILED)
        assertThat(scheduler.scheduled.filter { it.second }.map { it.first.itemId }).containsExactly("a")
    }

    @Test fun recordCheckSetsAndClearsVerifiedAt() = test {
        installed(packEntry(), "/x/med.zim")
        manager.recordCheck("med", SHA_1, verified = true)
        assertThat(records().single().verifiedAt).isEqualTo(7L)
        manager.recordCheck("med", SHA_1, verified = false)
        assertThat(records().single().verifiedAt).isNull()
    }
}
