package app.granger.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import app.granger.R

/** Spectral (OFL), for reading and headings (brand brief §8). Bundled; licences in docs/superpowers/brand/fonts. */
val Spectral = FontFamily(
    Font(R.font.spectral_regular, FontWeight.Normal),
    Font(R.font.spectral_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.spectral_medium, FontWeight.Medium),
    Font(R.font.spectral_semibold, FontWeight.SemiBold),
)

/** Courier Prime (OFL), for labels, counters and the command-line status voice. */
val CourierPrime = FontFamily(
    Font(R.font.courier_prime_regular, FontWeight.Normal),
    Font(R.font.courier_prime_bold, FontWeight.Bold),
)

private val Base = Typography()

private fun TextStyle.spectral(weight: FontWeight = FontWeight.Normal) = copy(fontFamily = Spectral, fontWeight = weight)
private fun TextStyle.courier(weight: FontWeight = FontWeight.Normal) = copy(fontFamily = CourierPrime, fontWeight = weight)

/** Plan T1, Decision 7: Material's sizes; Spectral for display to body (titles semibold), Courier Prime for labels. */
val GrangerTypography = Typography(
    displayLarge = Base.displayLarge.spectral(),
    displayMedium = Base.displayMedium.spectral(),
    displaySmall = Base.displaySmall.spectral(),
    headlineLarge = Base.headlineLarge.spectral(FontWeight.Medium),
    headlineMedium = Base.headlineMedium.spectral(FontWeight.Medium),
    headlineSmall = Base.headlineSmall.spectral(FontWeight.Medium),
    titleLarge = Base.titleLarge.spectral(FontWeight.SemiBold),
    titleMedium = Base.titleMedium.spectral(FontWeight.SemiBold),
    titleSmall = Base.titleSmall.spectral(FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.spectral(),
    bodyMedium = Base.bodyMedium.spectral(),
    bodySmall = Base.bodySmall.spectral(),
    labelLarge = Base.labelLarge.courier(FontWeight.Bold),
    labelMedium = Base.labelMedium.courier(FontWeight.Bold),
    labelSmall = Base.labelSmall.courier(),
)
