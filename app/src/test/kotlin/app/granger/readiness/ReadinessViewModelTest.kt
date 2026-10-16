package app.granger.readiness

import app.granger.FakeDownloads
import app.granger.FakeLibrary
import app.granger.FakeVolumes
import app.granger.MainDispatcherRule
import app.granger.core.ai.DeviceTier
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Integrity
import app.granger.core.downloads.ItemKind
import app.granger.core.library.PackStatus
import app.granger.pack
import app.granger.record
import app.granger.volume
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ReadinessViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val downloads = FakeDownloads()
    private val library = FakeLibrary()
    private val volumes = FakeVolumes(listOf(volume("/internal", "Internal storage", free = 9_000, total = 64_000)))
    private val quick = mutableMapOf<String, Integrity>()
    private var fullResult = CompletableDeferred<Integrity>()

    private val med = record("med", DownloadState.INSTALLED, filePath = "/internal/packs/med.zim")
    private val e2b = record("e2b", DownloadState.INSTALLED, kind = ItemKind.MODEL, filePath = "/internal/models/e2b.litertlm")
    private fun key(r: DownloadRecord) = "${r.itemId}@${r.sha256}"

    private fun TestScope.viewModel(): ReadinessViewModel {
        val vm = ReadinessViewModel(
            downloads, library, volumes, DeviceTier.THINK_DEEPER, totalRamBytes = 7_974_752_256, appScope = this,
            io = main.dispatcher,
            quick = { quick[it.itemId] ?: Integrity.SIZE_OK },
            full = { _, progress ->
                progress(0.5f)
                fullResult.await()
            },
        )
        backgroundScope.launch { vm.ui.collect {} }
        testScheduler.advanceUntilIdle()
        return vm
    }

    @Test fun showsTierRamAndVolumes() = runTest(main.dispatcher) {
        val ui = viewModel().ui.value
        assertThat(ui.loading).isFalse()
        assertThat(ui.tier).isEqualTo(DeviceTier.THINK_DEEPER)
        assertThat(ui.totalRamBytes).isEqualTo(7_974_752_256)
        assertThat(ui.volumes.single().freeBytes).isEqualTo(9_000)
    }

    @Test fun installedFilesGetTheQuickCheck() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med, e2b, record("other", DownloadState.RUNNING))
        quick["e2b"] = Integrity.VERIFIED
        quick["med"] = Integrity.INCOMPLETE
        val ui = viewModel().ui.value
        assertThat(ui.models.single().status).isEqualTo(CheckStatus.VERIFIED)
        val row = ui.packs.single()
        assertThat(row.status).isEqualTo(CheckStatus.INCOMPLETE)
        assertThat(row.canRepair).isTrue()
        assertThat(row.snapshotDate).isEqualTo("2026-10-10")
    }

    @Test fun aPackTheLibraryCannotOpenIsDamaged() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med)
        library.packList.value = listOf(pack("u-med", status = PackStatus.CORRUPT).copy(filePath = med.filePath, title = "WikiMed"))
        val row = viewModel().ui.value.packs.single()
        assertThat(row.status).isEqualTo(CheckStatus.DAMAGED)
        assertThat(row.title).isEqualTo("WikiMed")
        assertThat(row.canRepair).isTrue()
    }

    @Test fun packsAddedByHandAreListedButNotVerifiable() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("u-hand", title = "Pushed"), pack("u-gone", status = PackStatus.MISSING))
        val rows = viewModel().ui.value.packs
        assertThat(rows.map { it.title to it.status })
            .containsExactly("Pushed" to CheckStatus.NOT_FROM_CATALOG, "u-gone" to CheckStatus.MISSING)
        assertThat(rows.none { it.canVerify || it.canRepair }).isTrue()
    }

    @Test fun verifyShowsProgressThenTheResult() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med)
        val vm = viewModel()
        vm.verify(key(med))
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.packs.single().progress).isEqualTo(0.5f)
        assertThat(vm.ui.value.packs.single().canVerify).isFalse()

        fullResult.complete(Integrity.CORRUPTED)
        testScheduler.advanceUntilIdle()
        val row = vm.ui.value.packs.single()
        assertThat(row.progress).isNull()
        assertThat(row.status).isEqualTo(CheckStatus.CORRUPTED)
        assertThat(row.canRepair).isTrue()
        assertThat(downloads.calls).contains("check:med:false")
    }

    @Test fun aPassingCheckIsRecorded() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med)
        fullResult.complete(Integrity.VERIFIED)
        val vm = viewModel()
        vm.verify(key(med))
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.packs.single().status).isEqualTo(CheckStatus.VERIFIED)
        assertThat(downloads.calls).contains("check:med:true")
    }

    @Test fun repairDownloadsAgain() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med)
        val vm = viewModel()
        vm.repair(key(med))
        testScheduler.advanceUntilIdle()
        assertThat(downloads.calls).contains("repair:med")
    }
}
