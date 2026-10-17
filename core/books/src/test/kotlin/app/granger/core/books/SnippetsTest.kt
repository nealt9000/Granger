package app.granger.core.books

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SnippetsTest {
    @Test fun highlightsTermsAndEscapes() {
        val text = "a".repeat(200) + " Tom & <Jerry> chase the cat " + "b".repeat(200)
        val s = Snippets.around(text, "jerry CAT", radius = 40)
        assertThat(s).contains("<b>Jerry</b>")
        assertThat(s).contains("<b>cat</b>")
        assertThat(s).contains("&amp;")
        assertThat(s).contains("&lt;<b>Jerry</b>&gt;")
        assertThat(s).startsWith("…")
        assertThat(s).endsWith("…")
    }

    @Test fun noTermMatchReturnsEscapedStart() {
        val s = Snippets.around("<x> " + "z".repeat(500), "nothing", radius = 20)
        assertThat(s).startsWith("&lt;x&gt; zzz")
        assertThat(s).endsWith("…")
    }

    @Test fun shortTextHasNoEllipsis() {
        assertThat(Snippets.around("hello world", "world")).isEqualTo("hello <b>world</b>")
    }
}
