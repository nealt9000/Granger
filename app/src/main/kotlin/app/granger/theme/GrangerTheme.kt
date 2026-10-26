package app.granger.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Every Granger colour lives here so a later light scheme only adds a second scheme. */
object GrangerColors {
    val Flame = Color(0xFFE8601C)
    val OnFlame = Color(0xFF140A04)
    val Background = Color(0xFF0E0E0E)
    val Surface = Color(0xFF141414)
    val SurfaceVariant = Color(0xFF1D1D1D)
    val SurfaceHigh = Color(0xFF262626)
    val OnSurface = Color(0xFFECE6DA)
    val OnSurfaceVariant = Color(0xFFB8B1A4)
    val Outline = Color(0xFF6E685E)
    val OutlineVariant = Color(0xFF34312C)
    val Error = Color(0xFFFF8A7A)
    val OnError = Color(0xFF2A0A06)
    val SurfaceLowest = Color(0xFF0A0A0A)
    val SurfaceLow = Color(0xFF111111)
    val SurfaceHighest = Color(0xFF2E2E2E)
    val SurfaceDim = Color(0xFF0E0E0E)
    val SurfaceBright = Color(0xFF353230)
    val InverseSurface = Color(0xFFECE6DA)
    val InverseOnSurface = Color(0xFF1D1B18)
    val InversePrimary = Color(0xFFA83F06)
    val SecondaryContainer = Color(0xFF3A3630)
    val OnSecondaryContainer = Color(0xFFECE6DA)
    val Tertiary = Color(0xFFE8B66B)
    val OnTertiary = Color(0xFF2B1A00)
    val TertiaryContainer = Color(0xFF4A3410)
    val OnTertiaryContainer = Color(0xFFFFE2B8)
    val Scrim = Color(0xFF000000)

    // The field kit (brand brief §8). Only the brand uses them until the theme pass gives Granger its Field and Night schemes.
    val Enamel = Color(0xFF2E4A45)
    val Lantern = Color(0xFFF0A35E)
    val FieldPaper = Color(0xFFEFE4D0)
}

val GrangerColorScheme = darkColorScheme(
    primary = GrangerColors.Flame,
    onPrimary = GrangerColors.OnFlame,
    primaryContainer = Color(0xFF5A2509),
    onPrimaryContainer = Color(0xFFFFDBC9),
    secondary = GrangerColors.OnSurfaceVariant,
    onSecondary = GrangerColors.Background,
    background = GrangerColors.Background,
    onBackground = GrangerColors.OnSurface,
    surface = GrangerColors.Surface,
    onSurface = GrangerColors.OnSurface,
    surfaceVariant = GrangerColors.SurfaceVariant,
    onSurfaceVariant = GrangerColors.OnSurfaceVariant,
    secondaryContainer = GrangerColors.SecondaryContainer,
    onSecondaryContainer = GrangerColors.OnSecondaryContainer,
    tertiary = GrangerColors.Tertiary,
    onTertiary = GrangerColors.OnTertiary,
    tertiaryContainer = GrangerColors.TertiaryContainer,
    onTertiaryContainer = GrangerColors.OnTertiaryContainer,
    inverseSurface = GrangerColors.InverseSurface,
    inverseOnSurface = GrangerColors.InverseOnSurface,
    inversePrimary = GrangerColors.InversePrimary,
    surfaceDim = GrangerColors.SurfaceDim,
    surfaceBright = GrangerColors.SurfaceBright,
    surfaceContainerLowest = GrangerColors.SurfaceLowest,
    surfaceContainerLow = GrangerColors.SurfaceLow,
    surfaceContainer = GrangerColors.SurfaceVariant,
    surfaceContainerHigh = GrangerColors.SurfaceHigh,
    surfaceContainerHighest = GrangerColors.SurfaceHighest,
    scrim = GrangerColors.Scrim,
    outline = GrangerColors.Outline,
    outlineVariant = GrangerColors.OutlineVariant,
    error = GrangerColors.Error,
    onError = GrangerColors.OnError,
)

@Composable
fun GrangerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GrangerColorScheme, content = content)
}
