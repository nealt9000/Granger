package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LexicalScoreTest {
    private fun passage(text: String, title: String = "Untitled", heading: String = "", kind: SourceKind = SourceKind.BOOK) =
        Passage(kind, "id", title, heading = heading, text = text)

    @Test fun fractionOfKeywordsPresent() =
        assertThat(LexicalScore.of(passage("Steam drives the piston in a boiler."), listOf("steam", "piston", "gauge", "valve")))
            .isWithin(1e-9).of(0.5)

    @Test fun titleBonus() {
        // A title is only a bonus, for books and articles alike.
        assertThat(LexicalScore.of(passage("nothing here", title = "Steam engine", kind = SourceKind.ARTICLE), listOf("steam"))).isWithin(1e-9).of(0.1)
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
        assertThat(LexicalScore.of(passage("text", title = "Mercy and Portia", kind = SourceKind.ARTICLE), listOf("portia mercy"))).isWithin(1e-9).of(0.1)
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

    @Test fun everyKeywordWordWeighsTheSame() {
        val topic = passage("Alcohol and the Bible.")
        val side = passage("The Bible has a rare verse.")
        val other = passage("Alcohol and wine.")
        val score = LexicalScore.scorer(listOf("alcohol", "bible", "verse"))
        assertThat(score(topic)).isWithin(1e-9).of(2.0 / 3)
        assertThat(score(side)).isWithin(1e-9).of(2.0 / 3)
        assertThat(score(other)).isWithin(1e-9).of(1.0 / 3)
        assertThat(score(topic)).isAtLeast(score(other))
    }

    @Test fun leadPassageGetsTheBonusOnlyWhenItMatches() {
        fun p(heading: String, text: String, kind: SourceKind = SourceKind.ARTICLE) =
            Passage(kind, "s", "Overview", heading = heading, text = text)
        val keywords = listOf("steam", "valve")
        assertThat(LexicalScore.of(p(" overview ", "steam"), keywords)).isWithin(1e-9).of(0.5 + 0.3)
        assertThat(LexicalScore.of(p("", "steam"), keywords)).isWithin(1e-9).of(0.5 + 0.3)
        assertThat(LexicalScore.of(p("Overview", "river"), keywords)).isEqualTo(0.0)
        assertThat(LexicalScore.of(p("History", "steam"), keywords)).isWithin(1e-9).of(0.5)
        assertThat(LexicalScore.of(p("", "steam", SourceKind.BOOK), keywords)).isWithin(1e-9).of(0.5)
    }

    @Test fun noKeywordsScoresZero() = assertThat(LexicalScore.of(passage("text"), emptyList())).isEqualTo(0.0)

    @Test fun bookTitleIsOnlyABonus() {
        // tune-4: crediting a title as a keyword let "sister" pass every passage of Sister Carrie.
        val kjv = passage("For the love of money is the root of all evil.", title = "The King James Bible")
        assertThat(LexicalScore.of(kjv, listOf("bible", "money", "evil"))).isWithin(1e-9).of(2.0 / 3 + 0.1)
        val carrie = passage("She walked to the station.", title = "Sister Carrie")
        assertThat(LexicalScore.of(carrie, listOf("sister", "phone number"))).isWithin(1e-9).of(0.1)
    }

    @Test fun articleTitleDoesNotCountAsBody() {
        val article = passage("For the love of money is the root of all evil.", title = "Bible", kind = SourceKind.ARTICLE)
        // 2/3, TITLE_BONUS for "bible" in the title, LEAD_BONUS for the opening passage.
        assertThat(LexicalScore.of(article, listOf("bible", "money", "evil"))).isWithin(1e-9).of(2.0 / 3 + 0.1 + 0.3)
    }

    @Test fun titleBonusHonoursAliases() {
        val koran = passage("Charity is a duty.", title = "The Koran")
        assertThat(LexicalScore.of(koran, listOf("quran", "charity"))).isWithin(1e-9).of(0.5 + 0.1)
    }

    @Test fun aKeywordCountsForItsBestAlternative() {
        val kjv = passage("Wine is a mocker, strong drink is raging.", title = "The King James Bible")
        // "alcohol" alone isn't there; its alternates are. Each keyword still weighs one: (1 + 0) / 2.
        assertThat(LexicalScore.of(kjv, listOf("alcohol|strong drink|wine", "tomorrow"))).isWithin(1e-9).of(0.5)
        assertThat(LexicalScore.of(kjv, listOf("alcohol", "tomorrow"))).isEqualTo(0.0)
        // Half of "strong spirits" is the best of the group when nothing else matches.
        assertThat(LexicalScore.of(passage("strong winds"), listOf("alcohol|strong spirits"))).isWithin(1e-9).of(0.5)
    }

    @Test fun anAlternateInTheTitleGetsTheBonus() =
        assertThat(LexicalScore.of(passage("nothing here", title = "Wine"), listOf("alcohol|wine"))).isWithin(1e-9).of(0.1)
}
