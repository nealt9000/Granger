package app.granger.readiness

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.granger.SHA_A
import app.granger.UiText
import app.granger.core.catalog.Catalog
import app.granger.core.catalog.Copy
import app.granger.core.catalog.Domain
import app.granger.core.catalog.PackEntry
import app.granger.core.catalog.Readiness
import app.granger.core.catalog.Tiers
import app.granger.core.library.PackStatus
import app.granger.pack
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Readiness over the library's languages (Plan L3 Task 7). The English model is pinned by [ReadinessModelTest]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadinessLanguagesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun UiText?.text() = this?.resolve(context)

    private fun entry(id: String, zimName: String, domain: String, size: Long, language: String = "en") = PackEntry(
        id = id, title = "Title $id", category = "Reference", language = language, sizeBytes = size, sha256 = SHA_A,
        urls = listOf("https://download.kiwix.org/zim/$id.zim"), snapshotDate = "2026-10-10", license = "CC BY-SA 4.0",
        hasFulltextIndex = true, zimUuid = "u-$id", zimName = zimName, family = "wikipedia", domain = domain,
    )

    private val medEn = entry("wikipedia_en_medicine_maxi", "wikipedia_en_medicine", "medicine", 1_800)
    private val medline = entry("medlineplus.gov_en_all", "medlineplus.gov_en_all", "medicine", 400)
    private val medEs = entry("wikipedia_es_medicine_maxi", "wikipedia_es_medicine", "medicine", 500, "es")
    private val medlineEs = entry("medlineplus.gov_es_all", "medlineplus.gov_es_all", "medicine", 300, "es")
    private val readyEs = entry("ready.gov_es_all", "ready.gov_es_all", "food", 200, "es")
    private val water = entry("water_en", "water_en", "food", 100)
    private val stack = entry("stackexchange_en", "stackexchange_en", "technology", 100)

    private val catalog = Catalog(
        1, "2026-10-30T00:00:00Z", packs = listOf(medEn, medline, medEs, medlineEs, readyEs, water, stack),
        readiness = Readiness(
            listOf(
                Domain(
                    "medicine", "Medicine & first aid", listOf(medEn.id), listOf(medEn.id, medline.id),
                    i18n = mapOf("es" to Copy(title = "Medicina y primeros auxilios")),
                    languages = mapOf("es" to Tiers(listOf(medEs.id), listOf(medEs.id, medlineEs.id))),
                ),
                // Spanish Food has no essential tier, only a comprehensive one (the generator folds essential in).
                Domain("food", "Food & water", listOf(water.id), listOf(water.id), languages = mapOf("es" to Tiers(emptyList(), listOf(readyEs.id)))),
                // No Spanish list at all.
                Domain("technology", "Technology", listOf(stack.id), listOf(stack.id)),
            ),
        ),
    )
    private val today = LocalDate.of(2026, 10, 30)

    private fun installed(e: PackEntry) =
        pack(e.zimUuid, e.title, PackStatus.OK).copy(filePath = "/p/${e.id}.zim", name = e.zimName, sizeBytes = e.sizeBytes, hasFulltextIndex = true)

    private fun report(langs: List<String>, vararg packs: PackEntry, app: String = "en") =
        ReadinessModel.report(catalog, packs.map(::installed), emptyList(), emptyList(), today, langs, app)

    private fun ReadinessReport.domain(id: String) = domains.single { it.id == id }

    @Test fun aSpanishOnlyLibraryIsMeasuredOnSpanishTiers() {
        val none = report(listOf("es"))
        assertThat(none.domain("medicine").essentialBytes).isEqualTo(500)
        assertThat(none.domain("medicine").comprehensiveBytes).isEqualTo(800)
        assertThat(none.domain("medicine").missingEssential.map { it.id }).containsExactly(medEs.id)
        // The English WikiMed is no essential of a Spanish library.
        assertThat(report(listOf("es"), medEn).domain("medicine").coverage).isEqualTo(Coverage.NONE)
        assertThat(report(listOf("es"), medEs).domain("medicine").coverage).isEqualTo(Coverage.BASIC)
        assertThat(report(listOf("es"), medEs, medlineEs).domain("medicine").coverage).isEqualTo(Coverage.COMPREHENSIVE)
    }

    @Test fun aLanguageWithNoEssentialTierIsBasicOnceAnyOfItsComprehensiveIsHere() {
        assertThat(report(listOf("es")).domain("food").coverage).isEqualTo(Coverage.NONE)
        assertThat(report(listOf("es")).domain("food").missingEssential).isEmpty()
        assertThat(report(listOf("es"), readyEs).domain("food").coverage).isEqualTo(Coverage.COMPREHENSIVE)
    }

    @Test fun aBilingualLibraryGetsTheBestOfEachLanguage() {
        // Spanish essential only: Basic.
        assertThat(report(listOf("en", "es"), medEs).domain("medicine").coverage).isEqualTo(Coverage.BASIC)
        // English essential plus the whole Spanish tier: Comprehensive, from Spanish.
        assertThat(report(listOf("en", "es"), medEn, medEs, medlineEs).domain("medicine").coverage).isEqualTo(Coverage.COMPREHENSIVE)
        // A missing essential is the main language's.
        assertThat(report(listOf("en", "es")).domain("medicine").missingEssential.map { it.id }).containsExactly(medEn.id)
        assertThat(report(listOf("es", "en")).domain("medicine").missingEssential.map { it.id }).containsExactly(medEs.id)
        // Technology has no Spanish list, but English is on.
        assertThat(report(listOf("es", "en"), stack).domain("technology").coverage).isEqualTo(Coverage.COMPREHENSIVE)
        assertThat(report(listOf("es", "en")).domain("technology").emptyNote).isNull()
    }

    @Test fun aDomainEmptyInEveryLanguageSaysSo() {
        val tech = report(listOf("es")).domain("technology")
        assertThat(tech.coverage).isEqualTo(Coverage.NONE)
        assertThat(tech.emptyNote.text()).isEqualTo("No Spanish packs for this area yet")
        assertThat(coverageText(tech).text()).isEqualTo("No Spanish packs for this area yet")
        // No essential to suggest, so no empty-domain signal either.
        assertThat(report(listOf("es")).signals.filterIsInstance<Signal.EmptyDomain>().map { it.domain }).doesNotContain("technology")
    }

    @Test fun domainTitlesComeFromTheAppLanguagesCopy() {
        assertThat(report(listOf("es"), app = "es").domain("medicine").title).isEqualTo("Medicina y primeros auxilios")
        assertThat(report(listOf("es"), app = "en").domain("medicine").title).isEqualTo("Medicine & first aid")
        // No Spanish copy: English.
        assertThat(report(listOf("es"), app = "es").domain("food").title).isEqualTo("Food & water")
    }
}
