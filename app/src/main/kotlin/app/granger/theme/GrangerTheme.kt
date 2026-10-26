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
    primaryContainer = Color(0xFFCFE0DB),
    onPrimaryContainer = Color(0xFF10211E),
    secondary = FieldKit.Leather,
    onSecondary = FieldKit.Paper,
    secondaryContainer = Color(0xFFEADBCB),
    onSecondaryContainer = Color(0xFF2E170D),
    tertiary = FieldKit.Dusk,
    onTertiary = FieldKit.Paper,
    tertiaryContainer = Color(0xFFD9DDEA),
    onTertiaryContainer = Color(0xFF1A2133),
    background = FieldKit.Paper,
    onBackground = FieldKit.Ink,
    surface = FieldKit.Paper,
    onSurface = FieldKit.Ink,
    surfaceVariant = Color(0xFFE3D5BC),
    onSurfaceVariant = Color(0xFF4A4239),
    surfaceTint = FieldKit.Enamel,
    surfaceContainerLowest = Color(0xFFF7EFE2),
    surfaceContainerLow = Color(0xFFF2E8D7),
    surfaceContainer = Color(0xFFE9DCC6),
    surfaceContainerHigh = Color(0xFFE3D5BC),
    surfaceContainerHighest = Color(0xFFDCCCB0),
    surfaceDim = Color(0xFFD9CBB2),
    surfaceBright = Color(0xFFF7EFE2),
    outline = Color(0xFF7A6E5F),
    outlineVariant = Color(0xFFCDBFA6),
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
    secondaryContainer = FieldKit.Enamel,
    onSecondaryContainer = Color(0xFFD6EDE7),
    tertiary = Color(0xFFC9A27C),
    onTertiary = Color(0xFF2B1A0B),
    tertiaryContainer = Color(0xFF4A3524),
    onTertiaryContainer = Color(0xFFF3DCC4),
    background = FieldKit.Night,
    onBackground = FieldKit.Paper,
    surface = FieldKit.Night,
    onSurface = FieldKit.Paper,
    surfaceVariant = Color(0xFF2C3233),
    onSurfaceVariant = Color(0xFFB9B3A6),
    surfaceTint = FieldKit.Lantern,
    surfaceContainerLowest = Color(0xFF121516),
    surfaceContainerLow = Color(0xFF1C2122),
    surfaceContainer = FieldKit.Gunmetal,
    surfaceContainerHigh = Color(0xFF2C3233),
    surfaceContainerHighest = Color(0xFF353C3D),
    surfaceDim = Color(0xFF121516),
    surfaceBright = Color(0xFF3A4142),
    outline = Color(0xFF7F8786),
    outlineVariant = Color(0xFF3A4243),
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
    /** The command-line status voice, `> offline. ready.` */
    val status: Color,
    /** Play buttons and playback progress. */
    val play: Color,
    /** Fills, icons and rules; never text on paper (3.0:1). */
    val brass: Color,
    /** The mark in the lockup. */
    val mark: Color,
    /** Generated book covers: an object, so the same in both themes. */
    val coverTile: Color = FieldKit.Gunmetal,
    val coverText: Color = FieldKit.Paper,
    val coverRule: Color = FieldKit.Lantern,
)

val FieldExtras = GrangerExtras(status = FieldKit.Enamel, play = FieldKit.Enamel, brass = FieldKit.Brass, mark = FieldKit.Enamel)
val NightExtras = GrangerExtras(status = FieldKit.Phosphor, play = FieldKit.Lantern, brass = FieldKit.Brass, mark = FieldKit.Lantern)

val LocalGrangerExtras = staticCompositionLocalOf { NightExtras }

/** Field or Night. [night] is resolved by the caller from Settings → Appearance and the phone (Plan T1, Decision 1). */
@Composable
fun GrangerTheme(night: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGrangerExtras provides if (night) NightExtras else FieldExtras) {
        MaterialTheme(colorScheme = if (night) NightColors else FieldColors, content = content)
    }
}

object GrangerTheme {
    val extras: GrangerExtras
        @Composable @ReadOnlyComposable get() = LocalGrangerExtras.current
}

/**
 * The retired flame palette's names, now pointing at Night so nothing changes look before Task 2 moves each reader onto the scheme.
 * Deleted at the end of Plan T1 Task 2.
 */
@Deprecated("Read MaterialTheme.colorScheme or GrangerTheme.extras (Plan T1, Decision 3)")
object GrangerColors {
    val Flame = NightColors.primary
    val OnFlame = NightColors.onPrimary
    val Background = NightColors.background
    val Surface = NightColors.surface
    val SurfaceVariant = NightColors.surfaceContainer
    val SurfaceHigh = NightColors.surfaceContainerHigh
    val OnSurface = NightColors.onSurface
    val OnSurfaceVariant = NightColors.onSurfaceVariant
    val Outline = NightColors.outline
    val OutlineVariant = NightColors.outlineVariant
    val Error = NightColors.error
    val OnError = NightColors.onError
    val SurfaceLowest = NightColors.surfaceContainerLowest
    val SurfaceLow = NightColors.surfaceContainerLow
    val SurfaceHighest = NightColors.surfaceContainerHighest
    val SurfaceDim = NightColors.surfaceDim
    val SurfaceBright = NightColors.surfaceBright
    val InverseSurface = NightColors.inverseSurface
    val InverseOnSurface = NightColors.inverseOnSurface
    val InversePrimary = NightColors.inversePrimary
    val SecondaryContainer = NightColors.secondaryContainer
    val OnSecondaryContainer = NightColors.onSecondaryContainer
    val Tertiary = NightColors.tertiary
    val OnTertiary = NightColors.onTertiary
    val TertiaryContainer = NightColors.tertiaryContainer
    val OnTertiaryContainer = NightColors.onTertiaryContainer
    val Scrim = NightColors.scrim
    val Enamel = FieldKit.Enamel
    val Lantern = FieldKit.Lantern
    val FieldPaper = FieldKit.Paper
}
