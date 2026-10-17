package app.granger.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import androidx.compose.material3.darkColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test
    fun accentAndInverseRolesAreReadable() {
        val s = GrangerColorScheme
        assertTrue(contrast(s.onPrimary, s.primary) >= 4.5)
        assertTrue(contrast(s.inverseOnSurface, s.inverseSurface) >= 4.5)
        assertTrue(contrast(s.onSecondaryContainer, s.secondaryContainer) >= 4.5)
        assertTrue(contrast(s.onTertiary, s.tertiary) >= 4.5)
        assertTrue(contrast(s.onTertiaryContainer, s.tertiaryContainer) >= 4.5)
    }

    @Test
    fun noRoleKeepsTheMaterialBaseline() {
        val base = darkColorScheme()
        val s = GrangerColorScheme
        val roles = listOf<Triple<String, Color, Color>>(
            Triple("inverseSurface", s.inverseSurface, base.inverseSurface),
            Triple("inverseOnSurface", s.inverseOnSurface, base.inverseOnSurface),
            Triple("inversePrimary", s.inversePrimary, base.inversePrimary),
            Triple("surfaceContainerLowest", s.surfaceContainerLowest, base.surfaceContainerLowest),
            Triple("surfaceContainerLow", s.surfaceContainerLow, base.surfaceContainerLow),
            Triple("surfaceContainer", s.surfaceContainer, base.surfaceContainer),
            Triple("surfaceContainerHigh", s.surfaceContainerHigh, base.surfaceContainerHigh),
            Triple("surfaceContainerHighest", s.surfaceContainerHighest, base.surfaceContainerHighest),
            Triple("surfaceDim", s.surfaceDim, base.surfaceDim),
            Triple("surfaceBright", s.surfaceBright, base.surfaceBright),
            Triple("secondaryContainer", s.secondaryContainer, base.secondaryContainer),
            Triple("onSecondaryContainer", s.onSecondaryContainer, base.onSecondaryContainer),
            Triple("tertiary", s.tertiary, base.tertiary),
            Triple("onTertiary", s.onTertiary, base.onTertiary),
            Triple("tertiaryContainer", s.tertiaryContainer, base.tertiaryContainer),
            Triple("onTertiaryContainer", s.onTertiaryContainer, base.onTertiaryContainer),
            Triple("scrim", s.scrim, base.scrim),
        )
        // scrim is black in the baseline too; it is the one role Granger legitimately shares.
        for ((name, ours, theirs) in roles) if (name != "scrim") assertNotEquals(name, theirs, ours)
    }
}
