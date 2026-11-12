package app.granger.core.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KeywordQueryTest {
    @Test fun aQuestionLosesItsSmallWords() {
        val q = ZimLibrarySearch.keywordQuery("who chopped off holofernes head")
        assertThat(q).isNotNull()
        assertThat(q).contains("holofernes")
        assertThat(SearchText.words(q!!)).containsNoneOf("who", "off")
    }

    @Test fun aNameIsLeftAlone() {
        assertThat(ZimLibrarySearch.keywordQuery("holofernes")).isNull()
    }
}
