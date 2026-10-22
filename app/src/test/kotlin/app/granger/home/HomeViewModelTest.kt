package app.granger.home

import app.granger.FakeBookshelf
import app.granger.FakeCatalogSource
import app.granger.FakeDownloads
import app.granger.FakeLibrary
import app.granger.MainDispatcherRule
import app.granger.catalogOf
import app.granger.core.catalog.CatalogState
import app.granger.core.catalog.Domain
import app.granger.core.catalog.Readiness
import app.granger.core.downloads.DownloadState
import app.granger.pack
import app.granger.packEntry
import app.granger.readiness.Coverage
import app.granger.record
import app.granger.shell.RailEntry
import app.granger.shell.RailTabUi
import app.granger.shell.RailUi
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val library = FakeLibrary()
    private val bookshelf = FakeBookshelf()
    private val downloads = FakeDownloads()
    private val catalog = FakeCatalogSource()
    private val rail = MutableStateFlow(RailUi(emptyList()))

    private val med = packEntry("wikipedia_en_medicine_nopic", zimName = "wikipedia_en_medicine").copy(domain = "medicine")
    private val food = packEntry("cooking.stackexchange.com_en_all", zimName = "cooking.stackexchange.com_en", size = 500).copy(domain = "food")
    private val domains = listOf(
        Domain("medicine", "Medicine & first aid", listOf(med.id), listOf(med.id)),
        Domain("food", "Food, water & survival", listOf(food.id), listOf(food.id)),
    )

    private fun TestScope.viewModel(): HomeViewModel {
        val vm = HomeViewModel(
            catalog, library, bookshelf, downloads, rail, io = main.dispatcher, today = { LocalDate.of(2026, 10, 22) },
        )
        backgroundScope.launch { vm.ui.collect {} }
        testScheduler.advanceUntilIdle()
        return vm
    }

    private fun tab(id: Long, title: String) =
        RailEntry.Tab(RailTabUi(id, title, "A pack", title.first(), available = true, current = false), at = id)

    @Test fun stripIsTheReadinessReportOverTheCachedCatalog() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = catalogOf(packs = listOf(med, food)).copy(readiness = Readiness(domains)))
        library.packList.value = listOf(pack("uuid-med", "WikiMed").copy(name = "wikipedia_en_medicine"))
        downloads.list.value = listOf(record(med.id, DownloadState.INSTALLED))
        val ui = viewModel().ui.value
        assertThat(ui.strip.domains.map { it.id to it.coverage })
            .containsExactly("medicine" to Coverage.COMPREHENSIVE, "food" to Coverage.NONE).inOrder()
        assertThat(ui.strip.signals.single().text).startsWith("Nothing yet for Food, water & survival.")
        assertThat(ui.strip.needsCatalogRefresh).isFalse()
    }

    @Test fun noCatalogMeansARefreshIsNeeded() = runTest(main.dispatcher) {
        val ui = viewModel().ui.value
        assertThat(ui.strip.domains).isEmpty()
        assertThat(ui.strip.needsCatalogRefresh).isTrue()
        assertThat(stripText(ui.strip)).isEqualTo("Refresh the catalog to see your coverage.")
    }

    @Test fun continueIsTheRailsTabsAndPlaysInTheRailsOrder() = runTest(main.dispatcher) {
        val owls = tab(1, "Owls")
        val blue = RailEntry.Play("album:Blue", "Blue", at = 3)
        rail.value = RailUi(
            listOf(RailEntry.Search("cats", at = 9), blue, RailEntry.Chat(4, "About owls", at = 2), owls, RailEntry.Reading("k", "Live")),
        )
        val vm = viewModel()
        assertThat(vm.ui.value.continueCards).containsExactly(blue, owls).inOrder()

        rail.value = RailUi((1L..12L).map { tab(it, "T$it") })
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.continueCards).hasSize(HomeViewModel.CONTINUE)
    }

    @Test fun dotsAndTheSentenceFollowTheReport() {
        assertThat(dotAlpha(Coverage.NONE)).isLessThan(dotAlpha(Coverage.BASIC))
        assertThat(dotAlpha(Coverage.BASIC)).isLessThan(dotAlpha(Coverage.COMPREHENSIVE))
        assertThat(dotAlpha(Coverage.COMPREHENSIVE)).isEqualTo(1f)
        val ready = app.granger.readiness.ReadinessReport(emptyList(), emptyList(), emptyList(), needsCatalogRefresh = false)
        assertThat(stripText(ready)).isEqualTo("Ready.")
    }
}
