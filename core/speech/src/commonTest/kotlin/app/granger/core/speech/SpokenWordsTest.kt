package app.granger.core.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpokenWordsTest {
    private fun said(text: String) = SpokenWords.forPiper(text)

    @Test
    fun quoteMarksGoSoNothingIsLeftAfterTheLastStop() {
        assertEquals("Come in.", said("“Come in.”"))
        assertEquals("Come in, she said.", said("\"Come in,\" she said."))
        assertEquals("Come in.", said("‘Come in.’"))
        assertEquals("Come in.", said("'Come in.'"))
        assertEquals("Come in.", said("«Come in.»"))
        assertEquals("He said, Come in!", said("He said, “Come in!”"))
        assertEquals("(Come in.", said("(Come in.)"))
        assertEquals("Come in.", said("Come in.]"))
        assertEquals("It was very odd.", said("It was *very* _odd_."))
    }

    @Test
    fun apostrophesInsideWordsStay() {
        for (text in listOf("Don't go.", "It's five o’clock.", "The cat’s whiskers.", "snake_case")) assertEquals(text, said(text))
        assertEquals("Tis the boys toys.", said("’Tis the boys’ toys."))
    }

    @Test
    fun titlesAreWrittenOut() {
        assertEquals("Mister Smith met Missus Jones and Doctor Watson.", said("Mr. Smith met Mrs. Jones and Dr. Watson."))
        assertEquals("Miz Brown, Lieutenant Gray and Sergeant Pepper.", said("Ms. Brown, Lt. Gray and Sgt. Pepper."))
        assertEquals("Mister Darcy and Missus Bennet", said("Mr Darcy and Mrs Bennet"))
        assertEquals("Saint Paul's and Mount Everest", said("St. Paul's and Mt. Everest"))
        assertEquals("He lived on Baker Street for years.", said("He lived on Baker St. for years."))
        assertEquals("Martin Luther King, Junior spoke.", said("Martin Luther King, Jr. spoke."))
        assertEquals("Doctor J Watson", said("Dr. J. Watson"))
    }

    @Test
    fun abbreviationsBeforeNumbersAndAnywhere() {
        assertEquals("See number 5 on page 12 and Figure 3.", said("See No. 5 on p. 12 and Fig. 3."))
        assertEquals("Beethoven's Opus 27", said("Beethoven's Op. 27"))
        assertEquals("Fruit, for example apples, that is food.", said("Fruit, e.g. apples, i.e. food."))
        assertEquals("Roe versus Wade and Ali versus Frazier", said("Roe v. Wade and Ali vs. Frazier"))
        assertEquals("Smith and others found it.", said("Smith et al. found it."))
    }

    @Test
    fun initialsLoseTheirStops() {
        assertEquals("J.R.R Tolkien wrote it.", said("J. R. R. Tolkien wrote it."))
        assertEquals("by E.M Forster", said("by E. M. Forster"))
        assertEquals("the U.S Army", said("the U.S. Army"))
        assertEquals("Ay Milne", said("A. Milne"))
        assertEquals("C.S Lewis", said("C. S. Lewis"))
        assertEquals("I.M Pei built it.", said("I. M. Pei built it."))
        assertEquals("He moved to the U.S.", said("He moved to the U.S."))
    }

    @Test
    fun numeralsAfterChapterAndTheLikeAreCounted() {
        assertEquals("Chapter 4", said("Chapter IV"))
        assertEquals("CHAPTER 12. THE RETURN", said("CHAPTER XII. THE RETURN"))
        assertEquals("Chapter 1", said("Chapter I"))
        assertEquals("Chapter 40", said("Chapter XL"))
        assertEquals("Chapter 64", said("Chapter LXIV"))
        assertEquals("Book 3, Part 2", said("Book III, Part ii"))
        assertEquals("Act 3, Scene 2", said("Act III, Scene ii"))
        assertEquals("World War 2 and World War 1.", said("World War II and World War I."))
        assertEquals("Part 1 of the book", said("Part I of the book"))
        assertEquals("Volume 4", said("Vol. IV"))
    }

    @Test
    fun numeralsAfterNamesAreOrdinals() {
        assertEquals("Henry the Eighth had six wives.", said("Henry VIII had six wives."))
        assertEquals("Louis the Fourteenth's court", said("Louis XIV's court"))
        assertEquals("Elizabeth the Second and Elizabeth the First.", said("Elizabeth II and Elizabeth I."))
        assertEquals("Pope Pius the Twelfth", said("Pope Pius XII"))
    }

    @Test
    fun aHeadingThatStartsWithItsNumeral() {
        assertEquals("4. The Return", said("IV. The Return"))
        assertEquals("12", said("XII"))
        assertEquals("1.", said("I."))
        assertEquals("1. Down the Rabbit-Hole", said("I. Down the Rabbit-Hole"))
        assertEquals("2) the second", said("ii) the second"))
    }

    @Test
    fun whatMustNotChange() {
        for (text in listOf(
            "I think so.", "I am here.", "Then I left.", "Then George I said no.", "the book I read", "Part I was hard.",
            "Vitamin C is good.", "Grade C work", "Type C cable", "a MIX of things", "CIVIL WAR", "I, too, went.",
            "Gen Z", "Plain text with no marks at all", "It was 5 p.m. there.", "The DC area",
        )) {
            assertEquals(text, said(text))
        }
    }

    @Test
    fun romanValues() {
        val table = mapOf("I" to 1, "IV" to 4, "IX" to 9, "XIV" to 14, "XL" to 40, "XC" to 90, "MCMXCIV" to 1994, "iv" to 4, "xii" to 12)
        for ((numeral, n) in table) assertEquals(n, SpokenWords.roman(numeral))
        for (bad in listOf("IIII", "VX", "IL", "Iv", "", "ABC")) assertNull(SpokenWords.roman(bad))
    }
}
