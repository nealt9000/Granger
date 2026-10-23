// GrangerTheme.kt — change only this function; GrangerColors already matches the brand palette.
// Flame #E8601C, Tertiary (Amber) #E8B66B, OnSurface (Parchment) #ECE6DA, OnFlame (Ink) #140A04, Background #0E0E0E.
@Composable
fun GrangerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GrangerColorScheme, typography = GrangerTypography, content = content)
}
