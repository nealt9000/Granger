package app.granger.ebook

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FirstVisibleTextTest {
    @Test fun parseReadsTheWebViewsJsonAnswer() {
        assertThat(FirstVisibleText.parse("\"One night\\u2014it was on the twentieth of March\"")).isEqualTo("One night—it was on the twentieth of March")
        assertThat(FirstVisibleText.parse("\"  padded  \"")).isEqualTo("padded")
        assertThat(FirstVisibleText.parse("\"He said \\\"no\\\"\\n\\\\ done\"")).isEqualTo("He said \"no\"\n\\ done")
        assertThat(FirstVisibleText.parse("null")).isNull()
        assertThat(FirstVisibleText.parse(null)).isNull()
        assertThat(FirstVisibleText.parse("\"\"")).isNull()
        assertThat(FirstVisibleText.parse("42")).isNull()
        assertThat(FirstVisibleText.parse("not json")).isNull()
    }

    @Test fun theScriptIsOneExpressionThatReturnsAStringOrNull() {
        assertThat(FirstVisibleText.JS.trim()).startsWith("(function () {")
        assertThat(FirstVisibleText.JS.trim()).endsWith("})()")
        assertThat(FirstVisibleText.JS).contains("return null;")
        assertThat(FirstVisibleText.JS).contains("slice(0, ${FirstVisibleText.SNIPPET})")
    }
}
