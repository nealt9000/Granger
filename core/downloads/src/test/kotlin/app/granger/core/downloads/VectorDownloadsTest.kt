package app.granger.core.downloads

import app.granger.core.catalog.Catalog
import app.granger.core.catalog.CatalogItem
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
class VectorDownloadsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = inMemoryDownloads()
    private val dao = db.downloadDao()
    private val scheduler = FakeScheduler()
    private val dispatcher = StandardTestDispatcher()
    private val manager = DownloadManager(dao, scheduler, installedPackBytes = { 0L }, io = dispatcher, now = { 7L })
    private val vectors = VectorDownloads(manager, wifiOnly = { true })
    private val sd by lazy { tmp.newFolder("sd") }

    @After fun close() = db.close()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private val SHA_V = "3".repeat(64)
    private val v = VectorsEntry("https://github.test/vectors-1/med.gvec", SHA_V, 4_096, SHA_2, 256, "int8", 1)
    private val med = packEntry().copy(vectors = v)
    private val emma = bookEntry().copy(vectors = v.copy(url = "https://github.test/vectors-1/emma.gvec"))
    private fun catalog(vararg items: CatalogItem) = Catalog(
        1, "2026-10-23T00:00:00Z",
        packs = items.filterIsInstance<app.granger.core.catalog.PackEntry>(),
        books = items.filterIsInstance<app.granger.core.catalog.BookEntry>(),
    )

    private suspend fun install(item: CatalogItem, hash: String = item.sha256) {
        val spec = downloadSpecOf(item)
        dao.upsert(
            DownloadRecord(
                itemId = item.id, hash = hash, kind = spec.kind, title = item.title, urls = item.urls,
                sizeBytes = item.sizeBytes, filePath = File(sd, spec.relativePath).path, snapshotDate = spec.snapshotDate,
                wifiOnly = true, state = DownloadState.INSTALLED, bytesDone = item.sizeBytes, createdAt = 1, updatedAt = 1,
            ),
        )
    }

    private suspend fun reconcile(catalog: Catalog?) = vectors.reconcile(catalog, manager.records.first())

    private suspend fun vectorRecords() = manager.records.first().filter { it.kind == ItemKind.VECTORS }

    @Test fun anInstalledPackGetsItsVectorsOnItsVolume() = test {
        install(med)
        reconcile(catalog(med))
        val record = vectorRecords().single()
        assertThat(record.itemId).isEqualTo("med.vectors")
        assertThat(record.hash).isEqualTo(SHA_V)
        assertThat(record.sizeBytes).isEqualTo(4_096)
        assertThat(record.urls).containsExactly("https://github.test/vectors-1/med.gvec")
        assertThat(record.filePath).isEqualTo(File(sd, "vectors/med_333333333333.gvec").path)
        assertThat(record.wifiOnly).isTrue()
        assertThat(scheduler.scheduled.map { it.first.itemId }).containsExactly("med.vectors")
    }

    @Test fun aBookGetsItsVectorsToo() = test {
        install(emma)
        reconcile(catalog(emma))
        assertThat(vectorRecords().single().itemId).isEqualTo("gc-emma.vectors")
    }

    @Test fun nothingIsQueuedTwice() = test {
        install(med)
        reconcile(catalog(med))
        reconcile(catalog(med))
        assertThat(vectorRecords()).hasSize(1)
        assertThat(scheduler.scheduled).hasSize(1)
    }

    @Test fun anItemStillDownloadingOrAtAnotherSnapshotGetsNone() = test {
        manager.enqueue(med, sd, wifiOnly = true)
        install(emma, hash = SHA_2)
        reconcile(catalog(med, emma))
        assertThat(vectorRecords()).isEmpty()
    }

    @Test fun anEntryWithoutVectorsOrNoCatalogQueuesNothing() = test {
        install(packEntry())
        reconcile(catalog(packEntry()))
        install(med)
        reconcile(null)
        assertThat(vectorRecords()).isEmpty()
    }

    @Test fun anInstalledItemGainsVectorsWhenTheCatalogAddsThem() = test {
        install(med)
        reconcile(catalog(packEntry()))
        assertThat(vectorRecords()).isEmpty()
        reconcile(catalog(med))
        assertThat(vectorRecords()).hasSize(1)
    }

    @Test fun removingTheItemRemovesItsVectorsAndTheirFile() = test {
        install(med)
        reconcile(catalog(med))
        val file = vectorRecords().single().file.apply { parentFile!!.mkdirs(); writeText("vectors") }
        manager.remove("med")
        reconcile(catalog(med))
        assertThat(vectorRecords()).isEmpty()
        assertThat(file.exists()).isFalse()
    }

    @Test fun parentOfOnlyNamesVectorsItems() {
        assertThat(VectorDownloads.parentOf("med.vectors")).isEqualTo("med")
        assertThat(VectorDownloads.parentOf("med")).isNull()
    }
}
