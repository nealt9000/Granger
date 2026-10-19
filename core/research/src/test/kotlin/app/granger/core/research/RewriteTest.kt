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
            "Turn the question into search words for finding the text that answers it in a library.\n" +
                "Use the words that text itself would contain: names of people, places, works and things, and the source's own wording (older or formal words too).\n" +
                "Don't use words about the question, like definition, meaning, history, facts, problems, references, etymology.\n" +
                "For what a word means or where it comes from, give the word itself.\n" +
                "Reply in exactly two lines:\n" +
                "keywords: <3 to 6 words or short names, comma-separated>\n" +
                "medical: <yes if the question is about health, illness, injury, the body, medicines or treatment; otherwise no>\n" +
                "Example:\n" +
                "Question: how did old sailors work out how far east or west they were\n" +
                "keywords: longitude, marine chronometer, lunar distance, navigation\n" +
                "medical: no\n" +
                "Question: who was Darwin",
        )
    }

    @Test fun capsAtSixKeywords() {
        val r = Rewrite.parse("keywords: a1, b2, c3, d4, e5, f6, g7, h8\nmedical: no")!!
        assertThat(r.keywords).containsExactly("a1", "b2", "c3", "d4", "e5", "f6").inOrder()
    }

    @Test fun anEchoedPlaceholderIsNotKeywords() {
        assertThat(Rewrite.parse("keywords: <3 to 6 words or short names, comma-separated>\nmedical: no")).isNull()
    }

    @Test fun echoedMedicalPlaceholderIsNotYes() {
        val r = Rewrite.parse("keywords: burn, first aid\nmedical: <yes if the question is about health; otherwise no>")!!
        assertThat(r.medical).isNull()
    }

    @Test fun readsMedicalYesWithNewPromptWording() =
        assertThat(Rewrite.parse("keywords: stroke, signs\nmedical: yes")!!.medical).isTrue()

    @Test fun promptAsksForSourceWording() {
        val p = Rewrite.prompt("q", null)
        assertThat(p).contains("Use the words that text itself would contain")
        assertThat(p).contains("Don't use words about the question")
    }

    @Test fun promptHasAnExample() {
        val p = Rewrite.prompt("who was Darwin", null)
        assertThat(p).contains("Question: how did old sailors work out how far east or west they were")
        assertThat(p).contains("keywords: longitude, marine chronometer, lunar distance, navigation")
        assertThat(p.lines().last()).isEqualTo("Question: who was Darwin")
    }

    @Test fun metaWordsAreDropped() {
        assertThat(Rewrite.parse("keywords: betimes definition, betimes meaning, word origin")!!.keywords)
            .containsExactly("betimes", "word").inOrder()
    }

    @Test fun allMetaKeepsOriginal() {
        assertThat(Rewrite.parse("keywords: history, facts")!!.keywords).containsExactly("history", "facts").inOrder()
    }

    @Test fun keywordWithMetaInsideKeepsTheRest() {
        assertThat(Rewrite.parse("keywords: granny knot problems")!!.keywords).containsExactly("granny knot")
    }

    @Test fun rewriteDropsSourceMetaWords() {
        assertThat(Rewrite.parse("keywords: bible, money, evil, scripture")!!.keywords).containsExactly("bible", "money", "evil").inOrder()
        assertThat(Rewrite.parse("keywords: hamlet quotes, verse, passages")!!.keywords).containsExactly("hamlet", "verse").inOrder()
    }
}
