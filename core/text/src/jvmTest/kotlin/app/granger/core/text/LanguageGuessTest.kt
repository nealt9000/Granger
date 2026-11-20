package app.granger.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LanguageGuessTest {
    @Test fun spanishBeatsEnglishByStopwords() {
        assertThat(LanguageGuess.guess("Era una noche oscura y tormentosa; de pronto se oyó un disparo en la casa del médico.")).isEqualTo("es")
        assertThat(LanguageGuess.guess("It was a dark and stormy night; suddenly a shot rang out from the house of the doctor.")).isEqualTo("en")
        assertThat(LanguageGuess.guess("")).isEqualTo("en")
    }

    @Test fun aDeclaredLanguageWins() {
        assertThat(LanguageGuess.declared("spa")).isEqualTo("es")
        assertThat(LanguageGuess.declared("Spanish")).isEqualTo("es")
        assertThat(LanguageGuess.declared("es-MX")).isEqualTo("es")
        assertThat(LanguageGuess.declared("eng")).isEqualTo("en")
        assertThat(LanguageGuess.declared("fre")).isNull()
        assertThat(LanguageGuess.declared("fra")).isEqualTo("fr")
        assertThat(LanguageGuess.declared("")).isNull()
        assertThat(LanguageGuess.tag("", "Había una vez un rey que tenía tres hijas.")).isEqualTo("es")
    }

    @Test fun onlyTheFirstWordsCount() {
        val text = "the of and ".repeat(1_000) + "de la que ".repeat(5_000)
        assertThat(LanguageGuess.guess(text)).isEqualTo("en")
    }
}
