package app.granger.downloads

import app.granger.FakeCatalogSource
import app.granger.FakeDownloads
import app.granger.FakeLibrary
import app.granger.FakePrefs
import app.granger.FakeVolumes
import app.granger.MainDispatcherRule
import app.granger.catalogOf
import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.CatalogError
import app.granger.core.catalog.CatalogState
import app.granger.core.catalog.Preset
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.EnqueueResult
import app.granger.modelEntry
import app.granger.pack
import app.granger.packEntry
import app.granger.record
import app.granger.volume
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.File

class DownloadsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val med = packEntry("med", category = "Medical")
    private val top = packEntry("top", category = "Reference")
    private val voyage = packEntry("voyage", category = "Regional")
    private val e2b = modelEntry("e2b")
    private val catalog = FakeCatalogSource(
        catalogOf(
            packs = listOf(voyage, top, med),
            models = listOf(e2b),
            presets = listOf(Preset("starter", "Starter", listOf("e2b", "med", "top", "voyage"))),
        ),
    )
    private val downloads = FakeDownloads()
    private val library = FakeLibrary()
    private val volumes = FakeVolumes(listOf(volume("/internal", "Internal storage"), volume("/sd", "SD card")))
    private val prefs = FakePrefs()

    private fun TestScope.viewModel(tier: DeviceTier = DeviceTier.THINK_DEEPER): DownloadsViewModel {
        val vm = DownloadsViewModel(catalog, downloads, library, volumes, prefs, tier, appScope = this, io = main.dispatcher)
        backgroundScope.launch { vm.ui.collect {} }
        testScheduler.advanceUntilIdle()
        return vm
    }

    @Test fun loadsTheCachedCatalogThenRefreshes() = runTest(main.dispatcher) {
        viewModel()
        assertThat(catalog.loads).isEqualTo(1)
        assertThat(catalog.refreshes).isEqualTo(1)
    }

    @Test fun modelsFirstThenCategoriesInTheirOrder() = runTest(main.dispatcher) {
        val ui = viewModel().ui.value
        assertThat(ui.sections.map { it.title }).containsExactly("AI models", "Medical", "Reference", "Regional").inOrder()
        assertThat(ui.loading).isFalse()
        assertThat(ui.sections[1].rows.single().details).isEqualTo("eng · Updated 2026-10-10 · CC BY-SA 4.0")
    }

    @Test fun getQueuesOnTheChosenVolumeWithTheWifiSetting() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.selectVolume("/sd")
        vm.setWifiOnly(false)
        vm.get("med")
        testScheduler.advanceUntilIdle()
        assertThat(downloads.enqueued.single()).isEqualTo("med" to File("/sd"))
        assertThat(prefs.volumePath).isEqualTo("/sd")
        assertThat(prefs.wifiOnly).isFalse()
        assertThat(downloads.calls).contains("wifi:false")
        assertThat(vm.ui.value.sections.first { it.title == "Medical" }.rows.single().status)
            .isEqualTo(ItemStatus.Queued(wifiOnly = false))
    }

    @Test fun aVolumeThatIsGoneFallsBackToTheFirst() = runTest(main.dispatcher) {
        prefs.volumePath = "/sd-that-was-removed"
        val vm = viewModel()
        assertThat(vm.ui.value.selectedVolume).isEqualTo("/internal")
        vm.get("med")
        testScheduler.advanceUntilIdle()
        assertThat(downloads.enqueued.single().second).isEqualTo(File("/internal"))
    }

    @Test fun noSpaceExplainsWhere() = runTest(main.dispatcher) {
        downloads.nextResult = EnqueueResult.NoSpace(2_000_000_000)
        val vm = viewModel()
        vm.get("med")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.notice).contains("Not enough space on Internal storage")
        assertThat(vm.ui.value.notice).contains("2.0 GB")
        vm.dismissNotice()
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.notice).isNull()
    }

    @Test fun overTheCapExplains() = runTest(main.dispatcher) {
        downloads.nextResult = EnqueueResult.OverCap(10_000_000_000, 11_000_000_000)
        val vm = viewModel()
        vm.get("med")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.notice).contains("10 GB")
    }

    @Test fun presetSkipsInstalledAndActiveItems() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("u-med"))
        downloads.list.value = listOf(record("top", DownloadState.RUNNING))
        val vm = viewModel()
        assertThat(vm.ui.value.presets.single().remainingCount).isEqualTo(2)
        assertThat(vm.ui.value.presets.single().remainingBytes).isEqualTo(3_000)
        vm.getPreset("starter")
        testScheduler.advanceUntilIdle()
        assertThat(downloads.enqueued.map { it.first }).containsExactly("e2b", "voyage").inOrder()
        assertThat(vm.ui.value.notice).isEqualTo("Queued 2 downloads.")
    }

    @Test fun presetWithNothingLeftSaysSo() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("u-med"), pack("u-top"), pack("u-voyage"))
        downloads.list.value = listOf(record("e2b", DownloadState.INSTALLED, sha256 = e2b.sha256))
        val vm = viewModel()
        vm.getPreset("starter")
        testScheduler.advanceUntilIdle()
        assertThat(downloads.enqueued).isEmpty()
        assertThat(vm.ui.value.notice).contains("already")
    }

    @Test fun presetStopsAtTheFirstSpaceProblem() = runTest(main.dispatcher) {
        downloads.nextResult = EnqueueResult.NoSpace(500)
        val vm = viewModel()
        vm.getPreset("starter")
        testScheduler.advanceUntilIdle()
        assertThat(downloads.enqueued).hasSize(1)
        assertThat(vm.ui.value.notice).contains("Not enough space")
    }

    @Test fun removeDeletesThePackThenItsRecords() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("u-med"))
        val vm = viewModel()
        vm.remove("med")
        testScheduler.advanceUntilIdle()
        assertThat(library.removed).containsExactly("u-med")
        assertThat(downloads.calls).contains("remove:med")
    }

    @Test fun removingAModelOnlyTouchesDownloads() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.remove("e2b")
        testScheduler.advanceUntilIdle()
        assertThat(library.removed).isEmpty()
        assertThat(downloads.calls).contains("remove:e2b")
    }

    @Test fun cancelAndRetryPassThrough() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.cancel("med")
        vm.retry("top")
        testScheduler.advanceUntilIdle()
        assertThat(downloads.calls).containsAtLeast("cancel:med", "retry:top")
    }

    @Test fun firstRunOfflineExplains() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = null, error = CatalogError.NETWORK)
        val ui = viewModel().ui.value
        assertThat(ui.loading).isFalse()
        assertThat(ui.catalogProblem).contains("Connect to the internet")
        assertThat(ui.sections).isEmpty()
    }

    @Test fun aRejectedCatalogUpdateIsExplainedButTheOldOneShows() = runTest(main.dispatcher) {
        catalog.mutable.value = catalog.mutable.value.copy(error = CatalogError.BAD_SIGNATURE)
        val ui = viewModel().ui.value
        assertThat(ui.catalogProblem).contains("security check")
        assertThat(ui.sections).isNotEmpty()
    }

    @Test fun volumeMeterCountsWhatIsStillToDownload() = runTest(main.dispatcher) {
        downloads.list.value = listOf(record("med", DownloadState.RUNNING, filePath = "/sd/packs/med.zim", sizeBytes = 1_000, bytesDone = 400))
        val ui = viewModel().ui.value
        assertThat(ui.volumes.first { it.path == "/sd" }.queuedBytes).isEqualTo(600)
        assertThat(ui.volumes.first { it.path == "/internal" }.queuedBytes).isEqualTo(0)
    }

    @Test fun weakPhonesCannotGetModels() = runTest(main.dispatcher) {
        val ui = viewModel(tier = DeviceTier.LIBRARY_ONLY).ui.value
        assertThat(ui.sections.first().rows.single().status).isEqualTo(ItemStatus.NeedsMoreMemory)
        assertThat(ui.presets.single().remainingCount).isEqualTo(3)
    }
}
