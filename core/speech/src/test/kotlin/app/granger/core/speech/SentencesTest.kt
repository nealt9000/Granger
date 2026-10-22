package app.granger.core.speech

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ICU's sentence breaks come from the Android framework, so this runs under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SentencesTest {
    @Test
    fun whitespaceIsFoldedAndFragmentsWithoutLettersAreDropped() {
        assertThat(Sentences.split("One  two\n three.   Four! Five?")).containsExactly("One two three.", "Four!", "Five?").inOrder()
        assertThat(Sentences.split("...")).isEmpty()
        assertThat(Sentences.split("   ")).isEmpty()
        assertThat(Sentences.split("")).isEmpty()
    }

    @Test
    fun offsetsIndexTheFoldedText() {
        val folded = Sentences.fold("One  two\n three.   Four! Five?")
        val s = Sentences.sentences("One  two\n three.   Four! Five?")
        assertThat(folded).isEqualTo("One two three. Four! Five?")
        assertThat(s.map { folded.substring(it.start, it.end) }).isEqualTo(s.map { it.text })
        assertThat(s[1]).isEqualTo(Sentence("Four!", 15, 20))
    }

    @Test
    fun abbreviationsAndNumbersStayInsideTheirSentence() {
        val s = Sentences.split("Mars, the Bringer of War, is written in 5/4 time and runs about 7 minutes. Dr. Holst wrote it in 1914.")
        assertThat(s).hasSize(2)
        assertThat(s[1]).isEqualTo("Dr. Holst wrote it in 1914.")
    }
}
