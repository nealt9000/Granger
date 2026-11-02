package app.granger.sources

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.granger.FakeBookshelf
import app.granger.FakeCatalogSource
import app.granger.FakeDownloads
import app.granger.FakeLibrary
import app.granger.FakePrefs
import app.granger.FakeVolumes
import app.granger.MainDispatcherRule
import app.granger.UiText
import app.granger.catalogOf
import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.Copy
import app.granger.core.catalog.Domain
import app.granger.core.catalog.Readiness
import app.granger.core.catalog.Tiers
import app.granger.pack
import app.granger.packEntry
import app.granger.volume
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Get more by language (Plan L3 Task 7). The English lists are pinned by [SourceViewModelTest]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SourceLanguagesTest {
    @get:Rule val main = MainDispatcherRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun UiText?.en(): String? = this?.resolve(context)

    private fun kiwix(id: String, language: String, category: String = "Reference", featured: Boolean = false, zimName: String = "n-$id") =
        packEntry(id, category = category, urls = listOf("https://download.kiwix.org/zim/$id.zim"), featured = featured, zimName = zimName)
            .copy(language = language)

    private val wikiEn = kiwix("wiki_en", "en", featured = true)
    private val wikiEs = kiwix("wiki_es", "es", featured = true)
    private val both = kiwix("phrasebook", "en,es")
    private val mul = kiwix("lowtech_mul", "mul")
    private val french = kiwix("encyclopedie_fr", "fr")
    private val medEn = kiwix("wikipedia_en_medicine_maxi", "en", "Medical", zimName = "wikipedia_en_medicine").copy(subjectOf = "wikipedia_en")
    private val medEs = kiwix("wikipedia_es_medicine_maxi", "es", "Medical", zimName = "wikipedia_es_medicine").copy(subjectOf = "wikipedia_es")
    private val chem = kiwix("wikipedia_es_chemistry_maxi", "es", "Science", zimName = "wikipedia_es_chemistry").copy(subjectOf = "wikipedia_es")

    private val catalog = FakeCatalogSource(catalogOf(listOf(wikiEn, wikiEs, both, mul, french, medEn, medEs, chem)))
    private val downloads = FakeDownloads()
    private val library = FakeLibrary()
    private val knowledge = MutableStateFlow(listOf("en"))

    private fun TestScope.viewModel(domain: String? = null): SourceViewModel {
        val vm = SourceViewModel(
            CatalogScope.KIWIX, catalog, downloads, library, FakeBookshelf(), FakeVolumes(listOf(volume("/internal", "Internal storage"))),
            FakePrefs(), DeviceTier.ASK, appScope = this, io = main.dispatcher, domain = domain, knowledge = knowledge,
        )
        backgroundScope.launch { vm.ui.collect {} }
        testScheduler.advanceUntilIdle()
        return vm
    }

    private fun SourceViewModel.ids() = ui.value.sections.flatMap { it.rows }.map { it.id }

    @Test fun anEnglishLibraryListsEnglishMultilingualAndMulPacks() = runTest(main.dispatcher) {
        val vm = viewModel()
        assertThat(vm.ids()).containsExactly("wiki_en", "phrasebook", "lowtech_mul", "wikipedia_en_medicine_maxi")
        assertThat(vm.ui.value.sections.first().rows.map { it.id }).containsExactly("wiki_en")
        assertThat(vm.ui.value.languageChip).isTrue()
    }

    @Test fun aSpanishLibraryListsSpanishPacksAndRecommendsItsOwn() = runTest(main.dispatcher) {
        knowledge.value = listOf("es")
        val vm = viewModel()
        // WikiMed keeps its own row in every language; the other Spanish subjects wait for Add a subject.
        assertThat(vm.ids()).containsExactly("wiki_es", "phrasebook", "lowtech_mul", "wikipedia_es_medicine_maxi")
        assertThat(vm.ui.value.sections.first { it.title == SourceViewModel.RECOMMENDED }.rows.map { it.id }).containsExactly("wiki_es")
    }

    @Test fun aBilingualLibraryListsBoth() = runTest(main.dispatcher) {
        knowledge.value = listOf("es", "en")
        assertThat(viewModel().ids()).containsAtLeast("wiki_en", "wiki_es", "phrasebook")
    }

    @Test fun allLanguagesAndASearchListEverything() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.setAllLanguages(true); testScheduler.advanceUntilIdle()
        assertThat(vm.ids()).containsAtLeast("wiki_es", "encyclopedie_fr")
        assertThat(vm.ui.value.allLanguages).isTrue()
        vm.setAllLanguages(false); vm.setQuery("encyclopedie"); testScheduler.advanceUntilIdle()
        assertThat(vm.ids()).containsExactly("encyclopedie_fr")
    }

    @Test fun anInstalledPackInAnotherLanguageAlwaysShows() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("u-encyclopedie_fr"))
        assertThat(viewModel().ids()).contains("encyclopedie_fr")
    }

    @Test fun aDomainRecommendsTheLibraryLanguagesTiers() = runTest(main.dispatcher) {
        catalog.mutable.value = app.granger.core.catalog.CatalogState(
            catalog = catalogOf(listOf(wikiEn, wikiEs, medEn.copy(domain = "medicine"), medEs.copy(domain = "medicine"))).copy(
                readiness = Readiness(
                    listOf(
                        Domain(
                            "medicine", "Medicine & first aid", listOf(medEn.id), listOf(medEn.id),
                            i18n = mapOf("es" to Copy(title = "Medicina y primeros auxilios")),
                            languages = mapOf("es" to Tiers(listOf(medEs.id), listOf(medEs.id))),
                        ),
                    ),
                ),
            ),
        )
        knowledge.value = listOf("es")
        val vm = viewModel(domain = "medicine")
        assertThat(vm.ui.value.domainTitle).isEqualTo("Medicine & first aid")
        assertThat(vm.ui.value.sections.first().rows.map { it.id }).containsExactly(medEs.id)
    }

    @Config(qualifiers = "es")
    @Test fun aDomainIsTitledInTheAppsLanguage() = runTest(main.dispatcher) {
        catalog.mutable.value = app.granger.core.catalog.CatalogState(
            catalog = catalogOf(listOf(medEs.copy(domain = "medicine"))).copy(
                readiness = Readiness(listOf(Domain("medicine", "Medicine & first aid", i18n = mapOf("es" to Copy(title = "Medicina y primeros auxilios"))))),
            ),
        )
        knowledge.value = listOf("es")
        assertThat(viewModel(domain = "medicine").ui.value.domainTitle).isEqualTo("Medicina y primeros auxilios")
    }

    @Test fun aSetRowTakesItsCopyFromTheCatalog() = runTest(main.dispatcher) {
        fun slice(id: String, zim: String, rank: Int, label: String, en: String) = kiwix(id, "es", zimName = zim).copy(
            setId = "wikipedia_es", setRank = rank, setLabel = label, setTitle = "Wikipedia en español",
            setDescription = "La enciclopedia libre.", flavour = "maxi",
            i18n = mapOf("en" to Copy(setTitle = "Wikipedia in Spanish", setDescription = "The free encyclopedia in Spanish.", setLabel = en)),
        )
        catalog.mutable.value = app.granger.core.catalog.CatalogState(
            catalog = catalogOf(
                listOf(
                    slice("wikipedia_es_top_maxi", "wikipedia_es_top", 1, "Los 50.000 mejores artículos", "Best 50,000 articles"),
                    slice("wikipedia_es_all_maxi", "wikipedia_es_all", 2, "Todo", "Everything"),
                ),
            ),
        )
        knowledge.value = listOf("es")
        val row = viewModel().ui.value.sections.flatMap { it.rows }.single { it.setId == "wikipedia_es" }
        // An English app reads the set's English copy.
        assertThat(row.title).isEqualTo("Wikipedia in Spanish")
        assertThat(row.description.en()).isEqualTo("The free encyclopedia in Spanish.")
        assertThat(row.slices.map { it.label }).containsExactly("Best 50,000 articles", "Everything").inOrder()
    }
}
