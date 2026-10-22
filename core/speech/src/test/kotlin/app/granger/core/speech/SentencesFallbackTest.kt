package app.granger.core.speech

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Plain JUnit, no Robolectric: the framework's ICU is a stub here, so this exercises the punctuation fallback. */
class SentencesFallbackTest {
    @Test
    fun withoutIcuSentencesStillSplitOnPunctuationAndKeepTitlesTogether() {
        assertThat(Sentences.split("One two. Three four five. Six!  Seven?")).containsExactly("One two.", "Three four five.", "Six!", "Seven?").inOrder()
        assertThat(Sentences.split("Dr. Holst wrote it in 1914. It runs 7 minutes.")).containsExactly("Dr. Holst wrote it in 1914.", "It runs 7 minutes.").inOrder()
        val s = Sentences.sentences("Plants make sugar. They use light.")
        assertThat(s[1]).isEqualTo(Sentence("They use light.", 19, 34))
    }
}
