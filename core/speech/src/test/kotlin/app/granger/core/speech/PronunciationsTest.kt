package app.granger.core.speech

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PronunciationsTest {
    private val table = mapOf("Rosetta" to "Rozetta")

    @Test
    fun pronunciationsReplaceWholeWordsAndKeepCase() {
        assertThat(Pronunciations.apply("The Rosetta Stone is a stele.", table)).isEqualTo("The Rozetta Stone is a stele.")
        assertThat(Pronunciations.apply("rosetta, again", table)).isEqualTo("rozetta, again")
        assertThat(Pronunciations.apply("ROSETTA", table)).isEqualTo("ROZETTA")
        assertThat(Pronunciations.apply("Rosettas and Rosetta's", table)).isEqualTo("Rosettas and Rozetta's")
    }

    @Test
    fun pronunciationsLeaveTextWithoutMatchesIdentical() {
        val text = "Mars, the Bringer of War, is written in 5/4 time."
        assertThat(Pronunciations.apply(text, table)).isSameInstanceAs(text)
        assertThat(Pronunciations.apply(text)).isSameInstanceAs(text)          // the built-in table is empty for now
    }

    @Test
    fun aCustomTableTakesTheLongestMatchFirst() {
        val table = mapOf("St" to "Saint", "St Ives" to "Saint Ives")
        assertThat(Pronunciations.apply("St Ives and St Paul", table)).isEqualTo("Saint Ives and Saint Paul")
    }
}
