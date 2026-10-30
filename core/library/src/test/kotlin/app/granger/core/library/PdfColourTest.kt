package app.granger.core.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PdfColourTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun printAndPaperArentColourButPanelsAre() {
        val text = IntArray(100) { if (it % 3 == 0) rgb(20, 20, 20) else rgb(255, 255, 255) }
        assertThat(PdfColour.colourShare(text)).isEqualTo(0f)
        val oldPaper = IntArray(100) { rgb(230, 215, 170) }                     // a yellowed scan
        assertThat(PdfColour.colourShare(oldPaper)).isEqualTo(0f)
        val greyScan = IntArray(100) { rgb(128, 125, 120) }
        assertThat(PdfColour.colourShare(greyScan)).isEqualTo(0f)
        val panel = IntArray(100) { if (it < 60) rgb(40, 70, 160) else rgb(255, 255, 255) }   // a comic's night sky
        assertThat(PdfColour.colourShare(panel)).isWithin(0.001f).of(0.6f)
    }
}
