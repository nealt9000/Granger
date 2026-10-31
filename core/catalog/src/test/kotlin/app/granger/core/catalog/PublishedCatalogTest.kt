package app.granger.core.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.util.Base64

/** The catalog in catalog/ must verify with the committed key and lose no entries to validation. */
class PublishedCatalogTest {
    private fun repoFile(path: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "catalog/catalog.json").isFile) dir = dir.parentFile
        return File(dir ?: error("catalog/catalog.json not found"), path)
    }

    @Test fun publishedCatalogVerifiesAndParsesCompletely() {
        val bytes = repoFile("catalog/catalog.json").readBytes()
        val key = Base64.getDecoder().decode(repoFile("catalog/public-key.txt").readText().trim())
        val result = CatalogParser.parse(bytes, repoFile("catalog/catalog.json.sig").readBytes(), key)
        val catalog = (result as ParseResult.Ok).catalog

        val raw = kotlinx.serialization.json.Json.parseToJsonElement(bytes.decodeToString()) as kotlinx.serialization.json.JsonObject
        assertThat(catalog.packs.size).isEqualTo((raw["packs"] as kotlinx.serialization.json.JsonArray).size)
        assertThat(catalog.models.size).isEqualTo((raw["models"] as kotlinx.serialization.json.JsonArray).size)
        assertThat(catalog.books.size).isEqualTo((raw["books"] as kotlinx.serialization.json.JsonArray).size)
        assertThat(catalog.books).hasSize(347)
        assertThat(catalog.packs.map { it.id }).doesNotContain("granger_classics_en")
        // Starter: the 4 non-Classics items plus the 347 books.
        assertThat(catalog.presets.single { it.id == "starter" }.items).hasSize(351)
        assertThat(catalog.presets.single { it.id == "granger-classics" }.items).hasSize(347)
    }

    /** Plan H1: the readiness model rides in the catalog, and every pack and book names its domain or is storage-only. */
    @Test fun publishedCatalogCarriesTheOnboardingTables() {
        val bytes = repoFile("catalog/catalog.json").readBytes()
        val key = Base64.getDecoder().decode(repoFile("catalog/public-key.txt").readText().trim())
        val catalog = (CatalogParser.parse(bytes, repoFile("catalog/catalog.json.sig").readBytes(), key) as ParseResult.Ok).catalog

        val onboarding = catalog.onboarding!!
        assertThat(onboarding.useCases.map { it.id }).containsExactly("offgrid", "travel", "study", "build", "medical", "software", "faith").inOrder()
        // The generator checked every token, so the parser dropped none.
        val raw = kotlinx.serialization.json.Json.parseToJsonElement(bytes.decodeToString()) as kotlinx.serialization.json.JsonObject
        val rawBlock = raw["onboarding"] as kotlinx.serialization.json.JsonObject
        val rawItems = (rawBlock["useCases"] as kotlinx.serialization.json.JsonArray).sumOf {
            ((it as kotlinx.serialization.json.JsonObject)["items"] as kotlinx.serialization.json.JsonArray).size
        }
        assertThat(onboarding.useCases.sumOf { it.items.size }).isEqualTo(rawItems)
        assertThat(onboarding.larger).hasSize((rawBlock["larger"] as kotlinx.serialization.json.JsonArray).size)
        val maps = catalog.packs.filter { it.family == "maps" }
        assertThat(maps.size).isGreaterThan(150)
        assertThat(onboarding.larger.all { id -> maps.any { it.id == id } }).isTrue()
        assertThat(onboarding.useCases.all { it.blurb.isNotBlank() }).isTrue()
        assertThat(onboarding.covered["maps_en_central-america"]).contains("Puerto Rico")
        assertThat(onboarding.covered.keys.all { id -> maps.any { it.id == id } }).isTrue()
    }

    @Test fun publishedCatalogCarriesTheReadinessModel() {
        val bytes = repoFile("catalog/catalog.json").readBytes()
        val key = Base64.getDecoder().decode(repoFile("catalog/public-key.txt").readText().trim())
        val catalog = (CatalogParser.parse(bytes, repoFile("catalog/catalog.json.sig").readBytes(), key) as ParseResult.Ok).catalog

        val domains = catalog.readiness!!.domains
        assertThat(domains.map { it.id }).containsExactly(
            "medicine", "food", "making", "science", "technology", "maps", "reference", "literature", "thought",
        ).inOrder()
        val ids = domains.mapTo(HashSet()) { it.id }
        for (domain in domains) {
            assertThat(domain.essential).isNotEmpty()
            assertThat(domain.comprehensive).containsAtLeastElementsIn(domain.essential)
        }
        // The parser keeps only tokens it can resolve; the generator checked them too, so none were lost.
        val raw = kotlinx.serialization.json.Json.parseToJsonElement(bytes.decodeToString()) as kotlinx.serialization.json.JsonObject
        val rawDomains = raw["readiness"]!!.let { it as kotlinx.serialization.json.JsonObject }["domains"] as kotlinx.serialization.json.JsonArray
        assertThat(domains.sumOf { it.comprehensive.size }).isEqualTo(
            rawDomains.sumOf { ((it as kotlinx.serialization.json.JsonObject)["comprehensive"] as kotlinx.serialization.json.JsonArray).size },
        )
        assertThat(catalog.packs.filter { it.domain.isNotBlank() && it.domain !in ids }).isEmpty()
        assertThat(catalog.packs.count { it.domain.isNotBlank() }).isGreaterThan(800)
        assertThat(catalog.books.all { it.domain == "literature" || it.domain == "thought" }).isTrue()
        assertThat(catalog.presets.single { it.id == "efficient" }.items).isNotEmpty()
        assertThat(catalog.presets.single { it.id == "comprehensive" }.items.size)
            .isAtLeast(catalog.presets.single { it.id == "efficient" }.items.size)
    }

    /** Plan KB: the 41 Gutenberg packs and the 9 nautilus packs are books; every flavoured pack names its flavour. */
    @Test fun publishedCatalogMarksTheBookPacksAndFlavours() {
        val bytes = repoFile("catalog/catalog.json").readBytes()
        val key = Base64.getDecoder().decode(repoFile("catalog/public-key.txt").readText().trim())
        val catalog = (CatalogParser.parse(bytes, repoFile("catalog/catalog.json.sig").readBytes(), key) as ParseResult.Ok).catalog

        val books = catalog.packs.filter { it.isBookPack }
        assertThat(books).hasSize(50)
        assertThat(books.count { it.family == "gutenberg" }).isEqualTo(41)
        assertThat(catalog.packs.filter { it.family == "gutenberg" }.all { it.isBookPack }).isTrue()
        assertThat(books.filter { it.family != "gutenberg" }.map { it.zimName }).containsExactly(
            "zimgit-food-preparation_en", "zimgit-knots_en", "zimgit-medicine_en", "zimgit-post-disaster_en",
            "zimgit-water_en", "usda-2015_en", "prunelle_en_budding-authors", "prunelle_en_draw-your-african-story",
            "prunelle_en_interactive-books",
        )
        assertThat(catalog.packs.filter { it.flavour.isNotEmpty() }.all { it.id == "${it.zimName}_${it.flavour}" }).isTrue()
        assertThat(catalog.packs.groupingBy { it.flavour }.eachCount())
            .containsExactly("", 771, "maxi", 93, "nopic", 50, "mini", 10)
    }

    /** Plan WF: Wikipedia and Wikivoyage as sets, the subjects with what they add, the sample marked. */
    @Test fun publishedCatalogCarriesTheWikipediaFamily() {
        val bytes = repoFile("catalog/catalog.json").readBytes()
        val key = Base64.getDecoder().decode(repoFile("catalog/public-key.txt").readText().trim())
        val catalog = (CatalogParser.parse(bytes, repoFile("catalog/catalog.json.sig").readBytes(), key) as ParseResult.Ok).catalog

        val wikipedia = catalog.packs.filter { it.setId == "wikipedia_en" }
        assertThat(wikipedia.map { it.zimName to it.setRank }.distinct().sortedBy { it.second }).containsExactly(
            "wikipedia_en_100" to 1, "wikipedia_en_top" to 2, "wikipedia_en_top1m" to 3, "wikipedia_en_all" to 4,
        ).inOrder()
        assertThat(wikipedia.map { it.setLabel }.distinct()).containsExactly(
            "100 essential articles", "Best 50,000 articles", "Top 1 million articles", "Everything",
        )
        assertThat(wikipedia.size).isEqualTo(10)
        assertThat(catalog.packs.single { it.id == "wikipedia_en_top_nopic" }.title).isEqualTo("Wikipedia: Best 50,000 articles")
        val voyage = catalog.packs.filter { it.setId == "wikivoyage_en" }
        assertThat(voyage.map { it.setLabel }.distinct()).containsExactly("Europe", "The whole world")

        val subjects = catalog.packs.filter { it.isSubject }
        assertThat(subjects.map { it.zimName }.distinct()).hasSize(24)
        assertThat(subjects.all { it.subjectOf == "wikipedia_en" && it.articles > 0 }).isTrue()
        val med = catalog.packs.single { it.id == "wikipedia_en_medicine_maxi" }
        assertThat(med.adds.getValue("wikipedia_en_top")).isWithin(0.001).of(0.955)
        assertThat(med.adds.getValue("wikipedia_en_top1m")).isWithin(0.001).of(0.656)
        // Every flavour of a curated pack takes its base title (the KB title bug).
        assertThat(catalog.packs.filter { it.zimName == "wikipedia_en_medicine" }.map { it.title }.distinct()).containsExactly("WikiMed")
        assertThat(catalog.packs.filter { it.sample }.map { it.zimName }.distinct()).containsExactly("wikipedia_en_ray-charles")
    }

    /** Plan H4: the calibrated tiers and the containment table. */
    @Test fun publishedCatalogCarriesTheCalibration() {
        val bytes = repoFile("catalog/catalog.json").readBytes()
        val key = Base64.getDecoder().decode(repoFile("catalog/public-key.txt").readText().trim())
        val catalog = (CatalogParser.parse(bytes, repoFile("catalog/catalog.json.sig").readBytes(), key) as ParseResult.Ok).catalog

        val readiness = catalog.readiness!!
        assertThat(readiness.containers("wikipedia_en_top")).contains("wikipedia_en_top1m")
        val comprehensive = catalog.presets.single { it.id == "comprehensive" }.items
        assertThat(comprehensive).doesNotContain("stackoverflow.com_en_all")
        assertThat(comprehensive).contains("wikipedia_en_top1m_nopic")
        assertThat(comprehensive).doesNotContain("wikipedia_en_top_nopic")
        // One flavour per ZIM: WikiMed once, with pictures.
        assertThat(comprehensive).contains("wikipedia_en_medicine_maxi")
        assertThat(comprehensive).doesNotContain("wikipedia_en_medicine_nopic")
    }
}
