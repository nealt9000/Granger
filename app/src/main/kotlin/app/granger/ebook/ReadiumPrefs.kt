package app.granger.ebook

import android.content.SharedPreferences
import androidx.compose.ui.graphics.toArgb
import app.granger.theme.FieldColors
import app.granger.theme.NightColors
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Axis
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.Fit
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Spread
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme

// The values, their ranges and the store are shared (core:screens, Plan I5 Task 2); Readium's settings and the page
// colours, which name Android's and Readium's types, are Android's.

/**
 * Readium's preferences. Publisher styles are always off so the reader's choices win over the book's CSS
 * (Gutenberg's blue links on black among them), and books are paginated, never scrolled. A [fixedLayout] book
 * (a picture book, FixedPages) shows one page at a time, fitted to the screen: a phone is too small for two, and
 * Prunelle's pages are already spreads.
 */
fun ReadingPrefs.toEpub(fixedLayout: Boolean = false): EpubPreferences = EpubPreferences(
    theme = when {
        pageNight -> Theme.DARK
        theme == ReadingTheme.SEPIA -> Theme.SEPIA
        else -> Theme.LIGHT
    },
    // Night is the app's Night; Match app in Field is the app's paper and ink. Day and Sepia keep Readium's own.
    backgroundColor = when {
        pageNight -> Color(NightColors.background.toArgb())
        theme == ReadingTheme.APP -> Color(FieldColors.background.toArgb())
        else -> null
    },
    textColor = when {
        pageNight -> Color(NightColors.onSurface.toArgb())
        theme == ReadingTheme.APP -> Color(FieldColors.onSurface.toArgb())
        else -> null
    },
    fontFamily = when (typeface) {
        Typeface.SERIF -> FontFamily.SERIF
        Typeface.SANS -> FontFamily.SANS_SERIF
    },
    fontSize = fontScale,
    lineHeight = lineHeight,
    pageMargins = margins,
    textAlign = when (align) {
        Align.START -> TextAlign.START
        Align.JUSTIFY -> TextAlign.JUSTIFY
    },
    publisherStyles = false,
    scroll = false,
    spread = if (fixedLayout) Spread.NEVER else null,
)

/**
 * PDFium's preferences. A page turns sideways, or scrolls down when [pdfScroll]; its colours are the document's
 * own, but for Night, which [PdfiumEngineProvider]'s configure hook inverts (PDFium has no theme of its own).
 */
@OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
fun ReadingPrefs.toPdfium(): PdfiumPreferences = PdfiumPreferences(
    fit = if (pdfFitWidth) Fit.WIDTH else Fit.CONTAIN,
    pageSpacing = ReadingPrefs.PDF_PAGE_SPACING,
    scroll = pdfScroll,
    scrollAxis = if (pdfScroll) Axis.VERTICAL else Axis.HORIZONTAL,
)


/**
 * The colour of a book's page, as an ARGB int: what the reader paints behind the navigator, so the rail's place and
 * the cutout's band beside the page read as the page, not as the app's theme when the two differ.
 */
val ReadingPrefs.bookPageColor: Int get() = when {
    pageNight -> NightColors.background.toArgb()
    theme == ReadingTheme.APP -> FieldColors.background.toArgb()
    theme == ReadingTheme.SEPIA -> Theme.SEPIA.backgroundColor
    else -> Theme.LIGHT.backgroundColor
}

/** [bookPageColor] for a PDF: PDFium has no theme and fills around its pages in black, or white by day. */
val ReadingPrefs.pdfPageColor: Int get() = if (pdfNight) android.graphics.Color.BLACK else android.graphics.Color.WHITE


/** The reading preferences kept in [shared] (the "reading" SharedPreferences file), as they always were. */
fun ReadingPrefsStore(shared: SharedPreferences): ReadingPrefsStore = ReadingPrefsStore(
    read = { key -> shared.all[key] },
    write = { values ->
        val edit = shared.edit()
        for ((key, value) in values) {
            when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                else -> edit.putString(key, value.toString())
            }
        }
        edit.apply()
    },
)
