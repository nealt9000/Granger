package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResearchLanguageCodeTest {
    @Test fun zimLanguagesTakeTheFirst() {
        assertThat(ResearchLanguage.ofCode("spa")).isEqualTo(ResearchLanguage.ES)
        assertThat(ResearchLanguage.ofCode("eng")).isEqualTo(ResearchLanguage.EN)
        assertThat(ResearchLanguage.ofCode("spa,eng")).isEqualTo(ResearchLanguage.ES)
        assertThat(ResearchLanguage.ofCode("eng,spa")).isEqualTo(ResearchLanguage.EN)
        assertThat(ResearchLanguage.ofCode("fra,spa")).isNull()
        assertThat(ResearchLanguage.ofCode("mul")).isNull()
    }

    @Test fun bookLanguagesIgnoreTheRegion() {
        assertThat(ResearchLanguage.ofCode("es")).isEqualTo(ResearchLanguage.ES)
        assertThat(ResearchLanguage.ofCode("es-MX")).isEqualTo(ResearchLanguage.ES)
        assertThat(ResearchLanguage.ofCode("ES_es")).isEqualTo(ResearchLanguage.ES)
        assertThat(ResearchLanguage.ofCode(" en-US ")).isEqualTo(ResearchLanguage.EN)
        assertThat(ResearchLanguage.ofCode("Español")).isEqualTo(ResearchLanguage.ES)
        assertThat(ResearchLanguage.ofCode("English")).isEqualTo(ResearchLanguage.EN)
        assertThat(ResearchLanguage.ofCode("de")).isNull()
        assertThat(ResearchLanguage.ofCode("")).isNull()
        assertThat(ResearchLanguage.ofCode(null)).isNull()
    }
}
