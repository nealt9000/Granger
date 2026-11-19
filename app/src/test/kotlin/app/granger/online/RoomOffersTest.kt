package app.granger.online

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Plan O2 Task 6: what "Keep this room" offers. */
class RoomOffersTest {
    private val ranked = listOf("wikipedia_en_online" to "Nile", "wikipedia_en_top_maxi" to "Amazon_River", "wikipedia_en_online" to "Danube")
    private val packs = listOf(
        Triple("nhs_medicines", "NHS Medicines A to Z", 15_000_000L) to "health",
        Triple("wikipedia_en_medicine_maxi", "WikiMed", 2_000_000_000L) to "health",
        Triple("wikipedia_en_medicine_mini", "WikiMed (small)", 400_000_000L) to "health",
        Triple("cooking", "Cooking", 90_000_000L) to "food",
    )

    @Test fun onlineArticlesNotYetKeptWithTheirSize() {
        val o = RoomOffers.build(ranked, isKept = { _, t -> t == "Danube" }, keepsPictures = true, packs = emptyList(), subjectId = "places")!!
        // The pack's article is on the phone already; Danube is kept.
        assertThat(o.articles).containsExactly("en" to "Nile")
        assertThat(o.estimateBytes).isEqualTo(RoomOffers.EACH_WITH_PICTURES)
        assertThat(o.pack).isNull()
        assertThat(RoomOffers.build(ranked, { _, _ -> false }, keepsPictures = false, emptyList(), "places")!!.estimateBytes).isEqualTo(2 * RoomOffers.EACH_TEXT)
    }

    @Test fun aWikipediaSubjectPackIsOfferedFirstTheSmallestOfThem() {
        val o = RoomOffers.build(emptyList(), { _, _ -> false }, true, packs, "health")!!
        // Not the smallest pack on the subject (NHS medicines): WikiMed covers a room.
        assertThat(o.pack).isEqualTo(PackOffer("wikipedia_en_medicine_mini", "WikiMed (small)", 400_000_000L))
        assertThat(RoomOffers.build(emptyList(), { _, _ -> false }, true, packs, "food")!!.pack!!.id).isEqualTo("cooking")
        assertThat(o.articles).isEmpty()
    }

    @Test fun nothingToOfferIsNull() {
        assertThat(RoomOffers.build(listOf("wikipedia_en_top_maxi" to "Nile"), { _, _ -> false }, true, packs, "places")).isNull()
    }
}
