package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RewriteTest {
    @Test fun parsesBothLines() = assertThat(Rewrite.parse("keywords: steam engine, boiler pressure\nmedical: no"))
        .isEqualTo(RewriteResult(listOf("steam engine", "boiler pressure"), medical = false))

    @Test fun medicalYes() = assertThat(Rewrite.parse("keywords: burn, first aid\nmedical: yes")!!.medical).isTrue()

    @Test fun missingMedicalLineIsUnknown() =
        assertThat(Rewrite.parse("keywords: burn treatment, first aid for burns, burn care")!!.medical).isNull()

    @Test fun noKeywordsIsNull() = assertThat(Rewrite.parse("I'm sorry, I can't help")).isNull()

    @Test fun toleratesCaseAndExtraText() {
        assertThat(Rewrite.parse("Keywords: Darwin, finches\nMedical: No\n")!!.keywords).containsExactly("Darwin", "finches")
    }

    @Test fun promptCarriesThePreviousQuestion() =
        assertThat(Rewrite.prompt("and his finches?", "who was Darwin")).contains("Previous question: who was Darwin")

    @Test fun promptWithoutPreviousQuestion() {
        val p = Rewrite.prompt("who was Darwin", null)
        assertThat(p).doesNotContain("Previous question")
        assertThat(p).isEqualTo(
            "Turn the question into search keywords for a library catalogue.\n" +
                "Reply in exactly two lines:\n" +
                "keywords: <3 to 6 keywords or short phrases, comma-separated>\n" +
                "medical: <yes or no>\n" +
                "Question: who was Darwin",
        )
    }

    @Test fun capsAtSixKeywords() {
        val r = Rewrite.parse("keywords: a1, b2, c3, d4, e5, f6, g7, h8\nmedical: no")!!
        assertThat(r.keywords).containsExactly("a1", "b2", "c3", "d4", "e5", "f6").inOrder()
    }

    @Test fun anEchoedPlaceholderIsNotKeywords() {
        assertThat(Rewrite.parse("keywords: <3 to 6 keywords or short phrases, comma-separated>\nmedical: no")).isNull()
    }
}
