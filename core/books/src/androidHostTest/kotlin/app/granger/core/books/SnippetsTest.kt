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

    @Test fun quoteFindsThePhraseWithContext() {
        val text = "On what compulsion must I? Tell me that.\nPORTIA. The quality of\n mercy is not strain'd, It droppeth"
        val q = Snippets.quote(text, "Quality of mercy", context = 12)!!
        assertThat(q.highlight).isEqualTo("quality of\n mercy")
        assertThat(q.before).isEqualTo("PORTIA. The ")
        assertThat(q.after).isEqualTo(" is not stra")
    }

    @Test fun quoteFallsBackToTheFirstWordFound() {
        val q = Snippets.quote("the gentle rain from heaven, mercy seasons justice", "mercy rain")!!
        assertThat(q.highlight).isEqualTo("rain")
        assertThat(q.before).isEqualTo("the gentle ")
    }

    @Test fun quoteIsNullWithoutAMatch() {
        assertThat(Snippets.quote("nothing here", "mercy")).isNull()
        assertThat(Snippets.quote("nothing here", "  ")).isNull()
    }

    @Test fun quoteIsCappedInLength() {
        val q = Snippets.quote("a " + "x".repeat(300), "x".repeat(300), maxLength = 50)!!
        assertThat(q.highlight).hasLength(50)
    }
}
