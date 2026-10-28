package app.granger.sources

import app.granger.DENSE_RETRIEVAL
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
import app.granger.core.catalog.Domain
import app.granger.core.catalog.Preset
import app.granger.core.catalog.Readiness
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.EnqueueResult
import app.granger.modelEntry
import app.granger.core.catalog.ModelRole
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
        tier: DeviceTier = DeviceTier.ASK,
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
            volumes, prefs, DeviceTier.ASK, appScope = this, io = main.dispatcher,
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

    @Test fun theEmbedderSaysWhetherResearchUsesIt() = runTest(main.dispatcher) {
        val vm = SourceViewModel(
            CatalogScope.MODELS, FakeCatalogSource(catalogOf(models = listOf(e2b, modelEntry("gemma-embed", ModelRole.EMBEDDER)))),
            downloads, library, bookshelf, volumes, prefs, DeviceTier.ASK, appScope = this, io = main.dispatcher,
        )
        backgroundScope.launch { vm.ui.collect {} }
        testScheduler.advanceUntilIdle()
        val rows = vm.ui.value.sections.flatMap { it.rows }.associateBy { it.id }
        assertThat(rows.getValue("gemma-embed").description).isEqualTo(if (DENSE_RETRIEVAL) "Used by Ask" else "Not used yet")
        assertThat(rows.getValue("e2b").description).isEmpty()
    }

    @Test fun theLargeModelSaysItIsNotUsed() = runTest(main.dispatcher) {
        val vm = SourceViewModel(
            CatalogScope.MODELS, FakeCatalogSource(catalogOf(models = listOf(e2b, modelEntry("e4b", ModelRole.LARGE)))),
            downloads, library, bookshelf, volumes, prefs, DeviceTier.ASK, appScope = this, io = main.dispatcher,
        )
        backgroundScope.launch { vm.ui.collect {} }
        testScheduler.advanceUntilIdle()
        val rows = vm.ui.value.sections.flatMap { it.rows }.associateBy { it.id }
        assertThat(rows.getValue("e4b").description).isEqualTo("Not used")
        assertThat(rows.getValue("e4b").status).isEqualTo(ItemStatus.NeedsMoreMemory)
        assertThat(rows.getValue("e2b").description).isEmpty()
    }

    @Test fun onlyItemsInScopeAreListed() = runTest(main.dispatcher) {
        val kiwixPack = packEntry("wiki", category = "Reference", urls = listOf("https://download.kiwix.org/zim/wiki.zim"))
        catalog.mutable.value = CatalogState(catalog = catalogOf(listOf(med, kiwixPack), listOf(e2b)))
        fun ids(scope: CatalogScope) = viewModel(scope).ui.value.sections.flatMap { it.rows }.map { it.id }
        assertThat(ids(CatalogScope.GRANGER)).containsExactly("med")
        assertThat(ids(CatalogScope.KIWIX)).containsExactly("wiki")
        assertThat(ids(CatalogScope.MODELS)).containsExactly("e2b")
    }

    private fun kiwix(id: String, category: String = "Q&A", featured: Boolean = false, zimName: String = "n-$id") =
        packEntry(id, category = category, urls = listOf("https://download.kiwix.org/zim/$id.zim"), featured = featured, zimName = zimName)

    @Test fun kiwixListsRecommendedFirstAndNeverTwice() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(
            catalog = catalogOf(listOf(kiwix("se1"), kiwix("med", "Medical", featured = true), kiwix("top", "Reference", featured = true))),
        )
        val sections = viewModel(CatalogScope.KIWIX).ui.value.sections
        assertThat(sections.map { it.title }).containsExactly("Recommended", "Q&A").inOrder()
        assertThat(sections[0].rows.map { it.id }).containsExactly("med", "top").inOrder()
        assertThat(sections.flatMap { it.rows }.map { it.id }).containsNoDuplicates()
    }

    @Test fun featuredPacksStayInTheirCategoryOffTheKiwixScreen() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = catalogOf(listOf(packEntry("med", featured = true))))
        assertThat(viewModel().ui.value.sections.map { it.title }).containsExactly("Medical")
    }

    @Test fun kiwixCategoriesFoldUntilShowAll() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = catalogOf((1..20).map { kiwix("se$it") } + kiwix("m1", "Maps")))
        val vm = viewModel(CatalogScope.KIWIX)
        val qa = vm.ui.value.sections.single { it.title == "Q&A" }
        assertThat(qa.rows).hasSize(SourceViewModel.FOLDED_ROWS)
        assertThat(qa.total).isEqualTo(20)
        assertThat(qa.foldable).isTrue()
        assertThat(vm.ui.value.sections.single { it.title == "Maps" }.foldable).isFalse()

        vm.toggleSection("Q&A")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.sections.single { it.title == "Q&A" }.rows).hasSize(20)

        vm.toggleSection("Q&A")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.sections.single { it.title == "Q&A" }.rows).hasSize(SourceViewModel.FOLDED_ROWS)
    }

    @Test fun aKiwixSearchUnfoldsAndMatchesTheZimName() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(
            catalog = catalogOf((1..20).map { kiwix("se$it", zimName = "stackoverflow.com_en_all_$it") } + kiwix("other")),
        )
        val vm = viewModel(CatalogScope.KIWIX)
        vm.setQuery("stackoverflow")
        testScheduler.advanceUntilIdle()
        val sections = vm.ui.value.sections
        assertThat(sections.single().rows).hasSize(20)
        assertThat(sections.single().foldable).isFalse()
    }

    @Test fun grangerSectionsNeverFold() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = catalogOf((1..20).map { packEntry("p$it") }))
        val section = viewModel().ui.value.sections.single()
        assertThat(section.rows).hasSize(20)
        assertThat(section.foldable).isFalse()
    }

    @Test fun categoriesUseTheFamilyTableOrder() = runTest(main.dispatcher) {
        val categories = listOf("Other", "Maps", "Q&A", "Science & Math", "Zebra", "How-to", "Medical")
        catalog.mutable.value = CatalogState(catalog = catalogOf(categories.mapIndexed { i, c -> packEntry("p$i", category = c) }))
        assertThat(viewModel().ui.value.sections.map { it.title })
            .containsExactly("Medical", "Science & Math", "How-to", "Q&A", "Maps", "Other", "Zebra").inOrder()
    }

    private val kiwixCatalog = catalogOf(
        (1..20).map { kiwix("se$it", zimName = "se_$it") } +
            listOf(kiwix("m1", "Maps"), kiwix("m2", "Maps"), kiwix("med", "Medical", featured = true), kiwix("lone", "Medical")),
    )

    @Test fun kiwixChipsListTheCategoriesInOrder() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = kiwixCatalog)
        val ui = viewModel(CatalogScope.KIWIX).ui.value
        assertThat(ui.categories).containsExactly("Medical", "Q&A", "Maps").inOrder()
        assertThat(ui.category).isNull()
    }

    @Test fun aChipShowsOneCategoryWholeWithItsFeaturedPacks() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = kiwixCatalog)
        val vm = viewModel(CatalogScope.KIWIX)
        vm.setCategory("Q&A")
        testScheduler.advanceUntilIdle()
        val qa = vm.ui.value.sections.single()
        assertThat(qa.title).isEqualTo("Q&A")
        assertThat(qa.rows).hasSize(20)
        assertThat(qa.foldable).isFalse()

        vm.setCategory("Medical")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.sections.single().rows.map { it.id }).containsExactly("med", "lone").inOrder()

        vm.setCategory(null)
        testScheduler.advanceUntilIdle()
        val sections = vm.ui.value.sections
        assertThat(sections.map { it.title }).containsExactly("Recommended", "Medical", "Q&A", "Maps").inOrder()
        assertThat(sections.single { it.title == "Q&A" }.rows).hasSize(SourceViewModel.FOLDED_ROWS)
    }

    @Test fun aQuerySearchesWithinTheChosenCategory() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = kiwixCatalog)
        val vm = viewModel(CatalogScope.KIWIX)
        vm.setCategory("Maps")
        vm.setQuery("se_1")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.sections).isEmpty()
        vm.setCategory("Q&A")
        testScheduler.advanceUntilIdle()
        // se_1 and se_10 … se_19.
        assertThat(vm.ui.value.sections.single().rows).hasSize(11)
    }

    @Test fun grangerHasNoChips() = runTest(main.dispatcher) {
        assertThat(viewModel().ui.value.categories).isEmpty()
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

    // --- Plan H1: the Kiwix list filtered to a readiness domain ---

    private val domainCatalog = catalogOf(
        listOf(
            kiwix("med", "Medical", featured = true).copy(domain = "medicine", family = "wikipedia"),
            kiwix("wikem", "Medical").copy(domain = "medicine", family = "wikem"),
            kiwix("cdc", "Medical").copy(domain = "medicine", family = "cdc"),
            kiwix("top", "Reference", featured = true).copy(domain = "reference", family = "wikipedia"),
            kiwix("se1").copy(domain = "technology", family = "stack_exchange"),
        ),
    ).copy(
        readiness = Readiness(
            listOf(
                Domain("medicine", "Medicine & first aid", essential = listOf("med"), comprehensive = listOf("med", "wikem")),
                Domain("reference", "General reference", essential = listOf("top"), comprehensive = listOf("top")),
            ),
        ),
    )

    private fun TestScope.domainViewModel(domain: String?): SourceViewModel {
        val vm = SourceViewModel(
            CatalogScope.KIWIX, catalog, downloads, library, bookshelf, volumes, prefs, DeviceTier.ASK,
            appScope = this, io = main.dispatcher, domain = domain,
        )
        backgroundScope.launch { vm.ui.collect {} }
        testScheduler.advanceUntilIdle()
        return vm
    }

    @Test fun aDomainFiltersTheKiwixListAndHidesTheCategoryChips() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = domainCatalog)
        val ui = domainViewModel("medicine").ui.value
        assertThat(ui.domainTitle).isEqualTo("Medicine & first aid")
        assertThat(ui.categories).isEmpty()
        assertThat(ui.sections.flatMap { it.rows }.map { it.id }).containsExactly("med", "wikem", "cdc")
        // Short list: nothing folds.
        assertThat(ui.sections.none { it.foldable }).isTrue()
    }

    @Test fun recommendedInADomainIsItsTierItemsUninstalledFirst() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = domainCatalog)
        library.packList.value = listOf(pack("u-med"))
        val sections = domainViewModel("medicine").ui.value.sections
        assertThat(sections.map { it.title }).containsExactly("Recommended", "Medical").inOrder()
        // wikem (not installed) before med (installed); the non-tier pack is in its category.
        assertThat(sections[0].rows.map { it.id }).containsExactly("wikem", "med").inOrder()
        assertThat(sections[1].rows.map { it.id }).containsExactly("cdc")
    }

    @Test fun clearingTheDomainBringsTheChipsBack() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = domainCatalog)
        val vm = domainViewModel("medicine")
        vm.clearDomain()
        testScheduler.advanceUntilIdle()
        val ui = vm.ui.value
        assertThat(ui.domainTitle).isNull()
        assertThat(ui.categories).containsExactly("Medical", "Reference", "Q&A").inOrder()
        // Both featured packs sit under Recommended again, so Reference has no section of its own.
        assertThat(ui.sections.map { it.title }).containsExactly("Recommended", "Medical", "Q&A").inOrder()
        assertThat(ui.sections[0].rows.map { it.id }).containsExactly("med", "top").inOrder()
    }

    @Test fun anUnknownDomainShowsEverything() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = domainCatalog)
        val ui = domainViewModel("astrology").ui.value
        assertThat(ui.domainTitle).isNull()
        assertThat(ui.sections.flatMap { it.rows }).hasSize(5)
        assertThat(ui.categories).isNotEmpty()
    }

    @Test fun aQueryInADomainSearchesTheDomainOnly() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = domainCatalog)
        val vm = domainViewModel("medicine")
        vm.setQuery("Pack")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.sections.flatMap { it.rows }.map { it.id }).containsExactly("med", "wikem", "cdc")
    }
}
