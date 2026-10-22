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
}
