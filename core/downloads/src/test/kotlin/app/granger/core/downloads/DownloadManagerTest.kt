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
                itemId = entry.id, hash = entry.sha256, kind = ItemKind.PACK, title = entry.title, urls = entry.urls,
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

    @Test fun voicesGoToTheVoicesFolderAsAZip() = test {
        manager.enqueue(voiceEntry(), sd, wifiOnly = true)
        val record = records().single()
        assertThat(record.kind).isEqualTo(ItemKind.VOICE)
        assertThat(record.filePath).isEqualTo(File(sd, "voices/vits-piper-en_US-ljspeech-medium.zip").path)
        assertThat(record.snapshotDate).isEmpty()
        assertThat(VoiceUnpacker.voiceDir(record.file)).isEqualTo(File(sd, "voices/vits-piper-en_US-ljspeech-medium"))
    }

    @Test fun bookTargetsBooksGrangerFolder() = test {
        manager.enqueue(bookEntry(), sd, wifiOnly = true)
        val record = records().single()
        assertThat(record.kind).isEqualTo(ItemKind.BOOK)
        assertThat(record.filePath).isEqualTo(File(sd, "books/granger/gc-emma_2026-10-17.epub").path)
        assertThat(record.snapshotDate).isEqualTo("2026-10-17")
    }

    @Test fun booksCountTowardTheCap() = test {
        cap = 1_500
        assertThat(manager.enqueue(bookEntry(size = 600), internal, true)).isEqualTo(EnqueueResult.Queued)
        assertThat(manager.enqueue(packEntry(size = 1_000), internal, true)).isEqualTo(EnqueueResult.OverCap(1_500, 1_600))
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
        assertThat(records().map { it.hash to it.state })
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

    private fun spec(hash: String = "a".repeat(40), size: Long = 20, capped: Boolean = true) = DownloadSpec(
        itemId = "archive-org:x", kind = ItemKind.BOOK, title = "X", urls = listOf("https://archive.org/download/x/x.epub"),
        sizeBytes = size, hash = hash, hashAlgorithm = HashAlgorithm.SHA1, relativePath = "books/archive-org/x.epub",
        snapshotDate = "2026-10-17", capped = capped,
    )

    @Test fun specIsQueuedAtItsRelativePathWithItsAlgorithm() = test {
        assertThat(manager.enqueue(spec(), sd, true)).isEqualTo(EnqueueResult.Queued)
        val record = records().single()
        assertThat(record.filePath).isEqualTo(File(sd, "books/archive-org/x.epub").path)
        assertThat(record.hashAlgorithm).isEqualTo(HashAlgorithm.SHA1)
        assertThat(record.hash).isEqualTo("a".repeat(40))
        assertThat(record.kind).isEqualTo(ItemKind.BOOK)
        assertThat(record.snapshotDate).isEqualTo("2026-10-17")
        assertThat(scheduler.scheduled.single()).isEqualTo(record to false)
    }

    @Test fun cappedSpecCountsAgainstTheCap() = test {
        cap = 100
        installedPackBytes = 90
        assertThat(manager.enqueue(spec(size = 20), internal, true)).isEqualTo(EnqueueResult.OverCap(100, 110))
    }

    @Test fun uncappedSpecIgnoresTheCap() = test {
        cap = 100
        installedPackBytes = 90
        assertThat(manager.enqueue(spec(size = 20, capped = false), internal, true)).isEqualTo(EnqueueResult.Queued)
    }

    @Test fun specWithTheInstalledHashIsAlreadyInstalled() = test {
        dao.upsert(
            DownloadRecord(
                itemId = "archive-org:x", hash = "a".repeat(40), hashAlgorithm = HashAlgorithm.SHA1, kind = ItemKind.BOOK,
                title = "X", urls = emptyList(), sizeBytes = 20, filePath = "/x/x.epub", snapshotDate = "", wifiOnly = true,
                state = DownloadState.INSTALLED, bytesDone = 20, createdAt = 1, updatedAt = 1,
            ),
        )
        assertThat(manager.enqueue(spec(), internal, true)).isEqualTo(EnqueueResult.AlreadyInstalled)
        assertThat(scheduler.scheduled).isEmpty()
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
        assertThat(records().map { it.hash }).containsExactly(SHA_1)
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
        val part = records().first { it.hash == SHA_2 }.partFile.apply { writeText("new") }
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

    @Test fun cancellingASamePathUpdateKeepsOnlyTheOldVersion() = test {
        val file = File(internal, "models/e2b.litertlm").apply { parentFile!!.mkdirs(); writeText("old model") }
        installed(packEntry(sha256 = SHA_1), file.path)
        dao.upsert(
            DownloadRecord("med", SHA_2, HashAlgorithm.SHA256, ItemKind.PACK, "WikiMed", listOf("https://a.test/x"), 9, file.path, "", null, true,
                DownloadState.QUEUED, 0, createdAt = 2, updatedAt = 2),
        )
        manager.cancel("med")
        assertThat(records().map { it.hash to it.state }).containsExactly(SHA_1 to DownloadState.INSTALLED)
        assertThat(file.readText()).isEqualTo("old model")
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

    @Test fun resumeSchedulesUnfinishedDownloadsWithoutReplacingThem() = test {
        installed(packEntry(id = "old"), "/x/old.zim")
        manager.enqueue(packEntry(id = "a"), internal, wifiOnly = true)
        manager.enqueue(packEntry(id = "f"), internal, wifiOnly = true)
        dao.setState("f", SHA_1, DownloadState.FAILED, "boom", 0, 0, 9)
        manager.enqueueGroup("g", listOf(spec().copy(groupId = "g")), internal, wifiOnly = true)
        val before = scheduler.scheduled.size
        val groupsBefore = scheduler.groups.size
        manager.resume()
        assertThat(scheduler.scheduled.drop(before).map { it.first.itemId to it.second }).containsExactly("a" to false)
        assertThat(scheduler.groups).hasSize(groupsBefore)
    }

    @Test fun recordCheckSetsAndClearsVerifiedAt() = test {
        installed(packEntry(), "/x/med.zim")
        manager.recordCheck("med", SHA_1, verified = true)
        assertThat(records().single().verifiedAt).isEqualTo(7L)
        manager.recordCheck("med", SHA_1, verified = false)
        assertThat(records().single().verifiedAt).isNull()
    }

    // Download groups

    private fun member(n: Int, size: Long = 100, group: String = "g") = DownloadSpec(
        itemId = "$group/%03d".format(n), kind = ItemKind.AUDIO, title = "Album", urls = listOf("https://archive.org/download/x/$n.mp3"),
        sizeBytes = size, hash = n.toString().repeat(40).take(40), hashAlgorithm = HashAlgorithm.SHA1,
        relativePath = "music/archive-org/$group/%02d.mp3".format(n), snapshotDate = "", capped = false, groupId = group,
    )

    private fun fileOf(spec: DownloadSpec, root: File = internal) = File(root, spec.relativePath)

    private suspend fun installedMember(spec: DownloadSpec, root: File = internal) {
        val file = fileOf(spec, root).apply { parentFile!!.mkdirs(); writeText("done") }
        dao.upsert(
            DownloadRecord(
                itemId = spec.itemId, hash = spec.hash, hashAlgorithm = spec.hashAlgorithm, kind = spec.kind, title = spec.title,
                urls = spec.urls, sizeBytes = spec.sizeBytes, filePath = file.path, snapshotDate = "", groupId = spec.groupId,
                wifiOnly = true, state = DownloadState.INSTALLED, bytesDone = spec.sizeBytes, createdAt = 1, updatedAt = 1,
            ),
        )
    }

    @Test fun groupIsQueuedInOrderAsOneChain() = test {
        val specs = listOf(member(0), member(1), member(2))
        assertThat(manager.enqueueGroup("g", specs, internal, wifiOnly = true)).isEqualTo(EnqueueResult.Queued)
        val (group, records, replace) = scheduler.groups.single()
        assertThat(group).isEqualTo("g")
        assertThat(records.map { it.itemId }).containsExactly("g/000", "g/001", "g/002").inOrder()
        assertThat(replace).isFalse()
        assertThat(records().map { it.groupId }.distinct()).containsExactly("g")
        assertThat(records().map { it.kind }.distinct()).containsExactly(ItemKind.AUDIO)
        assertThat(records().map { it.state }.distinct()).containsExactly(DownloadState.QUEUED)
        assertThat(records().first { it.itemId == "g/001" }.filePath).isEqualTo(fileOf(specs[1]).path)
        assertThat(scheduler.scheduled).isEmpty()
        assertThat(dao.forGroup("g").map { it.itemId }).containsExactly("g/000", "g/001", "g/002").inOrder()
    }

    @Test fun groupThatDoesNotFitQueuesNothing() = test {
        free[internal.path] = QueuePolicy.RESERVE_BYTES + 250
        val specs = listOf(member(0), member(1), member(2))
        assertThat(manager.enqueueGroup("g", specs, internal, true)).isEqualTo(EnqueueResult.NoSpace(50))
        assertThat(records()).isEmpty()
        assertThat(scheduler.groups).isEmpty()
        assertThat(scheduler.scheduled).isEmpty()
    }

    @Test fun groupSpaceCheckCountsOtherQueuedDownloadsOnTheVolume() = test {
        free[internal.path] = QueuePolicy.RESERVE_BYTES + 1_150
        manager.enqueue(packEntry(size = 1_000), internal, true)
        assertThat(manager.enqueueGroup("g", listOf(member(0), member(1)), internal, true)).isEqualTo(EnqueueResult.NoSpace(50))
        assertThat(manager.enqueueGroup("g", listOf(member(0)), internal, true)).isEqualTo(EnqueueResult.Queued)
    }

    @Test fun groupIgnoresTheStorageCap() = test {
        cap = 10
        installedPackBytes = 1_000
        assertThat(manager.enqueueGroup("g", listOf(member(0), member(1)), internal, true)).isEqualTo(EnqueueResult.Queued)
    }

    @Test fun groupSkipsFilesAlreadyInstalled() = test {
        val specs = listOf(member(0), member(1), member(2))
        installedMember(specs[1])
        free[internal.path] = QueuePolicy.RESERVE_BYTES + 200
        assertThat(manager.enqueueGroup("g", specs, internal, true)).isEqualTo(EnqueueResult.Queued)
        assertThat(scheduler.groups.single().second.map { it.itemId }).containsExactly("g/000", "g/002").inOrder()
        assertThat(records().first { it.itemId == "g/001" }.state).isEqualTo(DownloadState.INSTALLED)
    }

    @Test fun groupLeavesActiveFilesAsTheyAre() = test {
        manager.enqueueGroup("g", listOf(member(0), member(1)), internal, true)
        dao.setState("g/001", member(1).hash, DownloadState.FAILED, "boom", 0, 0, 9)
        assertThat(manager.enqueueGroup("g", listOf(member(0), member(1), member(2)), internal, true)).isEqualTo(EnqueueResult.Queued)
        assertThat(scheduler.groups.last().second.map { it.itemId }).containsExactly("g/002")
        assertThat(records().first { it.itemId == "g/001" }.state).isEqualTo(DownloadState.FAILED)
    }

    @Test fun groupWithNothingLeftToFetch() = test {
        val specs = listOf(member(0), member(1))
        installedMember(specs[0])
        installedMember(specs[1])
        assertThat(manager.enqueueGroup("g", specs, internal, true)).isEqualTo(EnqueueResult.AlreadyInstalled)
        manager.enqueueGroup("h", listOf(member(0, group = "h")), internal, true)
        assertThat(manager.enqueueGroup("h", listOf(member(0, group = "h")), internal, true)).isEqualTo(EnqueueResult.AlreadyActive)
        assertThat(scheduler.groups.map { it.first }).containsExactly("h")
    }

    @Test fun cancelGroupKeepsInstalledFiles() = test {
        val a = member(0)
        val b = member(1)
        installedMember(a)
        manager.enqueueGroup("g", listOf(a, b), internal, true)
        dao.setState(b.itemId, b.hash, DownloadState.RUNNING, null, 2, 0, 9)
        val part = dao.get(b.itemId, b.hash)!!.partFile.apply { parentFile!!.mkdirs(); writeText("ha") }
        manager.cancelGroup("g")
        assertThat(records().map { it.itemId to it.state }).containsExactly(a.itemId to DownloadState.INSTALLED)
        assertThat(fileOf(a).exists()).isTrue()
        assertThat(part.exists()).isFalse()
        assertThat(scheduler.cancelledGroups).containsExactly("g")
    }

    @Test fun removeGroupDeletesEveryFile() = test {
        val a = member(0)
        val b = member(1)
        installedMember(a)
        manager.enqueueGroup("g", listOf(a, b), internal, true)
        val part = dao.get(b.itemId, b.hash)!!.partFile.apply { parentFile!!.mkdirs(); writeText("ha") }
        val other = member(0, group = "h")
        installedMember(other)
        manager.removeGroup("g")
        assertThat(records().map { it.itemId }).containsExactly(other.itemId)
        assertThat(fileOf(a).exists()).isFalse()
        assertThat(part.exists()).isFalse()
        assertThat(fileOf(other).exists()).isTrue()
        assertThat(scheduler.cancelledGroups).containsExactly("g")
    }

    @Test fun retryGroupRequeuesFailedInOrder() = test {
        val specs = listOf(member(0), member(1), member(2))
        installedMember(specs[0])
        manager.enqueueGroup("g", specs, internal, true)
        dao.setState("g/002", specs[2].hash, DownloadState.FAILED, "boom", 0, 2, 9)
        dao.setState("g/001", specs[1].hash, DownloadState.FAILED, "boom", 0, 1, 9)
        manager.retryGroup("g")
        val (group, records, replace) = scheduler.groups.last()
        assertThat(group).isEqualTo("g")
        assertThat(records.map { it.itemId }).containsExactly("g/001", "g/002").inOrder()
        assertThat(replace).isFalse()
        val after = records().filter { it.itemId != "g/000" }
        assertThat(after.map { it.state }.distinct()).containsExactly(DownloadState.QUEUED)
        assertThat(after.map { it.error }.distinct()).containsExactly(null)
        assertThat(after.map { it.hashFailures }.distinct()).containsExactly(0)
    }

    @Test fun wifiOnlyChangeReschedulesGroupsAsChains() = test {
        val specs = listOf(member(0), member(1), member(2))
        manager.enqueueGroup("g", specs, internal, wifiOnly = true)
        dao.setState("g/000", specs[0].hash, DownloadState.RUNNING, null, 0, 0, 9)
        dao.setState("g/001", specs[1].hash, DownloadState.FAILED, "boom", 0, 0, 9)
        manager.enqueue(packEntry(id = "a"), internal, wifiOnly = true)
        manager.setWifiOnly(false)
        val (group, records, replace) = scheduler.groups.last()
        assertThat(group).isEqualTo("g")
        assertThat(records.map { it.itemId }).containsExactly("g/000", "g/002").inOrder()
        assertThat(records.map { it.wifiOnly }.distinct()).containsExactly(false)
        assertThat(replace).isTrue()
        assertThat(scheduler.groups).hasSize(2)
        assertThat(scheduler.scheduled.filter { it.second }.map { it.first.itemId }).containsExactly("a")
    }

    @Test fun retryOfOneGroupedFileJoinsItsChain() = test {
        val specs = listOf(member(0), member(1))
        manager.enqueueGroup("g", specs, internal, true)
        dao.setState("g/001", specs[1].hash, DownloadState.FAILED, "boom", 0, 0, 9)
        manager.retry("g/001")
        assertThat(scheduler.groups.last().let { it.first to it.second.map { r -> r.itemId } }).isEqualTo("g" to listOf("g/001"))
        assertThat(scheduler.scheduled).isEmpty()
    }
}
