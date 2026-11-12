package app.granger.core.downloads

import app.granger.core.catalog.Catalog
import app.granger.core.catalog.ModelRole
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Plan SQ Phase 6: the embedder follows the first search vectors, once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EmbedderDownloadsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = inMemoryDownloads()
    private val dao = db.downloadDao()
    private val dispatcher = StandardTestDispatcher()
    private val manager = DownloadManager(dao, FakeScheduler(), io = dispatcher, now = { 7L })
    private var followed = false
    private val follow = EmbedderDownloads(manager, wifiOnly = { true }, followed = { followed }, markFollowed = { followed = true })
    private val sd by lazy { tmp.newFolder("sd") }
    private val embedder = modelEntry(id = "embeddinggemma", size = 1_000).copy(role = ModelRole.EMBEDDER, fileName = "eg.tflite")
    private val catalog = Catalog(1, "2026-11-11T00:00:00Z", models = listOf(modelEntry(), embedder))

    @After fun close() = db.close()

    private suspend fun record(itemId: String, kind: ItemKind, path: String) = dao.upsert(
        DownloadRecord(
            itemId = itemId, hash = SHA_2, kind = kind, title = itemId, urls = listOf("https://a.test/$itemId"),
            sizeBytes = 10, filePath = File(sd, path).path, snapshotDate = "", wifiOnly = true,
            state = DownloadState.QUEUED, bytesDone = 0, createdAt = 1, updatedAt = 1,
        ),
    )

    private suspend fun reconcile() = follow.reconcile(catalog, manager.records.first())

    @Test fun theFirstVectorsBringTheEmbedderOnTheirVolume() = runTest(dispatcher) {
        record("med.vectors", ItemKind.VECTORS, "vectors/med_333.gvec")
        reconcile()
        val model = manager.records.first().single { it.itemId == "embeddinggemma" }
        assertThat(model.filePath).isEqualTo(File(sd, "models/eg.tflite").path)
        assertThat(followed).isTrue()
    }

    @Test fun noVectorsNoEmbedder() = runTest(dispatcher) {
        record("med", ItemKind.PACK, "packs/med.zim")
        reconcile()
        assertThat(manager.records.first().map { it.itemId }).containsExactly("med")
        assertThat(followed).isFalse()
    }

    @Test fun onceFollowedARemovedEmbedderStaysRemoved() = runTest(dispatcher) {
        followed = true
        record("med.vectors", ItemKind.VECTORS, "vectors/med_333.gvec")
        reconcile()
        assertThat(manager.records.first().map { it.itemId }).containsExactly("med.vectors")
    }

    @Test fun anEmbedderAlreadyOnRecordIsJustMarked() = runTest(dispatcher) {
        record("embeddinggemma", ItemKind.MODEL, "models/eg.tflite")
        record("med.vectors", ItemKind.VECTORS, "vectors/med_333.gvec")
        reconcile()
        assertThat(manager.records.first().count { it.itemId == "embeddinggemma" }).isEqualTo(1)
        assertThat(followed).isTrue()
    }
}
