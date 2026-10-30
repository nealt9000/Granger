package app.granger.onboarding

import app.granger.bookEntry
import app.granger.catalogOf
import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.ModelRole
import app.granger.core.catalog.Onboarding
import app.granger.core.catalog.Preset
import app.granger.core.catalog.UseCase
import app.granger.modelEntry
import app.granger.packEntry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OnboardingModelTest {
    private val med = packEntry("wikipedia_en_medicine_nopic", size = 1_000, zimName = "wikipedia_en_medicine").copy(family = "wikipedia")
    private val medMaxi = packEntry("wikipedia_en_medicine_maxi", size = 1_900, zimName = "wikipedia_en_medicine").copy(family = "wikipedia")
    private val medline = packEntry("medlineplus.gov_en_all", size = 375).copy(family = "medlineplus.gov")
    private val voyage = packEntry("wikivoyage_en_all_maxi", size = 1_070).copy(family = "wikivoyage")
    private val france = packEntry("maps_en_france", size = 4_000).copy(family = "maps", title = "France")
    private val peru = packEntry("maps_en_peru", size = 500).copy(family = "maps", title = "Peru")
    private val europe = packEntry("maps_en_europe", size = 20_000).copy(family = "maps", title = "Europe")
    private val mdwikiNopic = packEntry("mdwiki_en_all_nopic", size = 800, zimName = "mdwiki_en_all").copy(family = "mdwiki")
    private val mdwikiMaxi = packEntry("mdwiki_en_all_maxi", size = 12_000, zimName = "mdwiki_en_all").copy(family = "mdwiki")
    private val bible = bookEntry("gc-bible", category = "Scripture & religion").copy(sizeBytes = 10, collection = "granger-classics")
    private val hamlet = bookEntry("gc-hamlet", category = "Drama").copy(sizeBytes = 20, collection = "granger-classics")

    private val catalog = catalogOf(
        packs = listOf(med, medMaxi, medline, voyage, france, peru, europe, mdwikiNopic, mdwikiMaxi),
        books = listOf(bible, hamlet),
        presets = listOf(
            Preset("efficient", "Efficient start", listOf("wikipedia_en_medicine_nopic", "gc-bible", "gc-hamlet")),
            Preset("comprehensive", "Comprehensive library", listOf("wikipedia_en_medicine_nopic", "gc-bible", "wikipedia_en_medicine_maxi", "medlineplus.gov_en_all", "gc-hamlet")),
        ),
    ).copy(
        onboarding = Onboarding(
            useCases = listOf(
                UseCase("medical", "Medical work", "Clinical.", listOf("wikipedia_en_medicine_maxi", "medlineplus.gov_en_all", "family:mdwiki")),
                UseCase("faith", "Faith & philosophy", "", listOf("@granger-classics")),
                UseCase("travel", "Travel", "", listOf("wikivoyage_en_all_maxi")),
            ),
            larger = listOf("maps_en_europe"),
            covered = mapOf("maps_en_europe" to listOf("Corsica", "Faroe Islands")),
        ),
    )

    @Test fun startChoicesFoldBooksIntoOneLineAndSkipInstalledOnes() {
        val choices = OnboardingModel.startChoices(catalog, installed = setOf("medlineplus.gov_en_all"))
        assertThat(choices.map { it.id }).containsExactly("efficient", "comprehensive").inOrder()
        val efficient = choices[0]
        assertThat(efficient.lines.map { it.title }).containsExactly("Pack wikipedia_en_medicine_nopic", "Granger Classics").inOrder()
        assertThat(efficient.lines[1].count).isEqualTo(2)
        assertThat(efficient.bytes()).isEqualTo(1_030)
        assertThat(efficient.bytes(unticked = setOf("collection:granger-classics"))).isEqualTo(1_000)
        val comprehensive = choices[1]
        assertThat(comprehensive.lines.single { it.id == "medlineplus.gov_en_all" }.installed).isTrue()
        // WikiMed with and without pictures are one ZIM: only the larger is listed (Neal, 2026-10-22).
        assertThat(comprehensive.lines.map { it.id }).containsExactly("collection:granger-classics", "wikipedia_en_medicine_maxi", "medlineplus.gov_en_all").inOrder()
        assertThat(comprehensive.bytes()).isEqualTo(30 + 1_900)
        assertThat(OnboardingModel.startChoices(catalogOf(), emptySet())).isEmpty()
    }

    @Test fun regionsAreCountriesThenLargerAreasAndSearchable() {
        val regions = OnboardingModel.regions(catalog, installed = setOf("maps_en_peru"))
        assertThat(regions.countries.map { it.title }).containsExactly("France", "Peru").inOrder()
        assertThat(regions.larger.map { it.title }).containsExactly("Europe")
        assertThat(regions.countries[1].installed).isTrue()
        assertThat(regions.matching("per").countries.map { it.title }).containsExactly("Peru")
        assertThat(regions.matching("eur").larger.map { it.title }).containsExactly("Europe")
        assertThat(regions.matching("  ")).isEqualTo(regions.copy(places = emptyList()))
        // A covered place is found by its own name and chooses its pack (Neal: Puerto Rico is in Central America).
        val corsica = regions.matching("cors").countries.single()
        assertThat(corsica.title).isEqualTo("Corsica")
        assertThat(corsica.pack.id).isEqualTo("maps_en_europe")
        assertThat(corsica.place).isEqualTo("Corsica")
        assertThat(regions.places.map { it.title }).containsExactly("Corsica", "Faroe Islands").inOrder()
    }

    @Test fun additionsLeaveOutWhatIsChosenOrInstalledAndTakeAFamilysSmallestPack() {
        val additions = OnboardingModel.additions(catalog, installed = setOf("medlineplus.gov_en_all"), chosen = setOf("wikipedia_en_medicine_maxi"))
        val medical = additions.first { it.useCase.id == "medical" }
        assertThat(medical.lines.map { it.id }).containsExactly("mdwiki_en_all_nopic")
        assertThat(medical.bytes).isEqualTo(800)
        val faith = additions.first { it.useCase.id == "faith" }
        assertThat(faith.lines.single().title).isEqualTo("Granger Classics")
        assertThat(faith.packs).isEqualTo(2)
        assertThat(OnboardingModel.additions(catalogOf(), emptySet(), emptySet())).isEmpty()
    }

    @Test fun theReviewCountsEveryItemOnceAndNothingInstalled() {
        val installed = setOf("medlineplus.gov_en_all")
        val start = OnboardingModel.startChoices(catalog, installed)[1] // comprehensive
        val region = OnboardingModel.regions(catalog, installed).countries.first { it.title == "Peru" }
        val additions = OnboardingModel.additions(catalog, installed, chosen = start.chosen().flatMap { it.items }.map { it.id }.toSet())
        val review = OnboardingModel.review(start, unticked = setOf("wikipedia_en_medicine_nopic"), region, additions, freeBytes = 10_000)
        assertThat(review.groups.map { it.title }).containsExactly("Comprehensive library", "Your region", "What you'll use it for").inOrder()
        assertThat(review.items.map { it.id }).containsExactly(
            "gc-bible", "wikipedia_en_medicine_maxi", "gc-hamlet", "maps_en_peru", "mdwiki_en_all_nopic", "wikivoyage_en_all_maxi",
        )
        assertThat(review.bytes).isEqualTo(10 + 1_900 + 20 + 500 + 800 + 1_070)
        // Unticking the medicine line by its surviving (larger) id removes it.
        assertThat(OnboardingModel.review(start, setOf("wikipedia_en_medicine_maxi"), null, emptyList(), 1).items.map { it.id }).doesNotContain("wikipedia_en_medicine_maxi")
        assertThat(review.fits).isTrue()
        assertThat(review.copy(freeBytes = 100).fits).isFalse()

        // A use case chosen alongside a start that already holds its items adds nothing twice.
        val twice = OnboardingModel.review(start, emptySet(), null, OnboardingModel.additions(catalog, installed, emptySet()), 1)
        assertThat(twice.items.map { it.id }).containsNoDuplicates()
        assertThat(twice.items.map { it.id }).doesNotContain("medlineplus.gov_en_all")

        // Nothing chosen at all.
        assertThat(OnboardingModel.review(null, emptySet(), null, emptyList(), 1).isEmpty).isTrue()
    }

    private val e2b = modelEntry("gemma-4-e2b-gpu", ModelRole.SMALL, size = 2_000)
    private val e4b = modelEntry("gemma-4-e4b-gpu", ModelRole.LARGE, size = 3_000)
    private val embedder = modelEntry("embeddinggemma-2-text-270m", ModelRole.EMBEDDER, size = 160)
    private val withModels = catalog.copy(models = listOf(e4b, e2b, embedder))

    @Test fun theReviewKeepsThePresetsNameOnlyWhileItIsWhole() {
        val efficient = OnboardingModel.startChoices(catalog, emptySet()).first { it.id == "efficient" }
        assertThat(OnboardingModel.review(efficient, emptySet(), null, emptyList(), 10_000).groups.single().title).isEqualTo("Efficient start")
        val trimmed = OnboardingModel.review(efficient, setOf("wikipedia_en_medicine_nopic"), null, emptyList(), 10_000)
        assertThat(trimmed.groups.single().title).isEqualTo("Your picks")
    }

    @Test fun theReviewSaysHowMuchIsAsksIndexes() {
        val vectors = app.granger.core.catalog.VectorsEntry(sizeBytes = 70)
        val withIndex = med.copy(vectors = vectors)
        val review = OnboardingModel.review(null, emptySet(), Region(withIndex, installed = false), emptyList(), 10_000)
        assertThat(review.bytes).isEqualTo(1_070)
        assertThat(review.indexBytes).isEqualTo(70)
    }

    @Test fun theSuggestedRegionIsThePhonesCountryByNameOrAlias() {
        val us = packEntry("maps_en_usa", size = 9_000).copy(family = "maps", title = "United States of America")
        val gb = packEntry("maps_en_uk", size = 1_000).copy(family = "maps", title = "United Kingdom")
        val sn = packEntry("maps_en_senegal", size = 100).copy(family = "maps", title = "Senegal and Gambia")
        val regions = OnboardingModel.regions(catalog.copy(packs = catalog.packs + us + gb + sn), emptySet())
        assertThat(OnboardingModel.suggestedRegion(regions, "FR")!!.title).isEqualTo("France")
        assertThat(OnboardingModel.suggestedRegion(regions, "gb")!!.title).isEqualTo("United Kingdom")
        assertThat(OnboardingModel.suggestedRegion(regions, "US")!!.title).isEqualTo("United States of America")
        assertThat(OnboardingModel.suggestedRegion(regions, "GM")!!.title).isEqualTo("Senegal and Gambia")
        // No pack of its own, an unknown code, no code: nothing suggested. Larger areas are never suggested.
        assertThat(OnboardingModel.suggestedRegion(regions, "IN")).isNull()
        assertThat(OnboardingModel.suggestedRegion(regions, "ZZ")).isNull()
        assertThat(OnboardingModel.suggestedRegion(regions, "")).isNull()
    }

    @Test fun askIsTheAnsweringModelPlusTheEmbedderWhenDense() {
        val line = OnboardingModel.askLine(withModels, DeviceTier.ASK, dense = true, installed = emptySet())!!
        assertThat(line.id).isEqualTo(OnboardingModel.ASK)
        assertThat(line.title).isEqualTo("Ask (Preview)")
        assertThat(line.items.map { it.id }).containsExactly("gemma-4-e2b-gpu", "embeddinggemma-2-text-270m").inOrder()
        assertThat(line.bytes).isEqualTo(2_160)
        assertThat(OnboardingModel.askLine(withModels, DeviceTier.ASK, dense = false, installed = emptySet())!!.items.map { it.id })
            .containsExactly("gemma-4-e2b-gpu")
    }

    @Test fun askIsNotOfferedWhereItCantRunOrHasNoModel() {
        assertThat(OnboardingModel.askLine(withModels, DeviceTier.LIBRARY_ONLY, dense = true, installed = emptySet())).isNull()
        // A release build's catalog drops unevaluated models: nothing to offer.
        assertThat(OnboardingModel.askLine(catalog, DeviceTier.ASK, dense = true, installed = emptySet())).isNull()
        assertThat(OnboardingModel.askLine(catalog.copy(models = listOf(embedder)), DeviceTier.ASK, dense = true, installed = emptySet())).isNull()
    }

    @Test fun askLeavesOutWhatIsInstalledAndVanishesWhenAllIs() {
        val line = OnboardingModel.askLine(withModels, DeviceTier.ASK, dense = true, installed = setOf("gemma-4-e2b-gpu"))!!
        assertThat(line.items.map { it.id }).containsExactly("embeddinggemma-2-text-270m")
        assertThat(OnboardingModel.askLine(withModels, DeviceTier.ASK, dense = true, installed = setOf("gemma-4-e2b-gpu", "embeddinggemma-2-text-270m"))).isNull()
    }

    @Test fun theReviewListsAskAsItsOwnGroup() {
        val ask = OnboardingModel.askLine(withModels, DeviceTier.ASK, dense = true, installed = emptySet())
        val review = OnboardingModel.review(null, emptySet(), null, emptyList(), 10_000, ask)
        assertThat(review.groups.map { it.title }).containsExactly("Ask (Preview)")
        assertThat(review.groups.single().lines.single().title).isEqualTo("The librarian")
        assertThat(review.bytes).isEqualTo(2_160)
    }

    private fun voice(id: String, title: String, size: Long) = app.granger.core.catalog.VoiceEntry(
        id = id, title = title, engine = "piper", language = "en-US", sizeBytes = size, sha256 = "d".repeat(64),
        url = "https://v.test/$id.zip", license = "CC BY 4.0",
    )
    private val ljspeech = voice("vits-piper-en_US-ljspeech-medium", "Granger voice (ljspeech)", 67)
    private val maggie = voice(OnboardingModel.VOICE_ID, "Granger voice (Maggie & Martin)", 80)

    @Test fun theVoiceLineIsMaggieAndMartinUntilAnyCatalogVoiceIsHere() {
        val withVoices = catalog.copy(voices = listOf(ljspeech, maggie))
        val line = OnboardingModel.voiceLine(withVoices, emptySet())!!
        assertThat(line.title).isEqualTo("Read aloud")
        assertThat(line.items.single().id).isEqualTo(OnboardingModel.VOICE_ID)
        assertThat(OnboardingModel.voiceLine(withVoices, setOf(ljspeech.id))).isNull()
        assertThat(OnboardingModel.voiceLine(catalog, emptySet())).isNull()
        val review = OnboardingModel.review(null, emptySet(), null, emptyList(), 1_000, voice = line)
        assertThat(review.groups.single().title).isEqualTo("Read aloud")
        assertThat(review.groups.single().lines.single().title).isEqualTo("Granger voice (Maggie & Martin)")
    }

    @Test fun saveSpaceSwapsEachPackForItsNoPicturesFlavour() {
        val pictures = catalog.copy(presets = listOf(Preset("efficient", "Efficient start", listOf("wikipedia_en_medicine_maxi", "medlineplus.gov_en_all", "gc-bible"))))
        val withPictures = OnboardingModel.startChoices(pictures, emptySet()).single()
        assertThat(withPictures.lines.map { it.id }).containsExactly("wikipedia_en_medicine_maxi", "medlineplus.gov_en_all", "collection:granger-classics").inOrder()
        val saving = OnboardingModel.startChoices(pictures, emptySet(), saveSpace = true).single()
        // MedlinePlus has no no-pictures flavour: it stays.
        assertThat(saving.lines.map { it.id }).containsExactly("wikipedia_en_medicine_nopic", "medlineplus.gov_en_all", "collection:granger-classics").inOrder()
        assertThat(saving.bytes()).isLessThan(withPictures.bytes())
    }
}
