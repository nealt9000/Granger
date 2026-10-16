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
    surfaceContainer = GrangerColors.SurfaceVariant,
    surfaceContainerHigh = GrangerColors.SurfaceHigh,
    outline = GrangerColors.Outline,
    outlineVariant = GrangerColors.OutlineVariant,
    error = GrangerColors.Error,
    onError = GrangerColors.OnError,
)

@Composable
fun GrangerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GrangerColorScheme, content = content)
}
