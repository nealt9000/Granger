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

    @Test fun modelsAreModels() {
        assertThat(scopeOf(modelEntry("e2b"))).isEqualTo(CatalogScope.MODELS)
    }
}
