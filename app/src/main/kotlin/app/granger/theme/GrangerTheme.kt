package app.granger.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The field kit (brand brief §8): the tokens both schemes are built from. Measured from the field-recorder image. */
object FieldKit {
    val Enamel = Color(0xFF2E4A45)
    val Gunmetal = Color(0xFF24292A)
    val Night = Color(0xFF181C1D)
    val Paper = Color(0xFFEFE4D0)
    val Ink = Color(0xFF1F1813)
    val Lantern = Color(0xFFF0A35E)
    val Phosphor = Color(0xFF91C4BA)
    val Brass = Color(0xFFA87B52)
    val Leather = Color(0xFF5B3322)
    val Dusk = Color(0xFF354059)
}

/** Field: paper, ink and enamel, for daylight (Plan T1, Decision 2). */
val FieldColors: ColorScheme = lightColorScheme(
    primary = FieldKit.Enamel,
    onPrimary = FieldKit.Paper,
    primaryContainer = Color(0xFFD3E2DE),
    onPrimaryContainer = Color(0xFF10211E),
    secondary = Color(0xFF3A4243),
    onSecondary = FieldKit.Paper,
    secondaryContainer = Color(0xFFE2E4E1),
    onSecondaryContainer = Color(0xFF1A1F20),
    tertiary = Color(0xFF8A4F1C),
    onTertiary = FieldKit.Paper,
    tertiaryContainer = Color(0xFFF6DEC6),
    onTertiaryContainer = Color(0xFF3A1F08),
    background = Color(0xFFF3EEE4),
    onBackground = FieldKit.Ink,
    surface = Color(0xFFF3EEE4),
    onSurface = FieldKit.Ink,
    surfaceVariant = Color(0xFFE1D9C9),
    onSurfaceVariant = Color(0xFF4A453F),
    surfaceTint = FieldKit.Enamel,
    surfaceContainerLowest = Color(0xFFFAF7F1),
    surfaceContainerLow = Color(0xFFF0EADF),
    surfaceContainer = Color(0xFFE8E1D3),
    surfaceContainerHigh = Color(0xFFE1D9C9),
    surfaceContainerHighest = Color(0xFFD9D0BE),
    surfaceDim = Color(0xFFD9D0BE),
    surfaceBright = Color(0xFFFAF7F1),
    outline = Color(0xFF77706A),
    outlineVariant = Color(0xFFCFC6B4),
    error = Color(0xFF962E19),
    onError = FieldKit.Paper,
    errorContainer = Color(0xFFF5D6CC),
    onErrorContainer = Color(0xFF3D0D04),
    inverseSurface = FieldKit.Gunmetal,
    inverseOnSurface = FieldKit.Paper,
    inversePrimary = FieldKit.Phosphor,
    scrim = Color(0xFF000000),
)

/** Night: night and gunmetal, lantern and phosphor (Plan T1, Decision 2). */
val NightColors: ColorScheme = darkColorScheme(
    primary = FieldKit.Lantern,
    onPrimary = FieldKit.Ink,
    primaryContainer = Color(0xFF5A3A1C),
    onPrimaryContainer = Color(0xFFFFDDB8),
    secondary = FieldKit.Phosphor,
    onSecondary = Color(0xFF0F2622),
    secondaryContainer = Color(0xFF2B4540),
    onSecondaryContainer = Color(0xFFD6EDE7),
    tertiary = Color(0xFFC9A27C),
    onTertiary = Color(0xFF2B1A0B),
    tertiaryContainer = Color(0xFF4A3524),
    onTertiaryContainer = Color(0xFFF3DCC4),
    background = Color(0xFF12211E),
    onBackground = FieldKit.Paper,
    surface = Color(0xFF12211E),
    onSurface = FieldKit.Paper,
    surfaceVariant = Color(0xFF233B36),
    onSurfaceVariant = Color(0xFFB8C4BF),
    surfaceTint = FieldKit.Lantern,
    surfaceContainerLowest = Color(0xFF0D1917),
    surfaceContainerLow = Color(0xFF172A26),
    surfaceContainer = Color(0xFF1C322E),
    surfaceContainerHigh = Color(0xFF233B36),
    surfaceContainerHighest = Color(0xFF2B4540),
    surfaceDim = Color(0xFF0D1917),
    surfaceBright = Color(0xFF34504A),
    outline = Color(0xFF7F948F),
    outlineVariant = Color(0xFF2F4A44),
    error = Color(0xFFFF8A7A),
    onError = Color(0xFF2A0A06),
    errorContainer = Color(0xFF5C1A10),
    onErrorContainer = Color(0xFFFFDAD3),
    inverseSurface = FieldKit.Paper,
    inverseOnSurface = FieldKit.Ink,
    inversePrimary = FieldKit.Enamel,
    scrim = Color(0xFF000000),
)

/** The brand's roles Material has no slot for (Plan T1, Decision 2). */
@Immutable
data class GrangerExtras(
    /** Night or Field, for the few views that must be told (the reader's WebView darkens pages only at night). */
    val night: Boolean,
    /** The command-line status voice, `> offline. ready.` */
    val status: Color,
    /** Play buttons and playback progress. */
    val play: Color,
    /** Fills, icons and rules; never text on paper (3.0:1). */
    val brass: Color,
    /** The mark in the lockup: the owl and the G, its eyes (always lantern) and its pupils. */
    val mark: Color,
    val markEyes: Color,
    val markPupils: Color,
    /** Generated book covers: an object, so the same in both themes. */
    val coverTile: Color = FieldKit.Gunmetal,
    val coverText: Color = FieldKit.Paper,
    val coverRule: Color = FieldKit.Lantern,
)

val FieldExtras = GrangerExtras(night = false, status = FieldKit.Enamel, play = Color(0xFF94531E), brass = FieldKit.Brass,
    mark = FieldKit.Enamel, markEyes = FieldKit.Lantern, markPupils = FieldKit.Enamel)
val NightExtras = GrangerExtras(night = true, status = FieldKit.Phosphor, play = FieldKit.Lantern, brass = FieldKit.Brass,
    mark = FieldKit.Paper, markEyes = FieldKit.Lantern, markPupils = FieldKit.Night)

val LocalGrangerExtras = staticCompositionLocalOf { NightExtras }

/** Field or Night. [night] is resolved by the caller from Settings → Appearance and the phone (Plan T1, Decision 1). */
@Composable
fun GrangerTheme(night: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGrangerExtras provides if (night) NightExtras else FieldExtras) {
        MaterialTheme(colorScheme = if (night) NightColors else FieldColors, typography = GrangerTypography, content = content)
    }
}

object GrangerTheme {
    val extras: GrangerExtras
        @Composable @ReadOnlyComposable get() = LocalGrangerExtras.current
}
