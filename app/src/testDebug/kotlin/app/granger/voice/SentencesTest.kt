package app.granger.voice

import app.granger.core.speech.Sentences

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
    fun articlePassageSplitsIntoItsSixSentences() {
        val s = Sentences.split(VoiceSpikePassages.ARTICLE.text)
        assertThat(s).hasSize(6)
        assertThat(s.first()).startsWith("The Rosetta Stone is a stele of granodiorite")
        assertThat(s.first()).endsWith("King Ptolemy V Epiphanes.")
        assertThat(s.last()).isEqualTo("It was found there in July 1799 by French officer Pierre-François Bouchard during the Napoleonic campaign in Egypt.")
    }

    @Test
    fun versePassageSplitsIntoNineSentencesAcrossLineBreaks() {
        val s = Sentences.split(VoiceSpikePassages.BOOK.text)
        assertThat(s).hasSize(9)
        assertThat(s[0]).isEqualTo("The quality of mercy is not strained.")
        assertThat(s[3]).startsWith("'Tis mightiest in the mightiest;")
        assertThat(s.none { '\n' in it }).isTrue()
    }

    @Test
    fun answerPassageSplitsIntoFourSentencesKeepingNumbers() {
        val s = Sentences.split(VoiceSpikePassages.ANSWER.text)
        assertThat(s).hasSize(4)
        assertThat(s[1]).isEqualTo("Mars, the Bringer of War, is written in 5/4 time and runs about 7 minutes in most recordings.")
    }

    @Test
    fun whitespaceIsFoldedAndFragmentsWithoutLettersAreDropped() {
        assertThat(Sentences.split("One  two\n three.   Four! Five?")).containsExactly("One two three.", "Four!", "Five?").inOrder()
        assertThat(Sentences.split("...")).isEmpty()
        assertThat(Sentences.split("   ")).isEmpty()
        assertThat(Sentences.split("")).isEmpty()
    }
}
