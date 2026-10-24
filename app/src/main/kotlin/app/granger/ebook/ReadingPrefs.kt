package app.granger.ebook

import android.content.SharedPreferences
import androidx.compose.ui.graphics.toArgb
import app.granger.theme.GrangerColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Axis
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.Fit
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import kotlin.math.roundToInt

enum class ReadingTheme { DAY, SEPIA, NIGHT }

enum class Typeface { SERIF, SANS }

enum class Align { START, JUSTIFY }

/** How books look. Global, not per book. */
data class ReadingPrefs(
    val typeface: Typeface = Typeface.SERIF,
    /** 0.75..2.5, in steps of 0.125. */
    val fontScale: Double = 1.0,
    /** 1.2..2.0. */
    val lineHeight: Double = 1.5,
    /** 0.5..2.0. */
    val margins: Double = 1.0,
    val align: Align = Align.START,
    val theme: ReadingTheme = ReadingTheme.NIGHT,
    /** PDFs: pages scroll on continuously instead of turning one at a time. */
    val pdfScroll: Boolean = false,
    /** PDFs: a page fills the width (scroll down it) instead of fitting whole on screen. */
    val pdfFitWidth: Boolean = false,
) {
    /**
     * Readium's preferences. Publisher styles are always off so the reader's choices win over the book's CSS
     * (Gutenberg's blue links on black among them), and books are paginated, never scrolled.
     */
    fun toEpub(): EpubPreferences = EpubPreferences(
        theme = when (theme) {
            ReadingTheme.DAY -> Theme.LIGHT
            ReadingTheme.SEPIA -> Theme.SEPIA
            ReadingTheme.NIGHT -> Theme.DARK
        },
        backgroundColor = if (theme == ReadingTheme.NIGHT) Color(GrangerColors.Background.toArgb()) else null,
        textColor = if (theme == ReadingTheme.NIGHT) Color(GrangerColors.OnSurface.toArgb()) else null,
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
    )

    /**
     * PDFium's preferences. A page turns sideways, or scrolls down when [pdfScroll]; its colours are the document's
     * own, but for Night, which [PdfiumEngineProvider]'s configure hook inverts (PDFium has no theme of its own).
     */
    @OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
    fun toPdfium(): PdfiumPreferences = PdfiumPreferences(
        fit = if (pdfFitWidth) Fit.WIDTH else Fit.CONTAIN,
        pageSpacing = PDF_PAGE_SPACING,
        scroll = pdfScroll,
        scrollAxis = if (pdfScroll) Axis.VERTICAL else Axis.HORIZONTAL,
    )

    /** PDF pages are drawn inverted: Night is the one theme a PDF honours. */
    val pdfNight: Boolean get() = theme == ReadingTheme.NIGHT

    /** What PDFium reads only when it lays a PDF out; the reader rebuilds the navigator when it changes. */
    val pdfLook: String get() = listOf(if (pdfNight) "night" else "day", if (pdfFitWidth) "width" else "page").joinToString("-")

    /** The same preferences with every number inside its range. */
    fun clamped(): ReadingPrefs = copy(
        fontScale = (fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE) / FONT_SCALE_STEP).roundToInt() * FONT_SCALE_STEP,
        lineHeight = lineHeight.coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT),
        margins = margins.coerceIn(MIN_MARGINS, MAX_MARGINS),
    )

    companion object {
        const val MIN_FONT_SCALE = 0.75
        const val MAX_FONT_SCALE = 2.5
        const val FONT_SCALE_STEP = 0.125
        const val MIN_LINE_HEIGHT = 1.2
        const val MAX_LINE_HEIGHT = 2.0
        const val MIN_MARGINS = 0.5
        const val MAX_MARGINS = 2.0
        const val PDF_PAGE_SPACING = 8.0
    }
}

/** The reading preferences, kept in [prefs] across launches; every value is clamped on the way in and out. */
class ReadingPrefsStore(private val shared: SharedPreferences) {
    private val state = MutableStateFlow(read())
    val prefs: StateFlow<ReadingPrefs> = state.asStateFlow()

    fun update(p: ReadingPrefs) {
        val clamped = p.clamped()
        state.value = clamped
        shared.edit()
            .putString(KEY_TYPEFACE, clamped.typeface.name)
            .putString(KEY_FONT_SCALE, clamped.fontScale.toString())
            .putString(KEY_LINE_HEIGHT, clamped.lineHeight.toString())
            .putString(KEY_MARGINS, clamped.margins.toString())
            .putString(KEY_ALIGN, clamped.align.name)
            .putString(KEY_THEME, clamped.theme.name)
            .putBoolean(KEY_PDF_SCROLL, clamped.pdfScroll)
            .putBoolean(KEY_PDF_FIT_WIDTH, clamped.pdfFitWidth)
            .apply()
    }

    private fun read(): ReadingPrefs {
        val d = ReadingPrefs()
        fun double(key: String, default: Double) = shared.getString(key, null)?.toDoubleOrNull() ?: default
        return ReadingPrefs(
            typeface = enumOr(shared.getString(KEY_TYPEFACE, null), d.typeface),
            fontScale = double(KEY_FONT_SCALE, d.fontScale),
            lineHeight = double(KEY_LINE_HEIGHT, d.lineHeight),
            margins = double(KEY_MARGINS, d.margins),
            align = enumOr(shared.getString(KEY_ALIGN, null), d.align),
            theme = enumOr(shared.getString(KEY_THEME, null), d.theme),
            pdfScroll = shared.getBoolean(KEY_PDF_SCROLL, d.pdfScroll),
            pdfFitWidth = shared.getBoolean(KEY_PDF_FIT_WIDTH, d.pdfFitWidth),
        ).clamped()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val KEY_TYPEFACE = "typeface"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_LINE_HEIGHT = "line_height"
        const val KEY_MARGINS = "margins"
        const val KEY_ALIGN = "align"
        const val KEY_THEME = "theme"
        const val KEY_PDF_SCROLL = "pdf_scroll"
        const val KEY_PDF_FIT_WIDTH = "pdf_fit_width"
    }
}
