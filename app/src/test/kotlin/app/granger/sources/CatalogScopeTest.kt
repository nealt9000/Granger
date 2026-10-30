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

    @Test fun modelsAreModels() {
        assertThat(scopeOf(modelEntry("e2b"))).isEqualTo(CatalogScope.MODELS)
    }
}
