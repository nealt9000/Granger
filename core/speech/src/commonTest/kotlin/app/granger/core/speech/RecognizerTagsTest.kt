package app.granger.core.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RecognizerTagsTest {
    /** What the Pixel 6 reported in the A0 spike (abridged). */
    private val pixel6 = RecognizerLanguages(
        installed = listOf("en-US", "es-ES"),
        supported = listOf("en-GB", "en-AU", "es-US", "fr-FR", "de-DE"),
    )

    @Test
    fun englishUsesTheChosenVariantEvenWhenItIsOnlyDownloadable() {
        assertEquals("en-US", RecognizerTags.forApp("en", RecognizerTags.ENGLISH_US, pixel6))
        val uk = RecognizerTags.forApp("en", RecognizerTags.ENGLISH_UK, pixel6)
        assertEquals("en-GB", uk)
        assertEquals(Availability.DOWNLOADABLE, pixel6.availability(uk!!))
    }

    @Test
    fun englishFallsBackToAnyInstalledEnglishWhenTheChoiceDoesNotExist() {
        val onlyIndia = RecognizerLanguages(installed = listOf("en-IN"), supported = emptyList())
        assertEquals("en-IN", RecognizerTags.forApp("en", RecognizerTags.ENGLISH_UK, onlyIndia))
    }

    @Test
    fun spanishPrefersAnythingInstalledOverADownload() {
        assertEquals("es-ES", RecognizerTags.forApp("es", RecognizerTags.ENGLISH_US, pixel6))
        val mexico = RecognizerLanguages(installed = listOf("es-MX"), supported = listOf("es-ES"))
        assertEquals("es-MX", RecognizerTags.forApp("es", RecognizerTags.ENGLISH_US, mexico))
        val none = RecognizerLanguages(installed = listOf("en-US"), supported = listOf("es-US", "es-ES"))
        assertEquals("es-ES", RecognizerTags.forApp("es", RecognizerTags.ENGLISH_US, none))
    }

    @Test
    fun otherLanguagesAndUnknownPhonesHaveNoMic() {
        assertNull(RecognizerTags.forApp("sw", RecognizerTags.ENGLISH_US, pixel6))
        assertNull(RecognizerTags.forApp("en", RecognizerTags.ENGLISH_US, null))
        assertNull(RecognizerTags.forApp("es", RecognizerTags.ENGLISH_US, RecognizerLanguages(listOf("en-US"), emptyList())))
    }

    @Test
    fun tagsCompareWithoutCase() {
        assertEquals(Availability.READY, RecognizerLanguages(listOf("en-us"), emptyList()).availability("en-US"))
    }
}
