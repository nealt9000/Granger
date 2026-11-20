package app.granger.core.text

import kotlin.test.Test
import kotlin.test.assertEquals

class SentencesTest {
    private fun split(p: String) = Sentences.breaks(p).zipWithNext { a, b -> p.substring(a, b) }

    @Test fun breaksAfterEndsBeforeACapital() {
        assertEquals(listOf("It was dark. ", "Very dark! ", "Was it? ", "Yes."), split("It was dark. Very dark! Was it? Yes."))
    }

    @Test fun keepsAbbreviationsBeforeLowerCase() {
        assertEquals(listOf("He met him in the U.S. in 1940."), split("He met him in the U.S. in 1940."))
        assertEquals(listOf("Dr. ", "Smith met him."), split("Dr. Smith met him."))
    }

    @Test fun closingQuotesStayWithTheirSentence() {
        assertEquals(listOf("“Go.” ", "He went."), split("“Go.” He went."))
        assertEquals(listOf("¿Qué? ", "¡Nada!"), split("¿Qué? ¡Nada!"))
    }

    @Test fun numbersAndEdges() {
        assertEquals(listOf("Pi is 3.14 or so."), split("Pi is 3.14 or so."))
        assertEquals(listOf(0), Sentences.breaks(""))
        assertEquals(listOf("No end"), split("No end"))
    }
}
