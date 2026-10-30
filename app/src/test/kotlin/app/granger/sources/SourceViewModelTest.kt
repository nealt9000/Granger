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
import app.granger.core.downloads.ItemKind
import app.granger.core.catalog.VectorsEntry
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
        assertThat(ui.sections[0].rows.single().details).isEqualTo("English · Updated October 2026 · CC BY-SA")
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
        assertThat(rows.getValue("ifixit").details).isEqualTo("English · Updated October 2026 · CC BY-NC-SA · provided by Kiwix")
        assertThat(rows.getValue("med").details).isEqualTo("English · Updated October 2026 · CC BY-SA")
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

    private fun gutenberg(lcc: String, subject: String, size: Long, featured: Boolean = false) = packEntry(
        "gutenberg_en_$lcc", category = "Literature & Drama", size = size,
        urls = listOf("https://download.kiwix.org/zim/gutenberg/gutenberg_en_${lcc}_2026-03.zim"), featured = featured,
        zimName = "gutenberg_en_$lcc",
    ).copy(title = "$subject · Project Gutenberg", family = "gutenberg")

    @Test fun projectGutenbergListsTheSubjectsAToZThenEverythingAndKiwixDoesnt() = runTest(main.dispatcher) {
        val music = gutenberg("lcc-m", "Music and books on music", 3_994_272_104)
        val philosophy = gutenberg("lcc-b", "Philosophy, psychology, religion", 6_365_165_314, featured = true)
        val fineArts = gutenberg("lcc-n", "Fine arts", 22_752_881_356)
        val english = gutenberg("all", "Every English book", 221_251_590_584)
        val every = packEntry(
            "gutenberg_mul_all", size = 253_055_616_960, urls = listOf("https://download.kiwix.org/zim/gutenberg/gutenberg_mul_all_2026-03.zim"),
            zimName = "gutenberg_mul_all",
        ).copy(title = "Every book, all languages · Project Gutenberg", family = "gutenberg")
        val wiki = kiwix("wiki", "Reference")
        catalog.mutable.value = CatalogState(catalog = catalogOf(listOf(every, wiki, music, english, philosophy, fineArts)))
        library.packList.value = listOf(pack("u-gutenberg_en_lcc-m").copy(name = "gutenberg_en_lcc-m"))

        val ui = viewModel(CatalogScope.GUTENBERG).ui.value
        assertThat(ui.sections.map { it.title }).containsExactly("Subjects", "Everything").inOrder()
        assertThat(ui.categories).isEmpty()
        assertThat(ui.presets).isEmpty()
        // A–Z by subject, each its subject alone on this screen, with its size and whether it's on the phone.
        val subjects = ui.sections[0].rows
        assertThat(subjects.map { it.title }).containsExactly("Fine arts", "Music and books on music", "Philosophy, psychology, religion").inOrder()
        assertThat(subjects.map { it.sizeBytes }).containsExactly(22_752_881_356, 3_994_272_104, 6_365_165_314).inOrder()
        assertThat(subjects[1].status).isEqualTo(ItemStatus.Installed)
        assertThat(subjects[1].packId).isEqualTo("u-gutenberg_en_lcc-m")
        assertThat(ui.sections[1].rows.map { it.title }).containsExactly("Every English book", "Every book, all languages").inOrder()

        // Kiwix's list no longer shows them, featured or not.
        assertThat(viewModel(CatalogScope.KIWIX).ui.value.sections.flatMap { it.rows }.map { it.id }).containsExactly("wiki")
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
        catalog.mutable.value = CatalogState(
            catalog = catalogOf(books = listOf(bookEntry("emma")), presets = listOf(Preset("granger-classics", "Granger Classics", listOf("emma")))),
        )
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

    @Test fun anInstalledPackRowOpensItsLibraryPack() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("u-med"))
        val rows = viewModel().ui.value.sections.flatMap { it.rows }.associateBy { it.id }
        // Open (Plan U3b Decision 3) needs the pack the entry is installed as; one not installed has none.
        assertThat(rows.getValue("med").status).isEqualTo(ItemStatus.Installed)
        assertThat(rows.getValue("med").packId).isEqualTo("u-med")
        assertThat(rows.getValue("top").packId).isNull()
    }

    @Test fun packRowsSayHowAskSearchesThem() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(
            catalog = catalogOf(
                packs = listOf(
                    packEntry("med").copy(vectors = VectorsEntry(url = "https://a.test/med.gvec")),
                    packEntry("top"),
                    packEntry("bare").copy(hasFulltextIndex = false),
                    packEntry("idx").copy(hasFulltextIndex = false),
                    packEntry("osm").copy(family = "maps", hasFulltextIndex = false),
                ),
                models = listOf(e2b),
            ),
        )
        // Vectors installed for a pack the catalog no longer lists them for still count.
        downloads.list.value = listOf(record("idx.vectors", DownloadState.INSTALLED, kind = ItemKind.VECTORS))
        val vm = viewModel()
        val rows = vm.ui.value.sections.flatMap { it.rows }.associateBy { it.id }
        assertThat(rows.getValue("med").ask).isEqualTo(AskReach.MEANING)
        assertThat(rows.getValue("top").ask).isEqualTo(AskReach.KEYWORDS)
        assertThat(rows.getValue("bare").ask).isEqualTo(AskReach.NONE)
        assertThat(rows.getValue("idx").ask).isEqualTo(AskReach.MEANING)
        assertThat(rows.getValue("osm").ask).isNull()
        assertThat(viewModel(CatalogScope.MODELS).ui.value.sections.flatMap { it.rows }.single().ask).isNull()
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

    @Test fun grangerBookCategoriesFoldPastEight() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(
            catalog = catalogOf(books = (1..12).map { bookEntry("n$it", "Novels") } + listOf(bookEntry("hamlet", "Plays"))),
        )
        val vm = viewModel()
        val novels = vm.ui.value.sections.first { it.title == "Novels" }
        assertThat(novels.rows).hasSize(SourceViewModel.FOLDED_ROWS)
        assertThat(novels.total).isEqualTo(12)
        assertThat(novels.foldable).isTrue()
        assertThat(novels.noun).isEqualTo("books")
        assertThat(vm.ui.value.sections.first { it.title == "Plays" }.foldable).isFalse()
        vm.toggleSection("Novels")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.sections.first { it.title == "Novels" }.rows).hasSize(12)
        // A search shows every match unfolded.
        vm.toggleSection("Novels")
        vm.setQuery("Book n")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.sections.first { it.title == "Novels" }.let { it.rows.size to it.foldable }).isEqualTo(12 to false)
    }

    @Test fun grangerSearchMatchesABooksAuthor() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(
            catalog = catalogOf(books = listOf(bookEntry("pan", author = "J. M. Barrie"), bookEntry("emma", author = "Jane Austen"))),
        )
        val vm = viewModel()
        vm.setQuery("barrie")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.sections.flatMap { it.rows }.map { it.id }).containsExactly("pan")
    }

    @Test fun presetsCountByKindAndSayWhatsOnThePhone() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(
            catalog = catalogOf(
                books = listOf(bookEntry("emma"), bookEntry("hamlet"), bookEntry("pan")),
                presets = listOf(Preset("granger-classics", "Granger Classics", listOf("emma", "hamlet", "pan"))),
            ),
        )
        val classics = viewModel().ui.value.presets.single()
        assertThat(listOf(classics.packs, classics.books, classics.others)).containsExactly(0, 3, 0).inOrder()
        assertThat(PresetText.summary(classics)).isEqualTo("3 books · ${app.granger.formatBytes(1_500)}")
        assertThat(PresetText.status(classics)).isEqualTo("3 of 3 to get")

        val book = app.granger.core.downloads.ItemKind.BOOK
        downloads.list.value = listOf(record("emma", DownloadState.INSTALLED, kind = book), record("hamlet", DownloadState.RUNNING, kind = book))
        assertThat(PresetText.status(viewModel().ui.value.presets.single())).isEqualTo("1 downloading · 1 of 3 to get")

        downloads.list.value = listOf("emma", "hamlet", "pan").map { record(it, DownloadState.INSTALLED, kind = book) }
        assertThat(PresetText.status(viewModel().ui.value.presets.single())).isEqualTo("All on this phone")
    }

    @Test fun presetLinesReadAsPlanned() {
        val efficient = PresetRow("e", "Efficient start", 359, 0, 0, packs = 12, books = 347, totalBytes = 17_700_000_000)
        assertThat(PresetText.summary(efficient)).startsWith("12 packs and 347 books · ")
        assertThat(PresetText.summary(efficient.copy(packs = 1, books = 0))).startsWith("1 pack · ")
        assertThat(PresetText.summary(efficient.copy(others = 2))).startsWith("12 packs, 347 books and 2 models · ")
        assertThat(PresetText.status(efficient.copy(downloading = 359))).isEqualTo("359 downloading")
        assertThat(PresetText.status(efficient.copy(onPhone = 359))).isEqualTo("All on this phone")
    }

    @Test fun classicsPresetComesFirst() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(
            catalog = catalogOf(
                books = listOf(bookEntry("emma"), bookEntry("hamlet")),
                presets = listOf(
                    Preset("tragedies", "Tragedies", listOf("hamlet")),
                    Preset("granger-classics", "Granger Classics", listOf("emma", "hamlet")),
                ),
            ),
        )
        assertThat(viewModel().ui.value.presets.map { it.id }).containsExactly("granger-classics", "tragedies").inOrder()
    }

    /**
     * Neal (Plan GB walkthrough): "Kiwix wikis are still showing in get more books". Under Books, Granger Classics lists
     * Granger's books and only sets of books (Starter, Efficient and Comprehensive bring the wikis), and Project
     * Gutenberg lists only Gutenberg packs.
     */
    @Test fun underBooksOnlyGrangerBooksAndGutenbergPacksAreOffered() = runTest(main.dispatcher) {
        val wiki = kiwix("wikipedia_en_medicine_nopic", "Medical", featured = true, zimName = "wikipedia_en_medicine")
        val cooking = kiwix("based.cooking_en_all", "How-to", zimName = "based.cooking_en_all")
        val music = gutenberg("lcc-m", "Music and books on music", 3_994_272_104)
        val byName = packEntry("gutenberg_fr_all", urls = listOf("https://download.kiwix.org/zim/gutenberg/gutenberg_fr_all.zim"), zimName = "gutenberg_fr_all")
        catalog.mutable.value = CatalogState(
            catalog = catalogOf(
                packs = listOf(wiki, cooking, music, byName),
                models = listOf(e2b),
                books = listOf(bookEntry("emma"), bookEntry("hamlet")),
                presets = listOf(
                    Preset("starter", "Starter", listOf("e2b", wiki.id, "emma")),
                    Preset("efficient", "Efficient start", listOf(wiki.id, cooking.id, "emma", "hamlet")),
                    Preset("granger-classics", "Granger Classics: all books", listOf("emma", "hamlet")),
                ),
            ),
        )
        val classics = viewModel(CatalogScope.GRANGER).ui.value
        assertThat(classics.sections.flatMap { it.rows }.map { it.id }).containsExactly("emma", "hamlet")
        assertThat(classics.presets.map { it.id }).containsExactly("granger-classics")
        val gutenberg = viewModel(CatalogScope.GUTENBERG).ui.value
        assertThat(gutenberg.sections.flatMap { it.rows }.map { it.id }).containsExactly(music.id, byName.id)
        assertThat(gutenberg.presets).isEmpty()
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
    }

    // --- Plan H1: the Kiwix list filtered to a readiness domain ---

    private val domainCatalog = catalogOf(
        listOf(
            kiwix("med", "Medical", featured = true).copy(domain = "medicine", family = "wikipedia"),
            kiwix("medline", "Medical").copy(domain = "medicine", family = "medlineplus.gov"),
            kiwix("cdc", "Medical").copy(domain = "medicine", family = "cdc"),
            kiwix("top", "Reference", featured = true).copy(domain = "reference", family = "wikipedia"),
            kiwix("se1").copy(domain = "technology", family = "stack_exchange"),
        ),
    ).copy(
        readiness = Readiness(
            listOf(
                Domain("medicine", "Medicine & first aid", essential = listOf("med"), comprehensive = listOf("med", "medline")),
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
        assertThat(ui.sections.flatMap { it.rows }.map { it.id }).containsExactly("med", "medline", "cdc")
        // Short list: nothing folds.
        assertThat(ui.sections.none { it.foldable }).isTrue()
    }

    @Test fun recommendedInADomainIsItsTierItemsUninstalledFirst() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = domainCatalog)
        library.packList.value = listOf(pack("u-med"))
        val sections = domainViewModel("medicine").ui.value.sections
        assertThat(sections.map { it.title }).containsExactly("Recommended", "Medical").inOrder()
        // medline (not installed) before med (installed); the non-tier pack is in its category.
        assertThat(sections[0].rows.map { it.id }).containsExactly("medline", "med").inOrder()
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

    // --- Plan KB: Kiwix libraries under Books; essentials still counted ---

    private fun library(id: String, title: String, size: Long) =
        kiwix(id, "Other", zimName = id).copy(title = title, sizeBytes = size, content = "books", family = id.substringBefore('_'))

    private val water = library("zimgit-water_en", "Water", 20_000_000)
    private val prep = library("zimgit-food-preparation_en", "Food for Preppers", 90_000_000)
    private val knots = library("zimgit-knots_en", "Knots", 60_000_000)

    @Test fun theKiwixListHasNoBookPacksAndKiwixLibrariesListsThemAToZ() = runTest(main.dispatcher) {
        val cooking = kiwix("based.cooking_en_all", "How-to", zimName = "based.cooking_en_all")
        val music = gutenberg("lcc-m", "Music and books on music", 3_994_272_104).copy(content = "books")
        catalog.mutable.value = CatalogState(catalog = catalogOf(listOf(cooking, water, prep, knots, music)))
        library.packList.value = listOf(pack("u-water").copy(name = "zimgit-water_en"))

        val kiwixUi = viewModel(CatalogScope.KIWIX).ui.value
        assertThat(kiwixUi.sections.flatMap { it.rows }.map { it.id }).containsExactly(cooking.id)
        assertThat(kiwixUi.categories).containsExactly("How-to")

        val libraries = viewModel(CatalogScope.KIWIX_BOOKS).ui.value
        assertThat(libraries.sections.map { it.title }).containsExactly(SourceViewModel.LIBRARIES)
        val rows = libraries.sections.single().rows
        assertThat(rows.map { it.title }).containsExactly("Food for Preppers", "Knots", "Water").inOrder()
        assertThat(rows.map { it.sizeBytes }).containsExactly(90_000_000L, 60_000_000L, 20_000_000L).inOrder()
        assertThat(libraries.presets).isEmpty()
        assertThat(libraries.categories).isEmpty()
        // Gutenberg keeps its own screen.
        assertThat(viewModel(CatalogScope.GUTENBERG).ui.value.sections.flatMap { it.rows }.map { it.id }).containsExactly(music.id)
    }

    @Test fun foodsDomainViewStillListsWaterAndFoodForPreppers() = runTest(main.dispatcher) {
        val cooking = kiwix("based.cooking_en_all", "How-to", zimName = "based.cooking_en_all").copy(domain = "food")
        val cat = catalogOf(listOf(cooking, water.copy(domain = "food"), prep.copy(domain = "food"), knots.copy(domain = "making"))).copy(
            readiness = Readiness(
                listOf(Domain("food", "Food, water & survival", essential = listOf(cooking.id, water.id, prep.id), comprehensive = listOf(cooking.id, water.id, prep.id))),
            ),
        )
        catalog.mutable.value = CatalogState(catalog = cat)
        val ui = domainViewModel("food").ui.value
        assertThat(ui.domainTitle).isEqualTo("Food, water & survival")
        assertThat(ui.sections.first().title).isEqualTo(SourceViewModel.RECOMMENDED)
        assertThat(ui.sections.flatMap { it.rows }.map { it.id }).containsExactly(cooking.id, water.id, prep.id)
    }

    @Test fun aQueryInADomainSearchesTheDomainOnly() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = domainCatalog)
        val vm = domainViewModel("medicine")
        vm.setQuery("Pack")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.sections.flatMap { it.rows }.map { it.id }).containsExactly("med", "medline", "cdc")
    }

    // --- Plan KB: one row per pack, in plain words, with a size check ---

    private fun wiki(flavour: String, size: Long, featured: Boolean = false, title: String = "WikiMed") =
        kiwix("wikipedia_en_medicine_$flavour", "Medical", featured = featured, zimName = "wikipedia_en_medicine")
            .copy(title = title, sizeBytes = size, flavour = flavour, snapshotDate = "2026-03-05")

    private val medFlavours = listOf(
        wiki("nopic", 1_050_000_000, featured = true, title = "WikiMed (no pictures)"),
        wiki("maxi", 1_860_000_000, featured = true),
        wiki("mini", 170_000_000, title = "WikiMed Medical Encyclopedia"),
    )

    @Test fun threeWikipediaFlavoursAreOneRowWithAChoice() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = catalogOf(medFlavours + kiwix("other", "Medical")))
        val vm = viewModel(CatalogScope.KIWIX)
        val rows = vm.ui.value.sections.flatMap { it.rows }
        val med = rows.single { it.title == "WikiMed" }
        assertThat(rows).hasSize(2)
        assertThat(med.flavours.map { it.label }).containsExactly("With pictures", "No pictures", "Mini").inOrder()
        assertThat(med.flavours.map { it.sizeBytes }).containsExactly(1_860_000_000L, 1_050_000_000L, 170_000_000L).inOrder()
        // Both featured flavours fit, so the larger is chosen.
        assertThat(med.id).isEqualTo("wikipedia_en_medicine_maxi")
        assertThat(med.flavours.single { it.chosen }.label).isEqualTo("With pictures")
        assertThat(med.details).isEqualTo("English · Updated March 2026 · CC BY-SA")
        assertThat(med.license).isEqualTo("CC BY-SA 4.0")
    }

    @Test fun getTakesTheChosenFlavour() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = catalogOf(medFlavours))
        val vm = viewModel(CatalogScope.KIWIX)
        vm.chooseFlavour("wikipedia_en_medicine_mini")
        testScheduler.advanceUntilIdle()
        val med = vm.ui.value.sections.flatMap { it.rows }.single()
        assertThat(med.id).isEqualTo("wikipedia_en_medicine_mini")
        assertThat(med.sizeBytes).isEqualTo(170_000_000L)
        vm.get(med.id)
        testScheduler.advanceUntilIdle()
        assertThat(downloads.enqueued.map { it.first }).containsExactly("wikipedia_en_medicine_mini")
    }

    @Test fun anInstalledFlavourSaysSoAndAnotherOneIsASwitch() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = catalogOf(medFlavours))
        library.packList.value = listOf(pack("u-wikipedia_en_medicine_nopic").copy(name = "wikipedia_en_medicine"))
        val vm = viewModel(CatalogScope.KIWIX)
        val installed = vm.ui.value.sections.flatMap { it.rows }.single()
        assertThat(installed.id).isEqualTo("wikipedia_en_medicine_nopic")
        assertThat(installed.installedFlavour).isEqualTo("no pictures")
        assertThat(installed.switches).isFalse()
        assertThat(installed.status).isEqualTo(ItemStatus.Installed)
        vm.chooseFlavour("wikipedia_en_medicine_maxi")
        testScheduler.advanceUntilIdle()
        val other = vm.ui.value.sections.flatMap { it.rows }.single()
        assertThat(other.id).isEqualTo("wikipedia_en_medicine_maxi")
        assertThat(other.installedFlavour).isEqualTo("no pictures")
        assertThat(other.switches).isTrue()
        assertThat(other.packId).isEqualTo("u-wikipedia_en_medicine_nopic")
    }

    @Test fun languagesDatesAndLicencesAreInPlainWords() {
        assertThat(KiwixList.languages("eng")).isEqualTo("English")
        assertThat(KiwixList.languages("eng,rus")).isEqualTo("English, Russian")
        assertThat(KiwixList.languages("eng,zho")).isEqualTo("English, Chinese")
        assertThat(KiwixList.languages("eng,fra,deu,nld,spa")).isEqualTo("English, French, German and 2 more")
        assertThat(KiwixList.updated("2026-03-05")).isEqualTo("Updated March 2026")
        assertThat(KiwixList.updated("")).isEmpty()
        assertThat(KiwixList.licence("CC BY-SA 4.0")).isEqualTo("CC BY-SA")
        assertThat(KiwixList.licence("CC BY-NC-SA 2.5")).isEqualTo("CC BY-NC-SA")
        assertThat(KiwixList.licence("Open licenses, per project (MIT, Apache, BSD, CC)")).isEqualTo("Open licences")
        assertThat(KiwixList.licence("Mixed open licenses (mostly CC BY-NC-SA 4.0)")).isEqualTo("Open licences")
        assertThat(KiwixList.licence("Not stated by the source")).isEqualTo("Licence not stated")
        assertThat(KiwixList.licence("Public domain (US) / Project Gutenberg License")).isEqualTo("Public domain")
        assertThat(KiwixList.licence("ODbL 1.0")).isEqualTo("ODbL")
    }

    @Test fun aGetOverTenGbAsksWithTheFreeSpaceAndASmallerFlavour() = runTest(main.dispatcher) {
        val maxi = wiki("maxi", 110_000_000_000).copy(id = "wikipedia_en_all_maxi", zimName = "wikipedia_en_all", title = "Wikipedia")
        val nopic = wiki("nopic", 48_000_000_000).copy(id = "wikipedia_en_all_nopic", zimName = "wikipedia_en_all", title = "Wikipedia (no pictures)")
        val survivor = kiwix("survivorlibrary.com_en_all", "How-to", zimName = "survivorlibrary.com_en_all")
            .copy(title = "Survivor Library", sizeBytes = 220_000_000_000)
        catalog.mutable.value = CatalogState(catalog = catalogOf(listOf(maxi, nopic, survivor)))
        val vm = viewModel(CatalogScope.KIWIX)
        vm.get(survivor.id)
        testScheduler.advanceUntilIdle()
        val check = vm.ui.value.sizeCheck!!
        assertThat(check.title).isEqualTo("Survivor Library")
        assertThat(check.sizeBytes).isEqualTo(220_000_000_000L)
        assertThat(check.volumeLabel).isEqualTo("Internal storage")
        assertThat(check.freeBytes).isEqualTo(volumes.list().first().freeBytes)
        assertThat(check.smaller).isNull()
        assertThat(downloads.enqueued).isEmpty()
        vm.dismissSizeCheck()

        vm.get(maxi.id)
        testScheduler.advanceUntilIdle()
        val wikipedia = vm.ui.value.sizeCheck!!
        assertThat(wikipedia.title).isEqualTo("Wikipedia")
        assertThat(wikipedia.smaller!!.id).isEqualTo(nopic.id)
        assertThat(wikipedia.smaller!!.label).isEqualTo("No pictures")
        vm.confirmGet(wikipedia.smaller!!.id)
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.sizeCheck).isNull()
        assertThat(downloads.enqueued.map { it.first }).containsExactly(nopic.id)
    }
}
