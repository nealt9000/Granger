package app.granger.sources

import app.granger.modelEntry
import app.granger.packEntry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CatalogScopeTest {
    @Test fun kiwixHostIsKiwix() {
        val mirrors = listOf("https://example.test/a.zim", "https://download.kiwix.org/zim/a.zim")
        assertThat(scopeOf(packEntry("a", urls = mirrors))).isEqualTo(CatalogScope.KIWIX)
        assertThat(scopeOf(packEntry("b", urls = listOf("https://kiwix.org/a.zim")))).isEqualTo(CatalogScope.KIWIX)
    }

    @Test fun githubReleaseIsGranger() {
        val release = listOf("https://github.com/nealt9000/Granger/releases/download/v1/classics.zim")
        assertThat(scopeOf(packEntry("classics", urls = release))).isEqualTo(CatalogScope.GRANGER)
        assertThat(scopeOf(packEntry("lookalike", urls = listOf("https://notkiwix.org.evil.test/a.zim")))).isEqualTo(CatalogScope.GRANGER)
    }

    @Test fun gutenbergPacksAreGutenbergsNotKiwixs() {
        val kiwix = listOf("https://download.kiwix.org/zim/gutenberg/gutenberg_en_lcc-m_2026-03.zim")
        // By family, and failing that by ZIM name (Plan GB Decision 1).
        assertThat(scopeOf(packEntry("gutenberg_en_lcc-m", urls = kiwix).copy(family = "gutenberg"))).isEqualTo(CatalogScope.GUTENBERG)
        assertThat(scopeOf(packEntry("m", urls = kiwix, zimName = "gutenberg_en_lcc-m"))).isEqualTo(CatalogScope.GUTENBERG)
        assertThat(scopeOf(packEntry("w", urls = kiwix, zimName = "wikisource_en_all"))).isEqualTo(CatalogScope.KIWIX)
    }

    /** Plan KB Decision 6: the other book packs are offered under Books, as Kiwix libraries. */
    @Test fun bookPacksAreKiwixLibrariesAndGutenbergStaysGutenbergs() {
        val kiwix = listOf("https://download.kiwix.org/zim/other/zimgit-water_en_2024-08.zim")
        val water = packEntry("zimgit-water_en", urls = kiwix, zimName = "zimgit-water_en").copy(content = "books")
        assertThat(scopeOf(water)).isEqualTo(CatalogScope.KIWIX_BOOKS)
        val gutenberg = packEntry("gutenberg_en_lcc-m", urls = kiwix, zimName = "gutenberg_en_lcc-m").copy(family = "gutenberg", content = "books")
        assertThat(scopeOf(gutenberg)).isEqualTo(CatalogScope.GUTENBERG)
        // An older catalog says nothing about content: the pack is Kiwix's, as before.
        assertThat(scopeOf(water.copy(content = ""))).isEqualTo(CatalogScope.KIWIX)
        assertThat(PACK_SCOPES).containsExactly(CatalogScope.KIWIX, CatalogScope.KIWIX_BOOKS, CatalogScope.GUTENBERG)
    }

    /** Plan KB2: a pack with a book manifest leaves the Kiwix web list for Kiwix libraries, though its content is blank. */
    @Test fun aPackWithABookManifestIsAKiwixLibrary() {
        val kiwix = listOf("https://download.kiwix.org/zim/zimit/irp.fas.org_en_military-medicine_2026-10.zim")
        val site = packEntry("irp.fas.org_en_military-medicine", urls = kiwix, zimName = "irp.fas.org_en_military-medicine")
        assertThat(scopeOf(site)).isEqualTo(CatalogScope.KIWIX)
        val gbooks = app.granger.core.catalog.SidecarEntry("gbooks", "https://github.com/x/releases/download/bookpacks-1/mm.gbooks", "8".repeat(64), 40_000, 1)
        val withManifest = site.copy(sidecars = listOf(gbooks))
        assertThat(withManifest.content).isEmpty()
        assertThat(scopeOf(withManifest)).isEqualTo(CatalogScope.KIWIX_BOOKS)
        // Another sidecar doesn't make it books.
        assertThat(scopeOf(site.copy(sidecars = listOf(gbooks.copy(kind = "gqid"))))).isEqualTo(CatalogScope.KIWIX)
    }

    @Test fun modelsAreModels() {
        assertThat(scopeOf(modelEntry("e2b"))).isEqualTo(CatalogScope.MODELS)
    }
}
