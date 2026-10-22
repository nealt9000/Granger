package app.granger.core.speech

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PronunciationsTest {
    @Test
    fun pronunciationsReplaceWholeWordsAndKeepCase() {
        assertThat(Pronunciations.apply("The Rosetta Stone is a stele.")).isEqualTo("The Rozetta Stone is a stele.")
        assertThat(Pronunciations.apply("rosetta, again")).isEqualTo("rozetta, again")
        assertThat(Pronunciations.apply("ROSETTA")).isEqualTo("ROZETTA")
        assertThat(Pronunciations.apply("Rosettas and Rosetta's")).isEqualTo("Rosettas and Rozetta's")
    }

    @Test
    fun pronunciationsLeaveTextWithoutMatchesIdentical() {
        val text = "Mars, the Bringer of War, is written in 5/4 time."
        assertThat(Pronunciations.apply(text)).isSameInstanceAs(text)
        assertThat(Pronunciations.apply(text, emptyMap())).isSameInstanceAs(text)
    }

    @Test
    fun aCustomTableTakesTheLongestMatchFirst() {
        val table = mapOf("St" to "Saint", "St Ives" to "Saint Ives")
        assertThat(Pronunciations.apply("St Ives and St Paul", table)).isEqualTo("Saint Ives and Saint Paul")
    }
}
