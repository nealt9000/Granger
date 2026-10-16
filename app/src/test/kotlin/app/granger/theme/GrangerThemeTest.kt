package app.granger.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrangerThemeTest {
    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color) =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun accentIsFlameOrange() {
        assertEquals(Color(0xFFE8601C), GrangerColorScheme.primary)
    }

    @Test
    fun textIsReadableOnSurfaces() {
        val s = GrangerColorScheme
        assertTrue(contrast(s.onSurface, s.surface) >= 4.5)
        assertTrue(contrast(s.onBackground, s.background) >= 4.5)
        assertTrue(contrast(s.onSurfaceVariant, s.surfaceVariant) >= 4.5)
        assertTrue(contrast(s.primary, s.surface) >= 3.0)
    }
}
