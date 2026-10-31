package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CitedQuoteEsTest {
    private val es = listOf(ResearchLanguage.ES)
    private val rayuela =
        "¿Quién escribió Rayuela? Julio Cortázar la publicó en Buenos Aires en 1963. ¡Fue un éxito inmediato! " +
            "La novela puede leerse en más de un orden, según el tablero de dirección."

    @Test fun spanishSentencesSplitAtQuestionAndExclamationMarks() {
        val sentences = CitedQuote.sentences(rayuela, ResearchLanguage.ES).map { rayuela.substring(it) }
        assertThat(sentences).containsExactly(
            "¿Quién escribió Rayuela?", "Julio Cortázar la publicó en Buenos Aires en 1963.", "¡Fue un éxito inmediato!",
            "La novela puede leerse en más de un orden, según el tablero de dirección.",
        ).inOrder()
    }

    @Test fun picksTheSpanishSentenceTheAnswerUsed() {
        val answer = "Cortázar publicó Rayuela en Buenos Aires en 1963 [1]. La novela se lee en más de un orden [2]."
        assertThat(CitedQuote.pick(answer, 1, rayuela, es)!!.sentence).isEqualTo("Julio Cortázar la publicó en Buenos Aires en 1963.")
        assertThat(CitedQuote.pick(answer, 2, rayuela, es)!!.sentence).startsWith("La novela puede leerse")
    }

    @Test fun thePassagesLanguageBreaksItsSentences() {
        // A Spanish question over an English passage: the passage breaks as English, stopwords of both drop out.
        val text = "Cortázar published Hopscotch in 1963. It can be read in more than one order."
        val quote = CitedQuote.pick("Rayuela se publicó en 1963 [1].", 1, text, es, passageLanguage = ResearchLanguage.EN)
        assertThat(quote!!.sentence).isEqualTo("Cortázar published Hopscotch in 1963.")
    }

    @Test fun spanishStopwordsArentContentWords() {
        assertThat(CitedQuote.contentWords("los que vivían en la casa", es)).containsExactly("vivian", "casa")
        assertThat(CitedQuote.contentWords("los que vivían en la casa")).contains("los")
    }
}
