package app.granger.ebook

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The words before a hit in the search panel are cut short, so the match shows in the row (Pixel check, I5a). */
class SearchLeadTest {
    @Test fun shortTextStaysWhole() {
        assertThat(SearchRows.lead("The sky over the ")).isEqualTo("The sky over the ")
    }

    @Test fun longTextKeepsItsLastWordsFromAWordBreak() {
        val before = "smoke, something green and sour, like the bottom of a pond. Like the fog. By the time he got to the window, the "
        val lead = SearchRows.lead(before)
        assertThat(lead).startsWith("…")
        assertThat(lead.length).isAtMost(SearchRows.LEAD_CHARS + 1)
        assertThat(before).endsWith(lead.removePrefix("…"))
        assertThat(lead.removePrefix("…").first()).isNotEqualTo(' ')
        // It starts on a whole word: the character before it in the full text is a space.
        val at = before.length - lead.length + 1
        assertThat(before[at - 1]).isEqualTo(' ')
    }
}
