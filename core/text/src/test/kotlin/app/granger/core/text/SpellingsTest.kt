package app.granger.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpellingsTest {
    @Test
    fun aWordWithAnotherSpellingGivesBothTheGivenOneFirst() {
        assertEquals(listOf("grey", "gray"), Spellings.variants("grey"))
        assertEquals(listOf("gray", "grey"), Spellings.variants("gray"))
        assertEquals(listOf("Colour", "color"), Spellings.variants("Colour"))
        assertEquals(listOf("anemia", "anaemia"), Spellings.variants("anemia"))
        assertEquals(listOf("favourite", "favorite"), Spellings.variants("favourite"))
        assertEquals(listOf("realised", "realized"), Spellings.variants("realised"))
        assertEquals(listOf("travelling", "traveling"), Spellings.variants("travelling"))
        assertEquals(listOf("analyse", "analyze"), Spellings.variants("analyse"))
        assertEquals(listOf("dapple-grey", "dapple-gray"), Spellings.variants("dapple-grey"))
        assertEquals(listOf("colour,", "color,"), Spellings.variants("colour,"))
    }

    @Test
    fun wordsThatOnlyLookLikeTheRulesAreLeftAlone() {
        for (word in listOf("promise", "otherwise", "rise", "hour", "four", "horse", "stories", "merchandise", "cheese")) {
            assertEquals(word, listOf(word), Spellings.variants(word))
        }
    }

    @Test
    fun formsCombineEachWordsSpellingsAsGivenFirstAndStopAtMax() {
        assertEquals(listOf("the dapple grey horse", "the dapple gray horse"), Spellings.forms("the dapple grey horse"))
        assertEquals(listOf("toasted cheese"), Spellings.forms("toasted cheese"))
        val four = Spellings.forms(listOf("grey", "colour", "centre"))
        assertEquals(4, four.size)
        assertEquals(listOf("grey", "colour", "centre"), four.first())
        assertEquals(listOf("grey", "colour", "center"), four[1])
        assertEquals(2, Spellings.forms(listOf("grey", "colour"), max = 2).size)
    }

    @Test
    fun anyTellsWhetherAQueryHasSomethingToVary() {
        assertTrue(Spellings.any(listOf("the", "dapple", "Grey")))
        assertFalse(Spellings.any(listOf("toasted", "cheese")))
    }
}
