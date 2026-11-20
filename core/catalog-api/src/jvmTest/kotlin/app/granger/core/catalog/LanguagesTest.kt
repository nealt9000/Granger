package app.granger.core.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LanguagesTest {
    @Test fun normaliseMapsThreeLetterCodesAndKeepsOrder() {
        assertThat(Languages.normalise("eng")).isEqualTo("en")
        assertThat(Languages.normalise("spa")).isEqualTo("es")
        assertThat(Languages.normalise("eng,spa")).isEqualTo("en,es")
        assertThat(Languages.normalise("spa, eng,spa")).isEqualTo("es,en")
        assertThat(Languages.normalise("fra,deu,ind,heb")).isEqualTo("fr,de,id,he")
    }

    @Test fun normaliseLeavesBcp47AndUnknownCodesAlone() {
        assertThat(Languages.normalise("en")).isEqualTo("en")
        assertThat(Languages.normalise("en-US")).isEqualTo("en")
        assertThat(Languages.normalise("ES")).isEqualTo("es")
        assertThat(Languages.normalise("mul")).isEqualTo("mul")
        assertThat(Languages.normalise("")).isEmpty()
    }

    @Test fun codesAndHas() {
        assertThat(Languages.codes("eng,spa")).containsExactly("en", "es").inOrder()
        assertThat(Languages.codes("")).isEmpty()
        assertThat(Languages.has("eng,spa", "es")).isTrue()
        assertThat(Languages.has("en,es", "spa")).isTrue()
        assertThat(Languages.has("eng", "es")).isFalse()
        assertThat(Languages.has("eng", "")).isFalse()
    }
}
