package app.granger.sources

import app.granger.UiText
import app.granger.core.catalog.Catalog
import app.granger.core.catalog.PackEntry
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tidy-up offer once a bigger slice of a set is in (Plan WF Decision 5), shared with the iPhone since Plan I7 Task 7c. */
class SetTidyOffersTest {
    private fun entry(id: String, zimName: String, rank: Int, label: String, size: Long) = PackEntry(
        id = id, title = id, category = "wikipedia", language = "eng", sizeBytes = size, sha256 = "0".repeat(64),
        urls = listOf("https://example.org/$id.zim"), snapshotDate = "2026-11-01", license = "CC-BY-SA", hasFulltextIndex = true,
        zimUuid = "u-$id", zimName = zimName, setId = "wikipedia_en", setRank = rank, setLabel = label,
    )

    private fun pack(e: PackEntry, size: Long) = Pack(
        id = e.zimUuid, filePath = "/packs/${e.zimName}.zim", title = e.title, description = "", language = "eng", date = "2026-11-01",
        sizeBytes = size, hasFulltextIndex = true, status = PackStatus.OK, addedAt = 0, name = e.zimName,
    )

    private val top = entry("top", "wikipedia_en_top", 2, "Best 50,000 articles", 2_700_000_000)
    private val top1m = entry("top1m", "wikipedia_en_top1m", 3, "Top 1 million articles", 17_000_000_000)
    private val catalog = Catalog(formatVersion = 1, generatedAt = "2026-11-01T00:00:00Z", packs = listOf(top, top1m))

    @Test fun theSmallerSliceIsOfferedOnceTheBiggerIsInAndKeepIsHeard() {
        assertTrue(SetTidy.offers(catalog, emptyList(), listOf(pack(top, 2_700_000_000)), emptySet()).isEmpty(), "one slice: nothing to tidy")
        val both = listOf(pack(top, 2_700_000_000), pack(top1m, 17_000_000_000))
        val offer = SetTidy.offers(catalog, emptyList(), both, emptySet()).single()
        assertEquals("top>top1m", offer.key)
        assertEquals("u-top", offer.smallPackId)
        assertEquals("u-top1m", offer.bigPackId)
        assertEquals(UiText.Res("sources_tidy_text", "Top 1 million", "Best 50,000", "2.7 GB"), offer.text)
        assertEquals(UiText.Res("sources_tidy_remove", "Best 50,000"), offer.removeLabel)
        assertEquals(UiText.Res("sources_tidy_keep_both"), offer.keepLabel)
        assertTrue(SetTidy.offers(null, emptyList(), both, emptySet()).isEmpty(), "no catalog, no offer")

        val saved = mutableListOf<Set<String>>()
        val dismissals = TidyDismissals(save = { saved += it })
        dismissals.dismiss(offer.key)
        assertEquals(setOf("top>top1m"), saved.last())
        assertTrue(SetTidy.offers(catalog, emptyList(), both, dismissals.keys.value).isEmpty(), "Keep both is remembered")
    }
}
