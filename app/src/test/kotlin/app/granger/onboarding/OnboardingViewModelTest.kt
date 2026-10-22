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

    private val med = packEntry("wikipedia_en_medicine_nopic", size = 1_000).copy(family = "wikipedia")
    private val wikem = packEntry("wikem_en_all_maxi", size = 375).copy(family = "wikem")
    private val peru = packEntry("maps_en_peru", size = 500).copy(family = "maps", title = "Peru")
    private val europe = packEntry("maps_en_europe", size = 20_000).copy(family = "maps", title = "Europe")
    private val bible = bookEntry("gc-bible", category = "Scripture & religion").copy(sizeBytes = 10, collection = "granger-classics")
    private val full = catalogOf(
        packs = listOf(med, wikem, peru, europe),
        books = listOf(bible),
        presets = listOf(
            Preset("efficient", "Efficient start", listOf("wikipedia_en_medicine_nopic", "gc-bible")),
            Preset("comprehensive", "Comprehensive library", listOf("wikipedia_en_medicine_nopic", "gc-bible", "wikem_en_all_maxi")),
        ),
    ).copy(onboarding = Onboarding(listOf(UseCase("medical", "Medical work", "Clinical.", listOf("wikem_en_all_maxi"))), larger = listOf("maps_en_europe")))

    private fun TestScope.viewModel(tier: DeviceTier = DeviceTier.THINK_DEEPER): OnboardingViewModel {
        val vm = OnboardingViewModel(
            catalog, library, downloads, volumes, prefs, tier, totalRamBytes = 7_974_752_256, onboardingPrefs,
            io = main.dispatcher, now = { 42 },
            enqueue = { group, items: List<CatalogItem>, _: Volume, wifi -> enqueued += Triple(group, items.map { it.id }, wifi); enqueueResult },
        )
        backgroundScope.launch { vm.ui.collect {} }
        testScheduler.advanceUntilIdle()
        return vm
    }

    private fun TestScope.settle() = testScheduler.advanceUntilIdle()

    @Test fun withoutACatalogTheStartStepAsksForARefresh() = runTest(main.dispatcher) {
        val vm = viewModel()
        assertThat(vm.ui.value.noCatalog).isTrue()
        assertThat(vm.ui.value.starts).isEmpty()
        vm.refreshCatalog(); settle()
        assertThat(catalog.refreshes).isEqualTo(1)
        assertThat(catalog.loads).isEqualTo(1)
    }

    @Test fun thisPhoneWordsTheTierAndResearch() = runTest(main.dispatcher) {
        assertThat(viewModel(DeviceTier.LIBRARY_ONLY).ui.value.phone.tierLine).startsWith("A library phone, with about 7 GB")
        assertThat(viewModel(DeviceTier.LIBRARY_ONLY).ui.value.phone.researchLine).contains("needs a phone with 6 GB")
        assertThat(viewModel(DeviceTier.THINK_DEEPER).ui.value.phone.tierLine).contains("think deeper")
        assertThat(viewModel(DeviceTier.THINK_DEEPER).ui.value.phone.volumes.single().label).isEqualTo("Internal storage")
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
        library.packList.value = listOf(app.granger.pack("u-wikem", "WikEM").copy(name = "wikem_en_all"))
        downloads.list.value = listOf(record("wikem_en_all_maxi", DownloadState.INSTALLED))
        val vm = viewModel()
        assertThat(vm.ui.value.noCatalog).isFalse()
        assertThat(vm.ui.value.starts.map { it.id }).containsExactly("efficient", "comprehensive").inOrder()
        assertThat(vm.ui.value.phone.efficientBytes).isEqualTo(1_010)
        // Comprehensive adds nothing for WikEM: it is installed.
        assertThat(vm.ui.value.phone.comprehensiveBytes).isEqualTo(1_010)

        vm.chooseStart("comprehensive")
        vm.toggleLine("collection:granger-classics")
        vm.setRegionQuery("per"); settle()
        assertThat(vm.ui.value.regions.countries.map { it.title }).containsExactly("Peru")
        vm.chooseRegion("maps_en_peru")
        vm.toggleUseCase("medical"); settle()
        val ui = vm.ui.value
        assertThat(ui.additions.single().lines).isEmpty() // WikEM is installed: the chip adds nothing.
        assertThat(ui.review.groups.map { it.title }).containsExactly("Comprehensive library", "Your region").inOrder()
        assertThat(ui.review.items.map { it.id }).containsExactly("wikipedia_en_medicine_nopic", "maps_en_peru")
        assertThat(ui.review.bytes).isEqualTo(1_500)
        assertThat(ui.review.fits).isTrue()

        vm.setWifiOnly(false)
        vm.start(); settle()
        assertThat(enqueued).containsExactly(Triple("onboarding:42", listOf("wikipedia_en_medicine_nopic", "maps_en_peru"), false))
        assertThat(prefs.wifiOnly).isFalse()
        assertThat(onboardingPrefs.done).isTrue()
        assertThat(vm.finished.value).isTrue()
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
