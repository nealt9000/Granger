package app.granger.onboarding

import app.granger.FakeCatalogSource
import app.granger.FakeDownloads
import app.granger.FakeLibrary
import app.granger.FakePrefs
import app.granger.FakeVolumes
import app.granger.MainDispatcherRule
import app.granger.bookEntry
import app.granger.catalogOf
import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.CatalogState
import app.granger.core.catalog.Onboarding
import app.granger.core.catalog.Preset
import app.granger.core.catalog.UseCase
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.EnqueueResult
import app.granger.core.downloads.Volume
import app.granger.packEntry
import app.granger.record
import app.granger.volume
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class OnboardingViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val library = FakeLibrary()
    private val downloads = FakeDownloads()
    private val catalog = FakeCatalogSource()
    private val volumes = FakeVolumes(listOf(volume("/internal", "Internal storage", free = 10_000, total = 64_000)))
    private val prefs = FakePrefs()
    private val onboardingPrefs = object : OnboardingPrefs { override var done = false }
    private val enqueued = mutableListOf<Triple<String, List<String>, Boolean>>()
    private var enqueueResult: EnqueueResult = EnqueueResult.Queued

    private val med = packEntry("wikipedia_en_medicine_nopic", size = 1_000, zimName = "wikipedia_en_medicine").copy(family = "wikipedia")
    private val medMaxi = packEntry("wikipedia_en_medicine_maxi", size = 1_900, zimName = "wikipedia_en_medicine").copy(family = "wikipedia")
    private val medline = packEntry("medlineplus.gov_en_all", size = 375).copy(family = "medlineplus.gov")
    private val peru = packEntry("maps_en_peru", size = 500).copy(family = "maps", title = "Peru")
    private val europe = packEntry("maps_en_europe", size = 20_000).copy(family = "maps", title = "Europe")
    private val bible = bookEntry("gc-bible", category = "Scripture & religion").copy(sizeBytes = 10, collection = "granger-classics")
    private val full = catalogOf(
        packs = listOf(med, medline, peru, europe),
        books = listOf(bible),
        presets = listOf(
            Preset("efficient", "Efficient start", listOf("wikipedia_en_medicine_nopic", "gc-bible")),
            Preset("comprehensive", "Comprehensive library", listOf("wikipedia_en_medicine_nopic", "gc-bible", "medlineplus.gov_en_all")),
        ),
    ).copy(onboarding = Onboarding(listOf(UseCase("medical", "Medical work", "Clinical.", listOf("medlineplus.gov_en_all"))), larger = listOf("maps_en_europe")))

    private fun TestScope.viewModel(tier: DeviceTier = DeviceTier.ASK, country: String = "", offerVoice: Boolean = true): OnboardingViewModel {
        val vm = OnboardingViewModel(
            catalog, library, downloads, volumes, prefs, tier, totalRamBytes = 7_974_752_256, onboardingPrefs,
            countryCode = country, offerVoice = offerVoice, io = main.dispatcher, now = { 42 },
            enqueue = { group, items: List<CatalogItem>, _: Volume, wifi -> enqueued += Triple(group, items.map { it.id }, wifi); enqueueResult },
        )
        backgroundScope.launch { vm.ui.collect {} }
        testScheduler.advanceUntilIdle()
        return vm
    }

    private fun TestScope.settle() = testScheduler.advanceUntilIdle()

    @Test fun aFreshInstallFetchesTheCatalogOnce() = runTest(main.dispatcher) {
        val vm = viewModel()
        assertThat(catalog.loads).isEqualTo(1)
        assertThat(catalog.refreshes).isEqualTo(1)
        assertThat(vm.ui.value.noCatalog).isTrue()
        assertThat(vm.ui.value.catalogFailed).isFalse()
        assertThat(vm.ui.value.starts).isEmpty()
    }

    @Test fun aCachedCatalogIsNeverRefreshedByOnboarding() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = full)
        viewModel()
        assertThat(catalog.loads).isEqualTo(1)
        assertThat(catalog.refreshes).isEqualTo(0)
    }

    @Test fun aFailedFetchOffersTryAgainAndNextStillReachesTheEnd() = runTest(main.dispatcher) {
        catalog.onRefresh = { catalog.mutable.value = CatalogState(error = app.granger.core.catalog.CatalogError.NETWORK) }
        val vm = viewModel()
        assertThat(vm.ui.value.catalogFailed).isTrue()
        vm.refreshCatalog(); settle()
        assertThat(catalog.refreshes).isEqualTo(2)
        repeat(5) { vm.next() }; settle()
        assertThat(vm.ui.value.step).isEqualTo(Step.REVIEW)
        assertThat(vm.ui.value.review.isEmpty).isTrue()
        vm.start(); settle()
        assertThat(vm.finished.value).isTrue()
        assertThat(enqueued).isEmpty()
    }

    @Test fun thisPhoneWordsTheTierAndAskAsReadinessDoes() = runTest(main.dispatcher) {
        assertThat(viewModel(DeviceTier.LIBRARY_ONLY).ui.value.phone.tierLine).isEqualTo("A library phone, with 8.0 GB of memory.")
        assertThat(viewModel(DeviceTier.LIBRARY_ONLY).ui.value.phone.researchLine).contains("needs a phone with 6 GB")
        assertThat(viewModel(DeviceTier.ASK).ui.value.phone.tierLine).isEqualTo("A library phone that can run Ask, with 8.0 GB of memory.")
        // No models in this catalog (a release build's): Ask runs here, but there's nothing to get on the next screen.
        assertThat(viewModel(DeviceTier.ASK).ui.value.phone.researchLine).isEqualTo("Ask, the librarian, runs on this phone.")
        assertThat(viewModel(DeviceTier.ASK).ui.value.phone.volumes.single().label).isEqualTo("Internal storage")
    }

    @Test fun theGroupIsQueuedQuickWinsFirstNotInReviewOrder() = runTest(main.dispatcher) {
        val big = packEntry("wikipedia_en_top_maxi", size = 6_990_000_000).copy(family = "wikipedia")
        val e2b = app.granger.modelEntry("gemma-4-e2b-gpu", app.granger.core.catalog.ModelRole.SMALL, size = 2_000)
        catalog.mutable.value = CatalogState(
            catalog = full.copy(
                packs = full.packs + big, models = listOf(e2b),
                presets = listOf(Preset("efficient", "Efficient start", listOf("wikipedia_en_top_maxi", "wikipedia_en_medicine_nopic", "gc-bible"))),
            ),
        )
        volumes.volumes = listOf(volume("/internal", "Internal storage", free = 100_000_000_000, total = 128_000_000_000))
        val vm = viewModel(DeviceTier.ASK)
        vm.chooseRegion(null); settle()
        // The review keeps the preset's grouping; the queue gets the Classics first and Ask's model last.
        assertThat(vm.ui.value.review.items.map { it.id }.first()).isEqualTo("wikipedia_en_top_maxi")
        vm.start(); settle()
        assertThat(enqueued.single().second)
            .containsExactly("gc-bible", "wikipedia_en_medicine_nopic", "wikipedia_en_top_maxi", "gemma-4-e2b-gpu").inOrder()
    }

    @Test fun askIsOfferedTickedAndGoesIntoTheReviewAndTheGroup() = runTest(main.dispatcher) {
        val e2b = app.granger.modelEntry("gemma-4-e2b-gpu", app.granger.core.catalog.ModelRole.SMALL, size = 2_000)
        catalog.mutable.value = CatalogState(catalog = full.copy(models = listOf(e2b)))
        val vm = viewModel(DeviceTier.ASK)
        assertThat(vm.ui.value.phone.researchLine).endsWith("You can get it on the next screen.")
        assertThat(vm.ui.value.askLine!!.bytes).isEqualTo(2_000)
        assertThat(vm.ui.value.askChosen).isTrue()
        // Under Neither too: Ask needs no preset.
        vm.chooseStart(null); settle()
        assertThat(vm.ui.value.review.groups.map { it.title }).containsExactly("Ask (Preview)")
        vm.start(); settle()
        assertThat(enqueued.single().second).containsExactly("gemma-4-e2b-gpu")
    }

    @Test fun untickingAskTakesItOutOfTheReview() = runTest(main.dispatcher) {
        val e2b = app.granger.modelEntry("gemma-4-e2b-gpu", app.granger.core.catalog.ModelRole.SMALL, size = 2_000)
        catalog.mutable.value = CatalogState(catalog = full.copy(models = listOf(e2b)))
        val vm = viewModel(DeviceTier.ASK)
        vm.toggleAsk(); settle()
        assertThat(vm.ui.value.askChosen).isFalse()
        assertThat(vm.ui.value.review.groups.map { it.title }).doesNotContain("Ask (Preview)")
        assertThat(viewModel(DeviceTier.LIBRARY_ONLY).ui.value.askLine).isNull()
    }

    @Test fun thePhonesCountryIsSuggestedUntilTheUserPicks() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = full)
        val vm = viewModel(country = "PE")
        assertThat(vm.ui.value.region!!.title).isEqualTo("Peru")
        assertThat(vm.ui.value.regionSuggested).isTrue()
        assertThat(vm.ui.value.review.groups.map { it.title }).contains("Your region")
        vm.chooseRegion(null); settle()
        assertThat(vm.ui.value.region).isNull()
        assertThat(vm.ui.value.regionSuggested).isFalse()
        vm.next(); vm.back(); settle() // a cleared suggestion stays cleared
        assertThat(vm.ui.value.region).isNull()
        vm.chooseRegion("maps_en_europe"); settle()
        assertThat(vm.ui.value.region!!.title).isEqualTo("Europe")
        assertThat(vm.ui.value.regionSuggested).isFalse()
        // A country with no pack suggests nothing.
        assertThat(viewModel(country = "IN").ui.value.region).isNull()
    }

    @Test fun startWaitsOnceAndIgnoresASecondTap() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = full)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val vm = OnboardingViewModel(
            catalog, library, downloads, volumes, prefs, DeviceTier.ASK, totalRamBytes = 1, onboardingPrefs,
            countryCode = "", io = main.dispatcher, now = { 42 },
            enqueue = { group, items: List<CatalogItem>, _: Volume, wifi -> enqueued += Triple(group, items.map { it.id }, wifi); gate.await(); EnqueueResult.Queued },
        )
        backgroundScope.launch { vm.ui.collect {} }
        settle()
        vm.start(); settle()
        assertThat(vm.ui.value.starting).isTrue()
        vm.start(); settle()
        gate.complete(Unit); settle()
        assertThat(enqueued).hasSize(1)
        assertThat(vm.finished.value).isTrue()
    }

    @Test fun aBigSuggestedMapIsOfferedNotPreselected() = runTest(main.dispatcher) {
        val usa = packEntry("maps_en_usa", size = 17_000_000_000).copy(family = "maps", title = "United States of America")
        catalog.mutable.value = CatalogState(catalog = full.copy(packs = full.packs + usa))
        val vm = viewModel(country = "US")
        assertThat(vm.ui.value.region).isNull()
        assertThat(vm.ui.value.suggestion!!.title).isEqualTo("United States of America")
        assertThat(vm.ui.value.review.groups.map { it.title }).doesNotContain("Your region")
        vm.chooseRegion("maps_en_usa"); settle()
        assertThat(vm.ui.value.region!!.title).isEqualTo("United States of America")
    }

    @Test fun theVoiceIsOfferedTickedAndCanBeLeftOut() = runTest(main.dispatcher) {
        val nanny = app.granger.core.catalog.VoiceEntry(
            id = OnboardingModel.VOICE_ID, title = "Granger voice (Owl)", engine = "piper", language = "en-US",
            sizeBytes = 80, sha256 = "d".repeat(64), url = "https://v.test/v.zip", license = "CC BY 4.0",
        )
        catalog.mutable.value = CatalogState(catalog = full.copy(voices = listOf(nanny)))
        val vm = viewModel()
        assertThat(vm.ui.value.voiceLine!!.items.single().id).isEqualTo(OnboardingModel.VOICE_ID)
        assertThat(vm.ui.value.review.groups.map { it.title }).contains("Read aloud")
        vm.toggleVoice(); settle()
        assertThat(vm.ui.value.review.groups.map { it.title }).doesNotContain("Read aloud")
        // Read-aloud off in this build: no line.
        assertThat(viewModel(offerVoice = false).ui.value.voiceLine).isNull()
    }

    @Test fun stepsGoForwardAndBackAndBackOnWelcomeIsSkip() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.next(); settle()
        assertThat(vm.ui.value.step).isEqualTo(Step.PHONE)
        vm.back(); settle()
        assertThat(vm.ui.value.step).isEqualTo(Step.WELCOME)
        assertThat(vm.finished.value).isFalse()
        vm.back(); settle()
        assertThat(vm.finished.value).isTrue()
        assertThat(onboardingPrefs.done).isTrue()
    }

    @Test fun choicesFlowIntoTheReviewAndStartEnqueuesOneGroup() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = full)
        library.packList.value = listOf(app.granger.pack("u-medline", "MedlinePlus").copy(name = "medlineplus.gov_en_all"))
        downloads.list.value = listOf(record("medlineplus.gov_en_all", DownloadState.INSTALLED))
        val vm = viewModel()
        assertThat(vm.ui.value.noCatalog).isFalse()
        assertThat(vm.ui.value.starts.map { it.id }).containsExactly("efficient", "comprehensive").inOrder()
        assertThat(vm.ui.value.phone.efficientBytes).isEqualTo(1_010)
        // Comprehensive adds nothing for MedlinePlus: it is installed.
        assertThat(vm.ui.value.phone.comprehensiveBytes).isEqualTo(1_010)

        vm.chooseStart("comprehensive")
        vm.toggleLine("collection:granger-classics")
        vm.setRegionQuery("per"); settle()
        assertThat(vm.ui.value.regions.countries.map { it.title }).containsExactly("Peru")
        vm.chooseRegion("maps_en_peru")
        vm.toggleUseCase("medical"); settle()
        val ui = vm.ui.value
        assertThat(ui.additions.single().lines).isEmpty() // MedlinePlus is installed: the chip adds nothing.
        assertThat(ui.review.groups.map { it.title }).containsExactly("Your picks", "Your region").inOrder() // Classics unticked: no longer the preset
        assertThat(ui.review.items.map { it.id }).containsExactly("wikipedia_en_medicine_nopic", "maps_en_peru")
        assertThat(ui.review.bytes).isEqualTo(1_500)
        assertThat(ui.review.fits).isTrue()

        vm.setWifiOnly(false)
        vm.start(); settle()
        // Quick wins first (Plan DL Decision 5): the small pack, then the region's map.
        assertThat(enqueued).containsExactly(Triple("onboarding:42", listOf("wikipedia_en_medicine_nopic", "maps_en_peru"), false))
        assertThat(prefs.wifiOnly).isFalse()
        // The switch also applies to what's already downloading.
        assertThat(downloads.calls).contains("wifi:false")
        assertThat(onboardingPrefs.done).isTrue()
        assertThat(vm.finished.value).isTrue()
    }

    @Test fun anInstalledFlavourCoversTheOtherFlavourOfTheSameZim() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = full.copy(packs = full.packs + medMaxi))
        // WikiMed with pictures is on the phone (by its ZIM name); the preset's no-pictures line counts as held.
        library.packList.value = listOf(app.granger.pack("u-med", "WikiMed").copy(name = "wikipedia_en_medicine"))
        val vm = viewModel()
        val efficient = vm.ui.value.starts.first { it.id == "efficient" }
        assertThat(efficient.lines.single { it.id == "wikipedia_en_medicine_nopic" }.installed).isTrue()
        assertThat(efficient.bytes()).isEqualTo(10)
    }

    @Test fun aPackInsideAnInstalledOneCountsAsHeld() = runTest(main.dispatcher) {
        // Plan H4: Top 1M holds the Top pack's articles, so Efficient's Top line is on the phone.
        val top = packEntry("wikipedia_en_top_nopic", size = 2_700, zimName = "wikipedia_en_top").copy(family = "wikipedia")
        val top1m = packEntry("wikipedia_en_top1m_nopic", size = 17_000, zimName = "wikipedia_en_top1m").copy(family = "wikipedia")
        val readiness = app.granger.core.catalog.Readiness(contains = mapOf("wikipedia_en_top1m" to listOf("wikipedia_en_top")))
        catalog.mutable.value = CatalogState(
            catalog = full.copy(
                packs = full.packs + top + top1m,
                presets = listOf(Preset("efficient", "Efficient start", listOf("wikipedia_en_top_nopic", "gc-bible"))),
                readiness = readiness,
            ),
        )
        library.packList.value = listOf(app.granger.pack("u-top1m", "Top 1M").copy(name = "wikipedia_en_top1m"))
        val vm = viewModel()
        val efficient = vm.ui.value.starts.first { it.id == "efficient" }
        assertThat(efficient.lines.single { it.id == "wikipedia_en_top_nopic" }.installed).isTrue()
    }

    @Test fun aRefusedStartStaysWithANoticeAndAnEmptyReviewJustFinishes() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = full)
        enqueueResult = EnqueueResult.NoSpace(neededBytes = 5_000)
        val vm = viewModel()
        vm.start(); settle()
        assertThat(vm.ui.value.notice).contains("free up")
        assertThat(vm.finished.value).isFalse()
        assertThat(onboardingPrefs.done).isFalse()
        vm.clearNotice(); settle()
        assertThat(vm.ui.value.notice).isNull()

        vm.chooseStart(null); settle()
        assertThat(vm.ui.value.review.isEmpty).isTrue()
        vm.start(); settle()
        assertThat(vm.finished.value).isTrue()
        assertThat(enqueued).hasSize(1)
    }
}
