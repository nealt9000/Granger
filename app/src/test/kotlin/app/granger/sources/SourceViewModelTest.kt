package app.granger.sources

import app.granger.FakeBookshelf
import app.granger.FakeCatalogSource
import app.granger.bookEntry
import app.granger.SHA_B
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

class SourceViewModelTest {
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
    private val bookshelf = FakeBookshelf()
    private val volumes = FakeVolumes(listOf(volume("/internal", "Internal storage"), volume("/sd", "SD card")))
    private val prefs = FakePrefs()

    private fun TestScope.viewModel(
        scope: CatalogScope = CatalogScope.GRANGER,
        tier: DeviceTier = DeviceTier.THINK_DEEPER,
    ): SourceViewModel {
        val vm = SourceViewModel(scope, catalog, downloads, library, bookshelf, volumes, prefs, tier, appScope = this, io = main.dispatcher)
        backgroundScope.launch { vm.ui.collect {} }
        testScheduler.advanceUntilIdle()
        return vm
    }

    @Test fun loadsTheCachedCatalogThenRefreshes() = runTest(main.dispatcher) {
        viewModel()
        assertThat(catalog.loads).isEqualTo(1)
        assertThat(catalog.refreshes).isEqualTo(1)
    }

    @Test fun categoriesInTheirOrder() = runTest(main.dispatcher) {
        val ui = viewModel().ui.value
        assertThat(ui.sections.map { it.title }).containsExactly("Medical", "Reference", "Regional").inOrder()
        assertThat(ui.loading).isFalse()
        assertThat(ui.sections[0].rows.single().details).isEqualTo("eng · Updated 2026-10-10 · CC BY-SA 4.0")
    }

    @Test fun aPackRowCarriesItsCatalogDescription() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = catalogOf(listOf(packEntry("top", description = "The best 50,000 articles"))))
        assertThat(viewModel().ui.value.sections.flatMap { it.rows }.single().description).isEqualTo("The best 50,000 articles")
    }

    @Test fun nonCommercialPackShowsProvidedByKiwix() = runTest(main.dispatcher) {
        val ifixit = packEntry("ifixit", category = "How-to", license = "CC BY-NC-SA 3.0", nonCommercial = true)
        val vm = SourceViewModel(
            CatalogScope.GRANGER, FakeCatalogSource(catalogOf(packs = listOf(ifixit, med))), downloads, library, bookshelf,
            volumes, prefs, DeviceTier.THINK_DEEPER, appScope = this, io = main.dispatcher,
        )
        backgroundScope.launch { vm.ui.collect {} }
        testScheduler.advanceUntilIdle()
        val rows = vm.ui.value.sections.flatMap { it.rows }.associateBy { it.id }
        assertThat(rows.getValue("ifixit").details).isEqualTo("eng · Updated 2026-10-10 · CC BY-NC-SA 3.0 · provided by Kiwix")
        assertThat(rows.getValue("med").details).isEqualTo("eng · Updated 2026-10-10 · CC BY-SA 4.0")
    }

    @Test fun modelsScopeListsTheModelsSection() = runTest(main.dispatcher) {
        val ui = viewModel(CatalogScope.MODELS).ui.value
        assertThat(ui.sections.map { it.title }).containsExactly("AI models")
    }

    @Test fun onlyItemsInScopeAreListed() = runTest(main.dispatcher) {
        val kiwixPack = packEntry("wiki", category = "Reference", urls = listOf("https://download.kiwix.org/zim/wiki.zim"))
        catalog.mutable.value = CatalogState(catalog = catalogOf(listOf(med, kiwixPack), listOf(e2b)))
        fun ids(scope: CatalogScope) = viewModel(scope).ui.value.sections.flatMap { it.rows }.map { it.id }
        assertThat(ids(CatalogScope.GRANGER)).containsExactly("med")
        assertThat(ids(CatalogScope.KIWIX)).containsExactly("wiki")
        assertThat(ids(CatalogScope.MODELS)).containsExactly("e2b")
    }

    @Test fun presetsOnlyOnGranger() = runTest(main.dispatcher) {
        assertThat(viewModel(CatalogScope.GRANGER).ui.value.presets).hasSize(1)
        assertThat(viewModel(CatalogScope.KIWIX).ui.value.presets).isEmpty()
        assertThat(viewModel(CatalogScope.MODELS).ui.value.presets).isEmpty()
    }

    private fun SourceViewModel.rowIds() = ui.value.sections.flatMap { it.rows }.map { it.id }

    @Test fun browseShowsEverythingInScope() = runTest(main.dispatcher) {
        assertThat(viewModel().apply { setTab(SourceTab.BROWSE) }.rowIds()).containsExactly("med", "top", "voyage")
    }

    @Test fun installedTabShowsInstalledUpdatesAndActive() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("u-med"))
        downloads.list.value = listOf(
            record("top", DownloadState.RUNNING),
            record("voyage", DownloadState.INSTALLED, sha256 = SHA_B),
        )
        val vm = viewModel()
        vm.setTab(SourceTab.INSTALLED)
        testScheduler.advanceUntilIdle()
        // med is installed, top is downloading, voyage is installed from an older snapshot.
        assertThat(vm.rowIds()).containsExactly("med", "top", "voyage")
        library.packList.value = emptyList()
        downloads.list.value = emptyList()
        testScheduler.advanceUntilIdle()
        assertThat(vm.rowIds()).isEmpty()
    }

    @Test fun updatesTabShowsOnlyUpdates() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("u-med"))
        downloads.list.value = listOf(
            record("top", DownloadState.RUNNING),
            record("voyage", DownloadState.INSTALLED, sha256 = SHA_B),
        )
        val vm = viewModel()
        vm.setTab(SourceTab.UPDATES)
        testScheduler.advanceUntilIdle()
        assertThat(vm.rowIds()).containsExactly("voyage")
    }

    @Test fun theSearchFieldTextUpdatesSynchronously() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.setQuery("f"); vm.setQuery("fi")
        assertThat(vm.searchText).isEqualTo("fi")
    }

    @Test fun queryFiltersTitleAndDescription() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(
            catalog = catalogOf(listOf(med, packEntry("top", description = "First aid for the field"), voyage)),
        )
        val vm = viewModel()
        vm.setQuery("PACK MED")
        testScheduler.advanceUntilIdle()
        assertThat(vm.rowIds()).containsExactly("med")
        vm.setQuery("first AID")
        testScheduler.advanceUntilIdle()
        assertThat(vm.rowIds()).containsExactly("top")
        vm.setQuery("")
        testScheduler.advanceUntilIdle()
        assertThat(vm.rowIds()).hasSize(3)
    }

    @Test fun getUsesTheChoicesSettingsMadeAfterTheScreenWasBuilt() = runTest(main.dispatcher) {
        val vm = viewModel()
        prefs.volumePath = "/sd"
        prefs.wifiOnly = false
        vm.get("med")
        testScheduler.advanceUntilIdle()
        assertThat(downloads.enqueued.single()).isEqualTo("med" to File("/sd"))
        assertThat(vm.ui.value.selectedVolume).isEqualTo("/sd")
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

    @Test fun overTheCapTalksAboutDownloadsNotPacks() = runTest(main.dispatcher) {
        downloads.nextResult = EnqueueResult.OverCap(10_000_000_000, 11_000_000_000)
        val vm = viewModel()
        vm.get("med")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.notice).contains("your downloads")
        assertThat(vm.ui.value.notice).doesNotContain("packs")
    }

    @Test fun grangerListsBooksByCategory() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(
            catalog = catalogOf(
                packs = listOf(med),
                books = listOf(bookEntry("emma", "Novels"), bookEntry("hamlet", "Plays"), bookEntry("persuasion", "Novels")),
            ),
        )
        val ui = viewModel().ui.value
        assertThat(ui.sections.map { it.title }).containsExactly("Medical", "Novels", "Plays").inOrder()
        val novels = ui.sections.first { it.title == "Novels" }.rows
        assertThat(novels.map { it.id }).containsExactly("emma", "persuasion").inOrder()
        assertThat(novels.first().details).isEqualTo("An Author · Public domain")
        assertThat(viewModel(CatalogScope.KIWIX).ui.value.sections.flatMap { it.rows }).isEmpty()
    }

    @Test fun classicsPresetComesFirst() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(
            catalog = catalogOf(
                packs = listOf(med),
                books = listOf(bookEntry("emma")),
                presets = listOf(
                    Preset("starter", "Starter", listOf("med")),
                    Preset("granger-classics", "Granger Classics", listOf("emma")),
                ),
            ),
        )
        assertThat(viewModel().ui.value.presets.map { it.id }).containsExactly("granger-classics", "starter").inOrder()
    }

    @Test fun installedTabIncludesBooks() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = catalogOf(books = listOf(bookEntry("emma"), bookEntry("hamlet"))))
        downloads.list.value = listOf(record("emma", DownloadState.INSTALLED, kind = app.granger.core.downloads.ItemKind.BOOK))
        val vm = viewModel()
        vm.setTab(SourceTab.INSTALLED)
        testScheduler.advanceUntilIdle()
        assertThat(vm.rowIds()).containsExactly("emma")
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
        val vm = viewModel(CatalogScope.MODELS)
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
        val models = viewModel(CatalogScope.MODELS, DeviceTier.LIBRARY_ONLY).ui.value
        assertThat(models.sections.single().rows.single().status).isEqualTo(ItemStatus.NeedsMoreMemory)
        val granger = viewModel(tier = DeviceTier.LIBRARY_ONLY).ui.value
        assertThat(granger.presets.single().remainingCount).isEqualTo(3)
    }
}
