package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CitationsTest {
    @Test
    fun validCitations() {
        assertThat(Citations.check("Steam expands [1] and cools [2, 3].", 4)).isEqualTo(CitationCheck.Valid(setOf(1, 2, 3)))
    }

    @Test
    fun outOfRangeCitationIsInvalid() {
        assertThat(Citations.check("It does [7].", 4)).isEqualTo(CitationCheck.Invalid)
    }

    @Test
    fun zeroCitationIsInvalid() {
        assertThat(Citations.check("It does [0].", 4)).isEqualTo(CitationCheck.Invalid)
    }

    @Test
    fun overflowingCitationIsInvalid() {
        assertThat(Citations.check("It does [99999999999].", 4)).isEqualTo(CitationCheck.Invalid)
    }

    @Test
    fun noCitationIsInvalid() {
        assertThat(Citations.check("It does.", 4)).isEqualTo(CitationCheck.Invalid)
    }

    @Test
    fun declineToken() {
        assertThat(Citations.check("  NOT IN LIBRARY", 4)).isEqualTo(CitationCheck.ModelDeclined)
    }

    @Test
    fun declineTokenAfterACitedSentenceStillDeclines() {
        // E4B on the Pixel 6: it quoted the passage, then declined.
        assertThat(Citations.check("The passage states: \"caviare to the general\" [1]\n\nNOT IN LIBRARY", 1))
            .isEqualTo(CitationCheck.ModelDeclined)
    }

    @Test
    fun proseDeclineIsADecline() {
        val a = "The provided passages do not contain information about your chances [1, 2]. However, odds vary."
        assertThat(Citations.check(a, 2)).isEqualTo(CitationCheck.ModelDeclined)
    }

    @Test
    fun passagesDontSayButAnswerIsAnAnswer() {
        assertThat(Citations.check("The passages do not give the exact day, but he died in 1616 [2].", 2))
            .isEqualTo(CitationCheck.Valid(setOf(2)))
    }

    @Test
    fun noInformationPhrasingDeclines() {
        assertThat(Citations.check("There is no information about this in the passages.", 2)).isEqualTo(CitationCheck.ModelDeclined)
    }

    @Test
    fun answerMentioningPassagesLaterIsAnAnswer() {
        assertThat(Citations.check("Paul wrote Romans [1]. The passages do not say when.", 2))
            .isEqualTo(CitationCheck.Valid(setOf(1)))
    }

    @Test
    fun decimalDoesNotEndTheFirstSentence() {
        assertThat(Citations.check("Everest is 8,848.86 m tall [1]. The passages do not say who measured it.", 1))
            .isEqualTo(CitationCheck.Valid(setOf(1)))
    }

    @Test
    fun leadingCitationIsSkipped() {
        assertThat(Citations.check("[1] The passages do not mention Ishmael.", 1)).isEqualTo(CitationCheck.ModelDeclined)
    }

    @Test
    fun sentenceEndingInANumberEnds() {
        assertThat(Citations.check("He died in 1616 [1]. The passages do not give the day.", 1)).isEqualTo(CitationCheck.Valid(setOf(1)))
        assertThat(Citations.check("Apollo 11 landed in 1969 [1]. The passages do not say more.", 1)).isEqualTo(CitationCheck.Valid(setOf(1)))
        assertThat(Citations.check("He died in 1616. The passages do not give the day. [1]", 1)).isEqualTo(CitationCheck.Valid(setOf(1)))
    }

    @Test
    fun singleCapitalEndsASentence() {
        assertThat(Citations.check("The answer is vitamin C [1]. The passages do not say more.", 1)).isEqualTo(CitationCheck.Valid(setOf(1)))
        assertThat(Citations.check("The answer is Vitamin C. The passages do not say more. [1]", 1)).isEqualTo(CitationCheck.Valid(setOf(1)))
    }

    @Test
    fun honorificDoesNotEndTheSentence() {
        assertThat(Citations.check("The passages do not mention St. Paul, but Paul wrote Romans [1].", 1))
            .isEqualTo(CitationCheck.Valid(setOf(1)))
    }

    @Test
    fun semicolonAndHoweverKeepTheAnswer() {
        for (a in listOf(
            "The passages do not say; he died in 1616 [1].",
            "The passages do not say. However, he died in 1616 [1].".replace(". However", ", however"),
            "The passages do not say, though he died in 1616 [1].",
            "The passages do not say the day, while he died in 1616 [1].",
            "The passages do not say the day, yet he died in 1616 [1].",
            "The passages do not say: he died in 1616 [1].",
            "The passages do not say, although he died in 1616 [1].",
        )) assertThat(Citations.check(a, 1)).isEqualTo(CitationCheck.Valid(setOf(1)))
    }

    @Test
    fun curlyApostropheDeclines() {
        assertThat(Citations.check("The passages don\u2019t mention Ishmael.", 1)).isEqualTo(CitationCheck.ModelDeclined)
    }

    @Test
    fun leadingNewlineDeclines() {
        assertThat(Citations.check("\nThe passages do not mention Ishmael.", 1)).isEqualTo(CitationCheck.ModelDeclined)
    }

    @Test
    fun stripRemovesGroups() {
        assertThat(Citations.strip("A [1] b [2, 3].")).isEqualTo("A b.")
    }

    @Test
    fun stripKeepsNewlines() {
        assertThat(Citations.strip("One.\n\n[1] Two [2].")).contains("\n\n")
    }
}
