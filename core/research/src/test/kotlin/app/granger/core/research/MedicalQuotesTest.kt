package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MedicalQuotesTest {
    private fun passage(text: String, source: String = "S") = Passage(SourceKind.ARTICLE, source, "Title", text = text)

    @Test fun eachCitedPassageGivesTheSentenceItsClaimUsed() {
        val a = passage("Fever is common in children. Paracetamol lowers a fever in children over two months old. See a doctor.")
        val b = passage("Dehydration is a risk with a high fever. Give small sips of fluid often.")
        val reply = "Paracetamol can lower a child's fever [1]. Offer small sips of fluid often [2]."
        assertThat(MedicalQuotes.build(reply, listOf(a, b), setOf(1, 2))).isEqualTo(
            "“Paracetamol lowers a fever in children over two months old.” [1]\n\n" +
                "“Give small sips of fluid often.” [2]",
        )
    }

    @Test fun uncitedPassagesAreLeftOutAndRepeatsQuotedOnce() {
        val a = passage("Ice reduces swelling after a sprain.")
        val b = passage("Ice reduces swelling after a sprain.", source = "T")
        val c = passage("The moon orbits the earth.")
        val text = MedicalQuotes.build("Ice reduces swelling [1][2].", listOf(a, b, c), setOf(1, 2))
        assertThat(text).isEqualTo("“Ice reduces swelling after a sprain.” [1]")
    }

    @Test fun isQuotedKnowsItsOwnFormat() {
        assertThat(MedicalQuotes.isQuoted("“A.” [1]\n\n“B.” [2]")).isTrue()
        assertThat(MedicalQuotes.isQuoted("A normal heart rate is 60 to 100 [1].")).isFalse()
        assertThat(MedicalQuotes.isQuoted("")).isFalse()
    }
}
