package app.granger.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.granger.UiText
import app.granger.catalogOf
import app.granger.core.catalog.Copy
import app.granger.core.catalog.Domain
import app.granger.core.catalog.Items
import app.granger.core.catalog.Onboarding
import app.granger.core.catalog.Preset
import app.granger.core.catalog.Readiness
import app.granger.core.catalog.Tiers
import app.granger.core.catalog.UseCase
import app.granger.packEntry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pick a start by language (Plan L3 Task 7). The English starts are pinned by [OnboardingModelTest]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnboardingLanguagesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun UiText.en() = resolve(context)

    private val topEn = packEntry("wikipedia_en_top_maxi", size = 7_000, zimName = "wikipedia_en_top")
    private val topEs = packEntry("wikipedia_es_top_maxi", size = 4_000, zimName = "wikipedia_es_top").copy(language = "es", title = "Wikipedia en español")
    private val topEsNopic = packEntry("wikipedia_es_top_nopic", size = 1_200, zimName = "wikipedia_es_top").copy(language = "es")
    private val medEs = packEntry("wikipedia_es_medicine_maxi", size = 500, zimName = "wikipedia_es_medicine").copy(language = "es")
    private val medlineEs = packEntry("medlineplus.gov_es_all", size = 300).copy(language = "es")
    private val stackEn = packEntry("stackoverflow_en", size = 9_000).copy()

    private val catalog = catalogOf(
        packs = listOf(topEn, topEs, topEsNopic, medEs, medlineEs, stackEn),
        presets = listOf(
            Preset("efficient", "Efficient start", listOf(topEn.id), i18n = mapOf("es" to Copy(title = "Comienzo eficiente"))),
            Preset("comprehensive", "Comprehensive library", listOf(topEn.id)),
            Preset("efficient-es", "Efficient start", listOf(topEs.id, medEs.id), language = "es", i18n = mapOf("es" to Copy(title = "Comienzo eficiente"))),
            Preset("comprehensive-es", "Comprehensive library", listOf(topEs.id, medEs.id, medlineEs.id), language = "es"),
        ),
    ).copy(
        readiness = Readiness(
            listOf(Domain("reference", "General reference", listOf(topEn.id), listOf(topEn.id), languages = mapOf("es" to Tiers(listOf(topEs.id), listOf(topEs.id))))),
        ),
        onboarding = Onboarding(
            listOf(
                UseCase("medical", "Medical work", "Clinical references.", listOf(stackEn.id), i18n = mapOf("es" to Copy("Trabajo médico", "Referencias clínicas.")), languages = mapOf("es" to Items(listOf(medlineEs.id)))),
                UseCase("software", "Software", "Code.", listOf(stackEn.id)),
            ),
        ),
    )

    @Test fun aSpanishLibraryIsOfferedTheSpanishStarts() {
        val starts = OnboardingModel.startChoices(catalog, emptySet(), language = "es", appLanguage = "es")
        assertThat(starts.map { it.id }).containsExactly(OnboardingModel.EFFICIENT, OnboardingModel.COMPREHENSIVE).inOrder()
        assertThat(starts[0].title).isEqualTo("Comienzo eficiente")
        assertThat(starts[0].lines.flatMap { it.items }.map { it.id }).containsExactly(topEs.id, medEs.id)
        assertThat(starts[1].title).isEqualTo("Comprehensive library")
        // Save space swaps flavours as it does in English.
        val small = OnboardingModel.startChoices(catalog, emptySet(), saveSpace = true, language = "es")
        assertThat(small[0].lines.flatMap { it.items }.map { it.id }).contains(topEsNopic.id)
    }

    @Test fun anEnglishLibraryKeepsTheEnglishStarts() {
        val starts = OnboardingModel.startChoices(catalog, emptySet())
        assertThat(starts.map { it.title }).containsExactly("Efficient start", "Comprehensive library").inOrder()
        assertThat(starts[0].lines.single().items.single().id).isEqualTo(topEn.id)
    }

    @Test fun aSecondLanguageAddsItsEncyclopediaAsItsOwnGroup() {
        val second = OnboardingModel.secondLine(catalog, "es", emptySet())!!
        assertThat(second.title.en()).isEqualTo("Encyclopedia in Spanish")
        assertThat(second.items.single().id).isEqualTo(topEs.id)
        assertThat(OnboardingModel.secondLine(catalog, "es", emptySet(), saveSpace = true)!!.items.single().id).isEqualTo(topEsNopic.id)
        assertThat(OnboardingModel.secondLine(catalog, "es", setOf(topEs.id))).isNull()
        val start = OnboardingModel.startChoices(catalog, emptySet()).first()
        val review = OnboardingModel.review(start, emptySet(), null, emptyList(), 100_000, second = second)
        assertThat(review.groups.map { it.title.en() }).containsExactly("Efficient start", "Encyclopedia in Spanish").inOrder()
        assertThat(review.groups[1].lines.single().title.en()).isEqualTo("Wikipedia en español")
    }

    @Test fun useCasesReadEachLibraryLanguagesItems() {
        val es = OnboardingModel.additions(catalog, emptySet(), emptySet(), listOf("es"), appLanguage = "es")
        // Software has nothing Spanish: it isn't offered to a Spanish library.
        assertThat(es.map { it.useCase.id }).containsExactly("medical")
        assertThat(es.single().title).isEqualTo("Trabajo médico")
        assertThat(es.single().blurb).isEqualTo("Referencias clínicas.")
        assertThat(es.single().lines.flatMap { it.items }.map { it.id }).containsExactly(medlineEs.id)
        val both = OnboardingModel.additions(catalog, emptySet(), emptySet(), listOf("es", "en"))
        assertThat(both.first { it.useCase.id == "medical" }.lines.flatMap { it.items }.map { it.id }).containsExactly(medlineEs.id, stackEn.id).inOrder()
        assertThat(both.map { it.useCase.id }).containsExactly("medical", "software")
    }
}
