package app.granger.core.speech

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpokenWordsTest {
    private fun said(text: String) = SpokenWords.forPiper(text)

    @Test
    fun quoteMarksGoSoNothingIsLeftAfterTheLastStop() {
        assertThat(said("“Come in.”")).isEqualTo("Come in.")
        assertThat(said("\"Come in,\" she said.")).isEqualTo("Come in, she said.")
        assertThat(said("‘Come in.’")).isEqualTo("Come in.")
        assertThat(said("'Come in.'")).isEqualTo("Come in.")
        assertThat(said("«Come in.»")).isEqualTo("Come in.")
        assertThat(said("He said, “Come in!”")).isEqualTo("He said, Come in!")
        assertThat(said("(Come in.)")).isEqualTo("(Come in.")
        assertThat(said("Come in.]")).isEqualTo("Come in.")
        assertThat(said("It was *very* _odd_.")).isEqualTo("It was very odd.")
    }

    @Test
    fun apostrophesInsideWordsStay() {
        for (text in listOf("Don't go.", "It's five o’clock.", "The cat’s whiskers.", "snake_case")) assertThat(said(text)).isEqualTo(text)
        assertThat(said("’Tis the boys’ toys.")).isEqualTo("Tis the boys toys.")
    }

    @Test
    fun titlesAreWrittenOut() {
        assertThat(said("Mr. Smith met Mrs. Jones and Dr. Watson.")).isEqualTo("Mister Smith met Missus Jones and Doctor Watson.")
        assertThat(said("Ms. Brown, Lt. Gray and Sgt. Pepper.")).isEqualTo("Miz Brown, Lieutenant Gray and Sergeant Pepper.")
        assertThat(said("Mr Darcy and Mrs Bennet")).isEqualTo("Mister Darcy and Missus Bennet")
        assertThat(said("St. Paul's and Mt. Everest")).isEqualTo("Saint Paul's and Mount Everest")
        assertThat(said("He lived on Baker St. for years.")).isEqualTo("He lived on Baker Street for years.")
        assertThat(said("Martin Luther King, Jr. spoke.")).isEqualTo("Martin Luther King, Junior spoke.")
        assertThat(said("Dr. J. Watson")).isEqualTo("Doctor J Watson")
    }

    @Test
    fun abbreviationsBeforeNumbersAndAnywhere() {
        assertThat(said("See No. 5 on p. 12 and Fig. 3.")).isEqualTo("See number 5 on page 12 and Figure 3.")
        assertThat(said("Beethoven's Op. 27")).isEqualTo("Beethoven's Opus 27")
        assertThat(said("Fruit, e.g. apples, i.e. food.")).isEqualTo("Fruit, for example apples, that is food.")
        assertThat(said("Roe v. Wade and Ali vs. Frazier")).isEqualTo("Roe versus Wade and Ali versus Frazier")
        assertThat(said("Smith et al. found it.")).isEqualTo("Smith and others found it.")
    }

    @Test
    fun initialsLoseTheirStops() {
        assertThat(said("J. R. R. Tolkien wrote it.")).isEqualTo("J.R.R Tolkien wrote it.")
        assertThat(said("by E. M. Forster")).isEqualTo("by E.M Forster")
        assertThat(said("the U.S. Army")).isEqualTo("the U.S Army")
        assertThat(said("A. Milne")).isEqualTo("Ay Milne")
        assertThat(said("C. S. Lewis")).isEqualTo("C.S Lewis")
        assertThat(said("I. M. Pei built it.")).isEqualTo("I.M Pei built it.")
        assertThat(said("He moved to the U.S.")).isEqualTo("He moved to the U.S.")
    }

    @Test
    fun numeralsAfterChapterAndTheLikeAreCounted() {
        assertThat(said("Chapter IV")).isEqualTo("Chapter 4")
        assertThat(said("CHAPTER XII. THE RETURN")).isEqualTo("CHAPTER 12. THE RETURN")
        assertThat(said("Chapter I")).isEqualTo("Chapter 1")
        assertThat(said("Chapter XL")).isEqualTo("Chapter 40")
        assertThat(said("Chapter LXIV")).isEqualTo("Chapter 64")
        assertThat(said("Book III, Part ii")).isEqualTo("Book 3, Part 2")
        assertThat(said("Act III, Scene ii")).isEqualTo("Act 3, Scene 2")
        assertThat(said("World War II and World War I.")).isEqualTo("World War 2 and World War 1.")
        assertThat(said("Part I of the book")).isEqualTo("Part 1 of the book")
        assertThat(said("Vol. IV")).isEqualTo("Volume 4")
    }

    @Test
    fun numeralsAfterNamesAreOrdinals() {
        assertThat(said("Henry VIII had six wives.")).isEqualTo("Henry the Eighth had six wives.")
        assertThat(said("Louis XIV's court")).isEqualTo("Louis the Fourteenth's court")
        assertThat(said("Elizabeth II and Elizabeth I.")).isEqualTo("Elizabeth the Second and Elizabeth the First.")
        assertThat(said("Pope Pius XII")).isEqualTo("Pope Pius the Twelfth")
    }

    @Test
    fun aHeadingThatStartsWithItsNumeral() {
        assertThat(said("IV. The Return")).isEqualTo("4. The Return")
        assertThat(said("XII")).isEqualTo("12")
        assertThat(said("I.")).isEqualTo("1.")
        assertThat(said("I. Down the Rabbit-Hole")).isEqualTo("1. Down the Rabbit-Hole")
        assertThat(said("ii) the second")).isEqualTo("2) the second")
    }

    @Test
    fun whatMustNotChange() {
        for (text in listOf(
            "I think so.", "I am here.", "Then I left.", "Then George I said no.", "the book I read", "Part I was hard.",
            "Vitamin C is good.", "Grade C work", "Type C cable", "a MIX of things", "CIVIL WAR", "I, too, went.",
            "Gen Z", "Plain text with no marks at all", "It was 5 p.m. there.", "The DC area",
        )) {
            assertThat(said(text)).isEqualTo(text)
        }
    }

    @Test
    fun romanValues() {
        val table = mapOf("I" to 1, "IV" to 4, "IX" to 9, "XIV" to 14, "XL" to 40, "XC" to 90, "MCMXCIV" to 1994, "iv" to 4, "xii" to 12)
        for ((numeral, n) in table) assertThat(SpokenWords.roman(numeral)).isEqualTo(n)
        for (bad in listOf("IIII", "VX", "IL", "Iv", "", "ABC")) assertThat(SpokenWords.roman(bad)).isNull()
    }
}
