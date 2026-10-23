package app.granger.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.granger.R

/** Brand type. Spectral for display/reading, Courier Prime for labels, captions and the tagline. */
val Spectral = FontFamily(
    Font(R.font.spectral_regular, FontWeight.Normal),
    Font(R.font.spectral_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.spectral_medium, FontWeight.Medium),
    Font(R.font.spectral_semibold, FontWeight.SemiBold),
)

val CourierPrime = FontFamily(
    Font(R.font.courier_prime_regular, FontWeight.Normal),
    Font(R.font.courier_prime_bold, FontWeight.Bold),
)

/** The wordmark itself: Spectral 500, tracking -2%. */
val Wordmark = TextStyle(fontFamily = Spectral, fontWeight = FontWeight.Medium, letterSpacing = (-0.02).em, lineHeight = 1.em)

/** Tagline / mono labels: caps, tracking +18%. Apply uppercase() to the text. */
val MonoLabel = TextStyle(fontFamily = CourierPrime, fontWeight = FontWeight.Normal, fontSize = 12.sp, letterSpacing = 0.18.em)

val GrangerTypography = Typography(
    displayLarge = Wordmark.copy(fontSize = 57.sp),
    displayMedium = Wordmark.copy(fontSize = 45.sp),
    displaySmall = Wordmark.copy(fontSize = 36.sp),
    headlineLarge = TextStyle(fontFamily = Spectral, fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = Spectral, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = Spectral, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = Spectral, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Spectral, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = Spectral, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Spectral, fontSize = 18.sp, lineHeight = 27.sp),
    bodyMedium = TextStyle(fontFamily = Spectral, fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontFamily = Spectral, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = MonoLabel.copy(fontSize = 13.sp, letterSpacing = 0.12.em),
    labelMedium = MonoLabel,
    labelSmall = MonoLabel.copy(fontSize = 11.sp),
)
