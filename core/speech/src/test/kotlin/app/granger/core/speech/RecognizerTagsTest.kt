package app.granger.core.speech

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecognizerTagsTest {
    /** What the Pixel 6 reported in the A0 spike (abridged). */
    private val pixel6 = RecognizerLanguages(
        installed = listOf("en-US", "es-ES"),
        supported = listOf("en-GB", "en-AU", "es-US", "fr-FR", "de-DE"),
    )

    @Test
    fun englishUsesTheChosenVariantEvenWhenItIsOnlyDownloadable() {
        assertThat(RecognizerTags.forApp("en", RecognizerTags.ENGLISH_US, pixel6)).isEqualTo("en-US")
        val uk = RecognizerTags.forApp("en", RecognizerTags.ENGLISH_UK, pixel6)
        assertThat(uk).isEqualTo("en-GB")
        assertThat(pixel6.availability(uk!!)).isEqualTo(Availability.DOWNLOADABLE)
    }

    @Test
    fun englishFallsBackToAnyInstalledEnglishWhenTheChoiceDoesNotExist() {
        val onlyIndia = RecognizerLanguages(installed = listOf("en-IN"), supported = emptyList())
        assertThat(RecognizerTags.forApp("en", RecognizerTags.ENGLISH_UK, onlyIndia)).isEqualTo("en-IN")
    }

    @Test
    fun spanishPrefersAnythingInstalledOverADownload() {
        assertThat(RecognizerTags.forApp("es", RecognizerTags.ENGLISH_US, pixel6)).isEqualTo("es-ES")
        val mexico = RecognizerLanguages(installed = listOf("es-MX"), supported = listOf("es-ES"))
        assertThat(RecognizerTags.forApp("es", RecognizerTags.ENGLISH_US, mexico)).isEqualTo("es-MX")
        val none = RecognizerLanguages(installed = listOf("en-US"), supported = listOf("es-US", "es-ES"))
        assertThat(RecognizerTags.forApp("es", RecognizerTags.ENGLISH_US, none)).isEqualTo("es-ES")
    }

    @Test
    fun otherLanguagesAndUnknownPhonesHaveNoMic() {
        assertThat(RecognizerTags.forApp("sw", RecognizerTags.ENGLISH_US, pixel6)).isNull()
        assertThat(RecognizerTags.forApp("en", RecognizerTags.ENGLISH_US, null)).isNull()
        assertThat(RecognizerTags.forApp("es", RecognizerTags.ENGLISH_US, RecognizerLanguages(listOf("en-US"), emptyList()))).isNull()
    }

    @Test
    fun tagsCompareWithoutCase() {
        assertThat(RecognizerLanguages(listOf("en-us"), emptyList()).availability("en-US")).isEqualTo(Availability.READY)
    }
}
