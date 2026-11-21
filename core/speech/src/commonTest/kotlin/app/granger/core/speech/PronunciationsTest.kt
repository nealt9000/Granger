package app.granger.core.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PronunciationsTest {
    private val table = mapOf("Rosetta" to "Rozetta")

    @Test
    fun pronunciationsReplaceWholeWordsAndKeepCase() {
        assertEquals("The Rozetta Stone is a stele.", Pronunciations.apply("The Rosetta Stone is a stele.", table))
        assertEquals("rozetta, again", Pronunciations.apply("rosetta, again", table))
        assertEquals("ROZETTA", Pronunciations.apply("ROSETTA", table))
        assertEquals("Rosettas and Rozetta's", Pronunciations.apply("Rosettas and Rosetta's", table))
    }

    @Test
    fun pronunciationsLeaveTextWithoutMatchesIdentical() {
        val text = "Mars, the Bringer of War, is written in 5/4 time."
        assertSame(text, Pronunciations.apply(text, table))
        assertSame(text, Pronunciations.apply(text))          // the built-in table is empty for now
    }

    @Test
    fun aCustomTableTakesTheLongestMatchFirst() {
        val table = mapOf("St" to "Saint", "St Ives" to "Saint Ives")
        assertEquals("Saint Ives and Saint Paul", Pronunciations.apply("St Ives and St Paul", table))
    }
}
