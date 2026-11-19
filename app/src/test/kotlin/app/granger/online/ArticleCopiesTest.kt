package app.granger.online

import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.core.library.QidIndexes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import app.granger.online.ArticleCopy.Kind.FULL
import app.granger.online.ArticleCopy.Kind.ONLINE
import app.granger.online.ArticleCopy.Kind.SHORT

/** Plan O1 Tasks 5 and 9: spec §4's "which copy opens", and the availability O2's marks will read. */
class ArticleCopiesTest {
    private val asked = ArticleCopy("mini", "Moon", SHORT)
    private val maxi = ArticleCopy("maxi", "Moon", FULL)
    private val web = ArticleCopy("wikipedia_en_online", "Moon", ONLINE)

    @Test fun theOrderOfSpecSection4() {
        // A kept full copy wins, even online.
        assertThat(ArticleCopies.pick(asked, listOf(asked, maxi, web), online = true)).isEqualTo(maxi)
        assertThat(ArticleCopies.pick(asked, listOf(asked, maxi, web), online = false)).isEqualTo(maxi)
        // Online beats a shorter kept copy...
        assertThat(ArticleCopies.pick(asked, listOf(asked, web), online = true)).isEqualTo(web)
        // ...which Local only (or no signal) always uses.
        assertThat(ArticleCopies.pick(asked, listOf(asked, web), online = false)).isEqualTo(asked)
        // Online only.
        assertThat(ArticleCopies.pick(web, listOf(web), online = true)).isEqualTo(web)
        // Nothing kept, no network: what was asked for; the reader says it can't.
        assertThat(ArticleCopies.pick(web, listOf(web), online = false)).isEqualTo(web)
    }

    private fun pack(id: String, file: String, name: String) =
        Pack(id, "/p/$file", id, "", "eng", "", 1, true, PackStatus.OK, 0, name = name)

    @Test fun maxiIsFullMiniAndNopicAreShorter() {
        assertThat(ArticleCopies.kindOf(pack("a", "wikipedia_en_top_maxi_2026-09.zim", "wikipedia_en_top"))).isEqualTo(FULL)
        assertThat(ArticleCopies.kindOf(pack("b", "wikipedia_en_top_mini_2026-09.zim", "wikipedia_en_top"))).isEqualTo(SHORT)
        assertThat(ArticleCopies.kindOf(pack("c", "wikipedia_en_all_nopic_2026-09.zim", "wikipedia_en_all"))).isEqualTo(SHORT)
        assertThat(ArticleCopies.kindOf(OnlineSources.row("en"))).isEqualTo(ONLINE)
        // Not a Wikipedia: its copy is the whole thing.
        assertThat(ArticleCopies.kindOf(pack("d", "wikem_en_all_2026-09.zim", "wikem_en_all"))).isEqualTo(FULL)
    }

    @Test fun anArticleNoIndexCoversStillFindsItsOnlineCopyByTitle() = runTest {
        val mini = pack("mini", "wikipedia_en_top_mini_2026-09.zim", "wikipedia_en_top")
        var online = true
        val copies = ArticleCopies({ QidIndexes(emptyList()) }, { listOf(mini, OnlineSources.row("en")) }, { online }, { setOf("en") })
        assertThat(copies.choose("mini", "Knowth")).isEqualTo(ArticleCopy("wikipedia_en_online", "Knowth", ONLINE))
        online = false
        assertThat(copies.choose("mini", "Knowth")).isEqualTo(ArticleCopy("mini", "Knowth", SHORT))
        // A language Wikipedia online doesn't read: the pack's copy.
        val noEs = ArticleCopies({ QidIndexes(emptyList()) }, { listOf(mini) }, { true }, { setOf("es") })
        assertThat(noEs.choose("mini", "Knowth").packId).isEqualTo("mini")
    }

    @Test fun availabilityFromTheCopies() = runTest {
        assertThat(Availability.of(listOf(maxi, web))).isEqualTo(Availability.OnPhone(full = true))
        assertThat(Availability.of(listOf(asked, web))).isEqualTo(Availability.OnPhone(full = false))
        assertThat(Availability.of(listOf(web))).isEqualTo(Availability.Online)
        assertThat(Availability.of(emptyList())).isEqualTo(Availability.Nowhere)
        val mini = pack("mini", "wikipedia_en_top_mini_2026-09.zim", "wikipedia_en_top")
        val knowth = ArticleAvailability(ArticleCopies({ QidIndexes(emptyList()) }, { listOf(mini, OnlineSources.row("en")) }, { true }, { setOf("en") }), { listOf(OnlineSources.row("en")) })
        assertThat(knowth.of("wikipedia_en_online", "Knowth")).isEqualTo(Availability.Online)
        val off = ArticleAvailability(ArticleCopies({ QidIndexes(emptyList()) }, { emptyList() }, { false }, { emptySet() }), { emptyList() })
        assertThat(off.of("wikipedia_en_online", "Knowth")).isEqualTo(Availability.Nowhere)
    }
}
