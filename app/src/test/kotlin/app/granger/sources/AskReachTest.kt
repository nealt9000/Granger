package app.granger.sources

import app.granger.core.catalog.VectorsEntry
import app.granger.packEntry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AskReachTest {
    private val withVectors = packEntry("wiki").copy(vectors = VectorsEntry(url = "https://a.test/wiki.gvec"))

    @Test fun vectorsInTheCatalogOrOnThePhoneSearchByMeaning() {
        assertThat(askReach(withVectors, emptySet())).isEqualTo(AskReach.MEANING)
        assertThat(askReach(packEntry("se"), setOf("se"))).isEqualTo(AskReach.MEANING)
        assertThat(AskReach.MEANING.text).isEqualTo(app.granger.UiText.Res(app.granger.R.string.sources_ask_meaning))
    }

    @Test fun aFullTextIndexAloneIsKeywordsOnly() {
        assertThat(askReach(packEntry("se"), setOf("other"))).isEqualTo(AskReach.KEYWORDS)
        assertThat(AskReach.KEYWORDS.text).isEqualTo(app.granger.UiText.Res(app.granger.R.string.sources_ask_keywords))
    }

    @Test fun neitherMeansAskCantSearchIt() {
        assertThat(askReach(packEntry("bare").copy(hasFulltextIndex = false), emptySet())).isEqualTo(AskReach.NONE)
        assertThat(AskReach.NONE.text).isEqualTo(app.granger.UiText.Res(app.granger.R.string.sources_ask_none))
    }

    @Test fun mapsSayNothing() {
        assertThat(askReach(packEntry("osm").copy(family = "maps", hasFulltextIndex = false), emptySet())).isNull()
        assertThat(askReach(packEntry("osm").copy(family = "maps"), setOf("osm"))).isNull()
    }
}
