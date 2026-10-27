package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EntitiesTest {
    @Test fun dateQuestionsAreCued() {
        assertThat(Entities.asked("How old was Mary Shelley when Frankenstein came out?")).isTrue()
        assertThat(Entities.asked("Could Jane Austen have read Frankenstein?")).isTrue()
        assertThat(Entities.asked("Was Lincoln already president when On the Origin of Species was published?")).isTrue()
        assertThat(Entities.asked("Did Darwin live to see Mendeleev's periodic table?")).isTrue()
        assertThat(Entities.asked("Who wrote Frankenstein?")).isFalse()
        assertThat(Entities.asked("What is the boiling point of water?")).isFalse()
    }

    @Test fun namesAreRunsOfCapitals() {
        assertThat(Entities.named("How old was Mary Shelley when Frankenstein came out?", emptyList()))
            .containsExactly("mary shelley", "frankenstein").inOrder()
        assertThat(Entities.named("Was Lincoln already president when On the Origin of Species was published?", emptyList()))
            .containsExactly("lincoln", "on the origin of species").inOrder()
        assertThat(Entities.named("Who was older, Charles Darwin or Abraham Lincoln?", emptyList()))
            .containsExactly("charles darwin", "abraham lincoln").inOrder()
        assertThat(Entities.named("Did Darwin live to see Mendeleev's periodic table?", emptyList()))
            .containsExactly("darwin", "mendeleev").inOrder()
        assertThat(Entities.named("Was Fermat's Last Theorem proved before or after the Berlin Wall fell?", emptyList()))
            .containsExactly("fermat's last theorem", "berlin wall").inOrder()
    }

    @Test fun aLowerCaseQuestionFallsBackOnTheKeywords() {
        assertThat(Entities.named("how old was mary shelley when frankenstein came out", listOf("mary shelley", "frankenstein")))
            .containsExactly("mary shelley", "frankenstein").inOrder()
    }

    @Test fun titlesMatchBestWhenExact() {
        assertThat(Entities.match("Frankenstein", "frankenstein")).isEqualTo(3)
        assertThat(Entities.match("Dmitri Mendeleev", "mendeleev")).isEqualTo(2)
        assertThat(Entities.match("United States Declaration of Independence", "declaration of independence")).isEqualTo(2)
        assertThat(Entities.match("Frankenstein (1931 film)", "frankenstein")).isEqualTo(1)
        assertThat(Entities.match("Lincoln, Nebraska", "lincoln")).isEqualTo(0)
        assertThat(Entities.match("Full moon", "moon")).isEqualTo(0)
        assertThat(Entities.match("Council of Europe", "europe")).isEqualTo(0)
        assertThat(Entities.match("Life of William Shakespeare", "shakespeare")).isEqualTo(0)
    }

    private fun p(title: String, heading: String = title, text: String = "$title / $heading") =
        Passage(SourceKind.ARTICLE, "wiki", title, location = title.replace(' ', '_'), heading = heading, text = text)

    @Test fun eachNameGetsItsArticlesOpeningPassage() {
        val shelley = p("Mary Shelley")
        val shelley2 = p("Mary Shelley", "Later life")
        val film = p("Frankenstein (1931 film)")
        val other = p("Gothic fiction")
        val other2 = p("Percy Bysshe Shelley")
        val novelLater = p("Frankenstein", "Reception")
        val novel = p("Frankenstein")
        val ranked = listOf(shelley, shelley2, film, other, other2)
        val out = Entities.reserve(ranked, pool = ranked + listOf(novelLater, novel), names = listOf("mary shelley", "frankenstein"), keep = 4)
        assertThat(out.take(4)).containsExactly(shelley, shelley2, film, novel).inOrder()
        assertThat(out.drop(4)).containsExactly(other, other2).inOrder()
    }

    @Test fun aNameAlreadyInTheTopKeepsItsPlaceAndNothingMoves() {
        val ranked = listOf(p("Charles Darwin", "Voyage"), p("Abraham Lincoln"), p("X"), p("Y"), p("Charles Darwin"))
        assertThat(Entities.reserve(ranked, ranked, listOf("charles darwin", "abraham lincoln"), keep = 4)).isEqualTo(ranked)
    }

    @Test fun anEmptyRankingStaysADecline() {
        assertThat(Entities.reserve(emptyList(), listOf(p("Magna Carta")), listOf("magna carta"), keep = 4)).isEmpty()
    }

    @Test fun aShortRankingGainsTheNamedPassage() {
        val ranked = listOf(p("Black Death"))
        assertThat(Entities.reserve(ranked, listOf(p("Magna Carta")), listOf("magna carta", "black death"), keep = 4))
            .containsExactly(p("Black Death"), p("Magna Carta")).inOrder()
    }
}
