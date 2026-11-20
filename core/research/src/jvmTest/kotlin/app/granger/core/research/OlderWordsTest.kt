package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OlderWordsTest {
    @Test fun aWholeKeywordGetsTheOtherSidesWords() {
        assertThat(OlderWords.expand(listOf("alcohol", "bible"))).containsExactly("alcohol|wine|strong drink", "bible").inOrder()
        assertThat(OlderWords.expand(listOf("Taxes", "jesus"))).containsExactly("Taxes|tribute", "jesus").inOrder()
        assertThat(OlderWords.expand(listOf("dropsy"))).containsExactly("dropsy|edema|oedema")
    }

    @Test fun aWordInsideAKeywordOrAnotherLanguageIsLeftAlone() {
        assertThat(OlderWords.expand(listOf("energy consumption", "artificial intelligence"))).containsExactly("energy consumption", "artificial intelligence").inOrder()
        assertThat(OlderWords.expand(listOf("wine|grape"))).containsExactly("wine|grape")
        assertThat(OlderWords.expand(listOf("alcohol"), listOf(ResearchLanguage.ES))).containsExactly("alcohol")
    }
}
