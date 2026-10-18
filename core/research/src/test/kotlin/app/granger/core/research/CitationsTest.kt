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
    fun stripRemovesGroups() {
        assertThat(Citations.strip("A [1] b [2, 3].")).isEqualTo("A b.")
    }

    @Test
    fun stripKeepsNewlines() {
        assertThat(Citations.strip("One.\n\n[1] Two [2].")).contains("\n\n")
    }
}
