package app.granger.online

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Plan O3 Task 8: Home's Ready offline from your recent reads. */
class ReadyOfflineTest {
    private fun read(path: String, a: Availability, subject: String?, inPack: Boolean = false, pack: String = "wikipedia_en_online") =
        RecentRead(pack, path, a, subject, inPack)

    @Test fun totalsBarsAndWhatWouldFillTheGaps() {
        val reads = listOf(
            read("Newgrange", Availability.OnPhone(true), "history"),
            read("Dowth", Availability.Saved, "history"),
            read("Knowth", Availability.Online, "history", inPack = true),
            read("Moon", Availability.Online, "science", inPack = true),
            read("Obscure", Availability.Online, null),
        )
        val top = PackOffer("wikipedia_en_top_mini", "Wikipedia Top", 2_700_000_000)
        val ui = ReadyOffline.of(reads, each = 1_000, pack = top)
        assertThat(ui.recent).isEqualTo(5)
        assertThat(ui.offline).isEqualTo(2)
        assertThat(ui.subjects.first()).isEqualTo(SubjectBar("history", onPhone = 1, saved = 1, online = 1))
        assertThat(ui.subjects.map { it.subjectId }).containsExactly("history", "science").inOrder()
        assertThat(ui.keepTheRead).containsExactly("en" to "Knowth", "en" to "Moon", "en" to "Obscure")
        assertThat(ui.keepBytes).isEqualTo(3_000)
        assertThat(ui.pack).isEqualTo(top)
        assertThat(ui.packCovers).isEqualTo(2)
    }

    @Test fun noPackWhenItCoversNothingAndNothingToKeepWhenAllIsOffline() {
        val ui = ReadyOffline.of(listOf(read("Newgrange", Availability.OnPhone(true), "history")), 1_000, PackOffer("p", "P", 1))
        assertThat(ui.pack).isNull()
        assertThat(ui.keepTheRead).isEmpty()
        assertThat(ui.offline).isEqualTo(1)
    }
}
