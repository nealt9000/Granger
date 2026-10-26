package app.granger.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.pow
import org.junit.Test

/** Plan T1, Decision 2: every text role passes 4.5:1 on what it sits on, outlines 3:1, in both schemes. */
class GrangerThemeTest {
    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private val schemes = listOf(
        Triple("Field", FieldColors, FieldExtras),
        Triple("Night", NightColors, NightExtras),
    )

    private fun grounds(s: ColorScheme) = listOf(
        "background" to s.background, "surface" to s.surface, "surfaceVariant" to s.surfaceVariant,
        "surfaceContainerLowest" to s.surfaceContainerLowest, "surfaceContainerLow" to s.surfaceContainerLow,
        "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest,
    )

    private fun check(scheme: String, what: String, fg: Color, bg: Color, atLeast: Double) {
        val c = contrast(fg, bg)
        assertWithMessage("$scheme: $what is ${"%.2f".format(c)}:1").that(c).isAtLeast(atLeast)
    }

    @Test fun textReadsOnEverySurface() {
        for ((name, s, x) in schemes) for ((g, bg) in grounds(s)) {
            check(name, "onSurface on $g", s.onSurface, bg, 4.5)
            check(name, "onSurfaceVariant on $g", s.onSurfaceVariant, bg, 4.5)
            check(name, "primary on $g", s.primary, bg, 4.5)
            check(name, "error on $g", s.error, bg, 4.5)
            check(name, "status on $g", x.status, bg, 4.5)
            check(name, "outline on $g", s.outline, bg, 3.0)
        }
    }

    @Test fun contentReadsOnItsContainer() {
        for ((name, s, _) in schemes) {
            check(name, "onPrimary", s.onPrimary, s.primary, 4.5)
            check(name, "onPrimaryContainer", s.onPrimaryContainer, s.primaryContainer, 4.5)
            check(name, "onSecondary", s.onSecondary, s.secondary, 4.5)
            check(name, "onSecondaryContainer", s.onSecondaryContainer, s.secondaryContainer, 4.5)
            check(name, "onTertiary", s.onTertiary, s.tertiary, 4.5)
            check(name, "onTertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer, 4.5)
            check(name, "onError", s.onError, s.error, 4.5)
            check(name, "onErrorContainer", s.onErrorContainer, s.errorContainer, 4.5)
            check(name, "inverseOnSurface", s.inverseOnSurface, s.inverseSurface, 4.5)
            check(name, "inversePrimary on inverseSurface", s.inversePrimary, s.inverseSurface, 4.5)
            check(name, "onBackground", s.onBackground, s.background, 4.5)
        }
    }

    @Test fun playAndTheMarkStandOut() {
        for ((name, s, x) in schemes) {
            check(name, "play on background", x.play, s.background, 3.0)
            check(name, "onPrimary on play", s.onPrimary, x.play, 4.5)
            check(name, "mark on background", x.mark, s.background, 3.0)
        }
        check("Covers", "cover text on tile", FieldExtras.coverText, FieldExtras.coverTile, 4.5)
    }

    @Test fun theFlamePaletteIsGone() {
        val retired = listOf(Color(0xFFE8601C), Color(0xFF0E0E0E))
        for ((name, s, _) in schemes) {
            for (c in listOf(s.primary, s.background, s.surface, s.secondary, s.tertiary)) {
                assertWithMessage("$name uses a retired colour").that(retired).doesNotContain(c)
            }
        }
    }
}
