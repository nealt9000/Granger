package app.granger.research

import app.granger.FakeCatalogSource
import app.granger.FakeDownloads
import app.granger.FakePrefs
import app.granger.FakeVolumes
import app.granger.catalogOf
import app.granger.core.catalog.CatalogState
import app.granger.core.catalog.ModelRole
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.ItemKind
import app.granger.modelEntry
import app.granger.record
import app.granger.volume
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LibrarianTest {
    private val e2b = modelEntry("gemma-4-e2b-gpu", ModelRole.SMALL, size = 2_000, sha256 = "a".repeat(64))
    private val e4b = modelEntry("gemma-4-e4b-gpu", ModelRole.LARGE, size = 3_000, sha256 = "b".repeat(64))
    private val embedder = modelEntry("embeddinggemma", ModelRole.EMBEDDER, size = 200, sha256 = "c".repeat(64))
    private val catalog = FakeCatalogSource(catalogOf(models = listOf(e4b, e2b, embedder)))
    private val downloads = FakeDownloads()
    private val volumes = FakeVolumes(listOf(volume("/internal", "Internal storage", free = 10_000, total = 64_000)))

    private fun librarian(dense: Boolean = true) = Librarian(catalog, downloads, volumes, FakePrefs(), dense, io = Dispatchers.Unconfined)

    @Test fun theLibrarianIsTheAnsweringModelPlusTheEmbedderWhenDense() = runTest {
        val ui = librarian().ui.first()!!
        assertThat(ui.items.map { it.id }).containsExactly("gemma-4-e2b-gpu", "embeddinggemma").inOrder()
        assertThat(ui.bytes).isEqualTo(2_200)
        assertThat(ui.downloading).isFalse()
        assertThat(librarian(dense = false).ui.first()!!.items.map { it.id }).containsExactly("gemma-4-e2b-gpu")
    }

    @Test fun noAnsweringModelInTheCatalogMeansNoLibrarian() = runTest {
        catalog.mutable.value = CatalogState(catalog = catalogOf(models = listOf(embedder)))
        assertThat(librarian().ui.first()).isNull()
    }

    @Test fun progressCountsInstalledAndRunningBytes() = runTest {
        downloads.list.value = listOf(
            record("gemma-4-e2b-gpu", DownloadState.RUNNING, sha256 = "a".repeat(64), kind = ItemKind.MODEL).copy(bytesDone = 500),
            record("embeddinggemma", DownloadState.INSTALLED, sha256 = "c".repeat(64), kind = ItemKind.MODEL),
        )
        val ui = librarian().ui.first()!!
        assertThat(ui.bytesDone).isEqualTo(700)
        assertThat(ui.downloading).isTrue()
        assertThat(ui.failed).isFalse()
    }

    @Test fun aStoppedDownloadIsRetriedAndTheRestQueued() = runTest {
        downloads.list.value = listOf(record("gemma-4-e2b-gpu", DownloadState.FAILED, sha256 = "a".repeat(64), kind = ItemKind.MODEL))
        val l = librarian()
        assertThat(l.ui.first()!!.failed).isTrue()
        assertThat(l.get()).isNull()
        assertThat(downloads.calls).contains("retry:gemma-4-e2b-gpu")
        assertThat(downloads.enqueued.map { it.first }).containsExactly("embeddinggemma")
    }
}
