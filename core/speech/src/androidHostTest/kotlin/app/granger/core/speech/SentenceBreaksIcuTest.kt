package app.granger.core.speech

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [SentenceBreakCases] with Android's ICU (`android.icu.text.BreakIterator`, real under Robolectric): the reference. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SentenceBreaksIcuTest {
    @Test fun theSameParagraphsBreakAsRecorded() {
        assertWithMessage("ICU's breaks").that(SentenceBreakCases.differences(ios = false)).isEmpty()
        assertWithMessage("every paragraph has a case").that(SentenceBreakCases.cases.map { it.text to it.languageTag })
            .containsExactlyElementsIn(SentenceBreakCases.paragraphs.map { it.second to it.first })
    }
}
