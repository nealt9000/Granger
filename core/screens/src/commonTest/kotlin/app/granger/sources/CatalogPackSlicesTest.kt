package app.granger.sources

import app.granger.core.catalog.Catalog
import app.granger.core.catalog.PackEntry
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.core.library.SliceKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Each installed pack's set and rank from the catalog, by ZIM name (Plan WF Decision 6; shared since Plan I7 Task 7b). */
class CatalogPackSlicesTest {
    private fun entry(id: String, zimName: String, set: String = "", rank: Int = 0) = PackEntry(
        id = id, title = id, category = "wikipedia", language = "eng", sizeBytes = 1, sha256 = "0".repeat(64),
        urls = listOf("https://example.org/$id.zim"), snapshotDate = "2026-11-01", license = "CC-BY-SA", hasFulltextIndex = true,
        zimUuid = "u-$id", zimName = zimName, setId = set, setRank = rank,
    )

    private fun pack(name: String) = Pack(
        id = "id-$name", filePath = "/packs/$name.zim", title = name, description = "", language = "eng", date = "2026-11-01",
        sizeBytes = 1, hasFulltextIndex = true, status = PackStatus.OK, addedAt = 0, name = name,
    )

    @Test fun aSliceIsItsSetAndRankAnOtherPackIsNone() {
        var catalog: Catalog? = Catalog(
            formatVersion = 1, generatedAt = "2026-11-01T00:00:00Z",
            packs = listOf(
                entry("top", "wikipedia_en_top", "wikipedia_en", 2),
                entry("all", "wikipedia_en_all", "wikipedia_en", 3),
                entry("chem", "wikipedia_en_chemistry"),
            ),
        )
        val slices = CatalogPackSlices { catalog }
        assertEquals(SliceKey("wikipedia_en", 2), slices.of(pack("wikipedia_en_top")))
        assertEquals(SliceKey("wikipedia_en", 3), slices.of(pack("wikipedia_en_all")))
        assertNull(slices.of(pack("wikipedia_en_chemistry")), "a subject pack isn't a slice")
        assertNull(slices.of(pack("based.cooking_en_all")), "a pack the catalog doesn't list")

        // A new catalog is read again; none answers nothing.
        catalog = Catalog(formatVersion = 1, generatedAt = "2026-11-02T00:00:00Z", packs = listOf(entry("top", "wikipedia_en_top", "wikipedia_en", 1)))
        assertEquals(SliceKey("wikipedia_en", 1), slices.of(pack("wikipedia_en_top")))
        catalog = null
        assertNull(slices.of(pack("wikipedia_en_top")))
    }
}
