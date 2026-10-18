package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import kotlin.math.ln
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

    @Test fun aPhraseCountsWhenAllItsWordsArePresent() {
        val portia = passage("The quality of mercy is not strained, said Portia.")
        assertThat(LexicalScore.of(portia, listOf("portia mercy", "quality mercy"))).isWithin(1e-9).of(1.0)
        assertThat(LexicalScore.of(passage("text", title = "Mercy and Portia"), listOf("portia mercy"))).isWithin(1e-9).of(0.1)
    }

    @Test fun aPhraseGetsTheShareOfItsWordsPresent() {
        val portia = passage("The quality of mercy is not strained, said Portia.")
        assertThat(LexicalScore.of(portia, listOf("portia quote"))).isWithin(1e-9).of(0.5)
        // (1 + 0.5 + 0) / 3
        assertThat(LexicalScore.of(portia, listOf("portia mercy", "portia quote", "literature analysis"))).isWithin(1e-9).of(0.5)
    }

    @Test fun scorerMatchesOf() {
        val keywords = listOf("steam", "boiler pressure", "valve")
        val score = LexicalScore.scorer(keywords)
        listOf(passage("Steam in the boiler raises its pressure."), passage("valve", title = "Steam"), passage("nothing")).forEach {
            assertThat(score(it)).isEqualTo(LexicalScore.of(it, keywords))
        }
        assertThat(LexicalScore.scorer(emptyList())(passage("steam"))).isEqualTo(0.0)
    }

    @Test fun stopwordsInAKeywordAreIgnored() =
        assertThat(LexicalScore.of(passage("Open the doors."), listOf("the library", "doors"))).isWithin(1e-9).of(0.5)

    @Test fun overACorpusRarerWordsCountMore() {
        val hours = passage("Library access hours.")
        val wifi = passage("The wifi password is on the desk.")
        val corpus = listOf(hours, wifi, passage("Library rules."), passage("Library cards."))
        val keywords = listOf("wifi password", "library access")
        // Smoothed idf over the 4 passages: ln((4 + 1) / (df + 1)) + 1; library is in 3 of them, the rest in 1.
        val rare = ln(5.0 / 2) + 1
        val library = ln(5.0 / 4) + 1
        val total = 3 * rare + library
        val score = LexicalScore.scorer(keywords, corpus)
        assertThat(score(hours)).isWithin(1e-9).of((library + rare) / total)
        assertThat(score(wifi)).isWithin(1e-9).of(2 * rare / total)
        // Without a corpus each keyword counts equally: both score 0.5.
        assertThat(LexicalScore.of(hours, keywords)).isWithin(1e-9).of(0.5)
    }

    @Test fun noKeywordsScoresZero() = assertThat(LexicalScore.of(passage("text"), emptyList())).isEqualTo(0.0)
}
