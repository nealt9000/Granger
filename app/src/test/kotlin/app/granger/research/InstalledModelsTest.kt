package app.granger.research

import app.granger.core.ai.InstalledModels
import app.granger.core.catalog.ModelRole
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.ItemKind
import app.granger.modelEntry
import app.granger.record
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class InstalledModelsTest {
    private val models = listOf(
        modelEntry("e2b", ModelRole.SMALL),
        modelEntry("e4b", ModelRole.LARGE),
        modelEntry("gemma-embed", ModelRole.EMBEDDER),
    )

    private fun model(id: String, state: DownloadState = DownloadState.INSTALLED, path: String = "/m/$id.litertlm", updatedAt: Long = 1) =
        record(id, state, kind = ItemKind.MODEL, filePath = path).copy(updatedAt = updatedAt)

    @Test fun mapsRolesByCatalogId() {
        val installed = installedModelsOf(
            listOf(model("e2b"), model("e4b"), model("gemma-embed"), record("e2b-pack", DownloadState.INSTALLED)),
            models,
        ) { true }
        assertThat(installed).isEqualTo(
            InstalledModels(File("/m/e2b.litertlm"), File("/m/e4b.litertlm"), File("/m/gemma-embed.litertlm")),
        )
    }

    @Test fun ignoresUnfinishedAndMissingFiles() {
        val installed = installedModelsOf(
            listOf(model("e2b", DownloadState.RUNNING), model("e4b"), model("gemma-embed"), model("unknown")),
            models,
        ) { it.path != "/m/e4b.litertlm" }
        assertThat(installed).isEqualTo(InstalledModels(embedder = File("/m/gemma-embed.litertlm")))
    }

    @Test fun newestInstalledVersionWins() {
        val installed = installedModelsOf(
            listOf(model("e2b", path = "/m/old.litertlm", updatedAt = 5), model("e2b", path = "/m/new.litertlm", updatedAt = 9)),
            models,
        ) { true }
        assertThat(installed.small).isEqualTo(File("/m/new.litertlm"))
    }
}
