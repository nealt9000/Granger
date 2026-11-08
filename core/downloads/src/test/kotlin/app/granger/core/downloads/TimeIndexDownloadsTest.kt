package app.granger.core.downloads

import app.granger.core.catalog.Catalog
import app.granger.core.catalog.PackEntry
import app.granger.core.catalog.TimeIndexEntry
import app.granger.core.catalog.VectorsEntry
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
class TimeIndexDownloadsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = inMemoryDownloads()
    private val dao = db.downloadDao()
    private val scheduler = FakeScheduler()
    private val dispatcher = StandardTestDispatcher()
    private val manager = DownloadManager(dao, scheduler, io = dispatcher, now = { 7L })
    private val indexes = TimeIndexDownloads(manager, wifiOnly = { true })
    private val sd by lazy { tmp.newFolder("sd") }

    @After fun close() = db.close()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private val SHA_T = "5".repeat(64)
    private val t = TimeIndexEntry("https://github.test/timeindex-1/med.gtime", SHA_T, 700, 42, 1, 1)
    private val med = packEntry().copy(timeIndex = t)
    private fun catalog(vararg packs: PackEntry) = Catalog(1, "2026-11-08T00:00:00Z", packs = packs.toList())

    private suspend fun install(pack: PackEntry, hash: String = pack.sha256) {
        val spec = downloadSpecOf(pack)
        dao.upsert(
            DownloadRecord(
                itemId = pack.id, hash = hash, kind = spec.kind, title = pack.title, urls = pack.urls,
                sizeBytes = pack.sizeBytes, filePath = File(sd, spec.relativePath).path, snapshotDate = spec.snapshotDate,
                wifiOnly = true, state = DownloadState.INSTALLED, bytesDone = pack.sizeBytes, createdAt = 1, updatedAt = 1,
            ),
        )
    }

    private suspend fun reconcile(catalog: Catalog?) = indexes.reconcile(catalog, manager.records.first())
    private suspend fun indexRecords() = manager.records.first().filter { it.kind == ItemKind.TIME_INDEX }

    @Test fun anInstalledPackGetsItsTimeIndexOnItsVolume() = test {
        install(med)
        reconcile(catalog(med))
        val record = indexRecords().single()
        assertThat(record.itemId).isEqualTo("med.timeindex")
        assertThat(record.hash).isEqualTo(SHA_T)
        assertThat(record.filePath).isEqualTo(File(sd, "timeindex/med_555555555555.gtime").path)
        assertThat(record.kind.isCompanion).isTrue()
    }

    @Test fun nothingIsQueuedTwiceOrForAPackStillDownloadingOrAtAnotherSnapshot() = test {
        install(med)
        reconcile(catalog(med))
        reconcile(catalog(med))
        assertThat(indexRecords()).hasSize(1)
        val other = packEntry(id = "hist").copy(timeIndex = t.copy(url = "https://github.test/timeindex-1/hist.gtime"))
        install(other, hash = SHA_2)
        reconcile(catalog(med, other))
        assertThat(indexRecords().map { it.itemId }).containsExactly("med.timeindex")
    }

    @Test fun vectorsAndTheTimeIndexFollowThePackSideBySide() = test {
        val both = med.copy(vectors = VectorsEntry("https://github.test/vectors-1/med.gvec", "3".repeat(64), 4_096, SHA_2, 256, "int8", 1))
        install(both)
        reconcile(catalog(both))
        VectorDownloads(manager) { true }.reconcile(catalog(both), manager.records.first())
        val companions = manager.records.first().filter { it.kind.isCompanion }
        assertThat(companions.map { it.itemId }).containsExactly("med.timeindex", "med.vectors")
        assertThat(companions.map { Companions.parentOf(it.itemId) }.toSet()).containsExactly("med")
    }

    @Test fun removingThePackRemovesItsTimeIndexAndItsFile() = test {
        install(med)
        reconcile(catalog(med))
        val file = indexRecords().single().file.apply { parentFile!!.mkdirs(); writeText("gtime") }
        manager.remove("med")
        reconcile(catalog(med))
        assertThat(indexRecords()).isEmpty()
        assertThat(file.exists()).isFalse()
    }

    @Test fun aNewIndexReplacesTheOldOnceInstalled() = test {
        install(med)
        reconcile(catalog(med))
        val old = indexRecords().single().also { r -> r.file.parentFile!!.mkdirs(); r.file.writeText("v1"); dao.upsert(r.copy(state = DownloadState.INSTALLED)) }
        val SHA_U = "6".repeat(64)
        val newer = med.copy(timeIndex = t.copy(url = "https://github.test/timeindex-2/med.gtime", sha256 = SHA_U))
        reconcile(catalog(newer))
        assertThat(indexRecords().map { it.hash }).containsExactly(SHA_T, SHA_U)
        indexRecords().single { it.hash == SHA_U }.let { r -> r.file.writeText("v2"); dao.upsert(r.copy(state = DownloadState.INSTALLED)) }
        reconcile(catalog(newer))
        assertThat(indexRecords().map { it.hash }).containsExactly(SHA_U)
        assertThat(old.file.exists()).isFalse()
    }

    @Test fun parentOfOnlyNamesTimeIndexItems() {
        assertThat(TimeIndexDownloads.parentOf("med.timeindex")).isEqualTo("med")
        assertThat(TimeIndexDownloads.parentOf("med.vectors")).isNull()
        assertThat(Companions.parentOf("med.vectors")).isEqualTo("med")
        assertThat(Companions.parentOf("med")).isNull()
    }
}
