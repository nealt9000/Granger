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
}
