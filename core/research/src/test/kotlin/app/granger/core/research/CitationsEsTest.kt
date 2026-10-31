package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Spanish declines in prose, in case the model answers a Spanish question in Spanish. English ones still count. */
class CitationsEsTest {
    private val es = listOf(ResearchLanguage.ES)

    @Test fun spanishDeclinesAreDeclines() {
        listOf(
            "Los pasajes no mencionan la fecha de su boda.",
            "No se menciona en los pasajes cuándo murió.",
            "No se especifica el año.",
            "No hay información sobre su comida favorita.",
            "No encuentro ese dato en la biblioteca.",
            "No pude encontrar la respuesta.",
            "No se indica cuántos habitantes tiene.",
            "La biblioteca no dice nada sobre eso.",
            "The passages do not say when he died.",
        ).forEach { assertThat(Citations.check(it, 3, es)).isEqualTo(CitationCheck.ModelDeclined) }
    }

    @Test fun spanishDeclinesNeedASpanishQuestion() {
        assertThat(Citations.check("Los pasajes no mencionan la fecha.", 3)).isEqualTo(CitationCheck.Invalid)
    }

    @Test fun aDeclineThatGoesOnToAnswerIsAnAnswer() {
        assertThat(Citations.check("No se especifica el día, pero fue en agosto de 1819 [2].", 3, es)).isEqualTo(CitationCheck.Valid(setOf(2)))
        assertThat(Citations.check("No se menciona la hora; sin embargo, fue al amanecer [1].", 3, es)).isEqualTo(CitationCheck.Valid(setOf(1)))
        assertThat(Citations.check("No hay información precisa, aunque se cree que nació en 1648 [3].", 3, es)).isEqualTo(CitationCheck.Valid(setOf(3)))
    }

    @Test fun spanishAbbreviationsDontEndTheFirstSentence() {
        assertThat(Citations.firstSentence("La Sra. Kahlo se casó con Diego Rivera en 1929 [1]. No hay más datos.", spanish = true))
            .isEqualTo("La Sra. Kahlo se casó con Diego Rivera en 1929 [1]")
        assertThat(Citations.firstSentence("Julio César murió en el 44 a. C. y Cleopatra en el 30 a. C. Los pasajes no dicen más.", spanish = true))
            .isEqualTo("Julio César murió en el 44 a. C. y Cleopatra en el 30 a. C")
        assertThat(Citations.check("Murió en el 44 a. C. Los pasajes no dicen nada más [1].", 3, es)).isEqualTo(CitationCheck.Valid(setOf(1)))
    }
}
