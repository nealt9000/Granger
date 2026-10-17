package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LexicalScoreTest {
    private fun passage(text: String, title: String = "Untitled", heading: String = "") =
        Passage(SourceKind.ARTICLE, "id", title, heading = heading, text = text)

    @Test fun fractionOfKeywordsPresent() =
        assertThat(LexicalScore.of(passage("Steam drives the piston in a boiler."), listOf("steam", "piston", "gauge", "valve")))
            .isWithin(1e-9).of(0.5)

    @Test fun titleBonus() {
        assertThat(LexicalScore.of(passage("nothing here", title = "Steam engine"), listOf("steam"))).isWithin(1e-9).of(0.1)
        assertThat(LexicalScore.of(passage("steam power", title = "Steam engine"), listOf("steam"))).isWithin(1e-9).of(1.1)
    }

    @Test fun wholeWordsOnly() =
        assertThat(LexicalScore.of(passage("Please concatenate the strings."), listOf("cat"))).isEqualTo(0.0)

    @Test fun headingCountsAndPhrasesMatch() =
        assertThat(LexicalScore.of(passage("text", heading = "Boiler Pressure"), listOf("boiler pressure"))).isWithin(1e-9).of(1.0)

    @Test fun noKeywordsScoresZero() = assertThat(LexicalScore.of(passage("text"), emptyList())).isEqualTo(0.0)
}
