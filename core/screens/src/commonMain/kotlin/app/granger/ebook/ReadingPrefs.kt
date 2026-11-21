package app.granger.ebook

import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [APP] follows the app's theme (Field or Night, Settings → Appearance); the others are the reader's own. */
enum class ReadingTheme { APP, DAY, SEPIA, NIGHT }

enum class Typeface { SERIF, SANS }

enum class Align { START, JUSTIFY }

/** Reference links in the Classics (Plan LL1 Decision 5): none, the Light set (the default), or More. */
enum class LinkLevel(val maxTier: Int) { OFF(0), LIGHT(1), MORE(2) }

/**
 * How books look. Global, not per book. Shared since Plan I5 Task 2: the values, their ranges and the store; turning
 * them into the reader's own settings (Readium's `EpubPreferences` on Android: `toEpub`, `toPdfium`, the page colours)
 * is each phone's.
 */
data class ReadingPrefs(
    val typeface: Typeface = Typeface.SERIF,
    /** 0.75..2.5, in steps of 0.125. */
    val fontScale: Double = 1.0,
    /** 1.2..2.0. */
    val lineHeight: Double = 1.5,
    /** 0.5..2.0. */
    val margins: Double = 1.0,
    val align: Align = Align.START,
    val theme: ReadingTheme = ReadingTheme.APP,
    /** Whether the app is in Night now; not saved, the store is told it ([ReadingPrefsStore.setAppNight]). */
    val appNight: Boolean = true,
    /** PDFs: pages scroll on continuously instead of turning one at a time. */
    val pdfScroll: Boolean = false,
    /** PDFs: a page fills the width (scroll down it) instead of fitting whole on screen. */
    val pdfFitWidth: Boolean = false,
    /** The wiki reader's text size (Plan U3c Decision 4): the WebView's `textZoom`, a percent, 80..160 in steps of 10. */
    val wikiTextZoom: Int = 100,
    /** Reference links in the Classics (Plan LL1): Light by default (Neal, 2026-11-11). */
    val referenceLinks: LinkLevel = LinkLevel.LIGHT,
    /** A Wikipedia article opens at its topic page (Plan LL2 Decision 1, on by default: Neal, 2026-11-16), or at the article. */
    val topicFirst: Boolean = true,
) {
    /** The page is Night: chosen, or Match app while the app is in Night. */
    val pageNight: Boolean get() = theme == ReadingTheme.NIGHT || (theme == ReadingTheme.APP && appNight)

    /** A wiki page is darkened: Night chosen, or Match app while the app is in [appNight]. Sepia reads as Day there. */
    fun wikiNight(appNight: Boolean): Boolean = theme == ReadingTheme.NIGHT || (theme == ReadingTheme.APP && appNight)

    /** PDF pages are drawn inverted: Night is the one theme a PDF honours. */
    val pdfNight: Boolean get() = pageNight

    /** What PDFium reads only when it lays a PDF out; the reader rebuilds the navigator when it changes. */
    val pdfLook: String get() = listOf(if (pdfNight) "night" else "day", if (pdfFitWidth) "width" else "page").joinToString("-")

    /** The same preferences with every number inside its range. */
    fun clamped(): ReadingPrefs = copy(
        fontScale = (fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE) / FONT_SCALE_STEP).roundToInt() * FONT_SCALE_STEP,
        lineHeight = lineHeight.coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT),
        margins = margins.coerceIn(MIN_MARGINS, MAX_MARGINS),
        wikiTextZoom = (wikiTextZoom.coerceIn(MIN_WIKI_ZOOM, MAX_WIKI_ZOOM) / WIKI_ZOOM_STEP.toDouble()).roundToInt() * WIKI_ZOOM_STEP,
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
        const val MIN_WIKI_ZOOM = 80
        const val MAX_WIKI_ZOOM = 160
        const val WIKI_ZOOM_STEP = 10
    }
}

/**
 * The reading preferences, kept across launches where [read] and [write] keep them (Android: a SharedPreferences file,
 * `ReadingPrefsStore(shared)`; the iPhone: UserDefaults). A value is a String, a Boolean or an Int, as Android has
 * always stored them: the numbers with a fraction as strings, the switches as booleans, the wiki zoom as an int. Every
 * value is clamped on the way in and out. With nothing kept (tests, previews) they last as long as the object.
 */
open class ReadingPrefsStore(
    private val read: (key: String) -> Any? = { null },
    private val write: (values: Map<String, Any>) -> Unit = {},
) {
    private val state = MutableStateFlow(load())
    val prefs: StateFlow<ReadingPrefs> = state.asStateFlow()

    /** The app's theme now (MainActivity, at creation and on every change); Match app follows it. */
    fun setAppNight(night: Boolean) {
        if (state.value.appNight != night) state.value = state.value.copy(appNight = night)
    }

    fun update(p: ReadingPrefs) {
        val clamped = p.clamped().copy(appNight = state.value.appNight)
        state.value = clamped
        write(
            linkedMapOf(
                KEY_TYPEFACE to clamped.typeface.name,
                KEY_FONT_SCALE to clamped.fontScale.toString(),
                KEY_LINE_HEIGHT to clamped.lineHeight.toString(),
                KEY_MARGINS to clamped.margins.toString(),
                KEY_ALIGN to clamped.align.name,
                KEY_THEME to clamped.theme.name,
                KEY_PDF_SCROLL to clamped.pdfScroll,
                KEY_PDF_FIT_WIDTH to clamped.pdfFitWidth,
                KEY_WIKI_TEXT_ZOOM to clamped.wikiTextZoom,
                KEY_REFERENCE_LINKS to clamped.referenceLinks.name,
                KEY_TOPIC_FIRST to clamped.topicFirst,
            ),
        )
    }

    private fun load(): ReadingPrefs {
        val d = ReadingPrefs()
        fun string(key: String) = read(key) as? String
        fun double(key: String, default: Double) = string(key)?.toDoubleOrNull() ?: default
        fun boolean(key: String, default: Boolean) = when (val v = read(key)) {
            is Boolean -> v
            is String -> v.toBooleanStrictOrNull() ?: default
            else -> default
        }
        fun int(key: String, default: Int) = when (val v = read(key)) {
            is Int -> v
            is Long -> v.toInt()
            is String -> v.toIntOrNull() ?: default
            else -> default
        }
        return ReadingPrefs(
            typeface = enumOr(string(KEY_TYPEFACE), d.typeface),
            fontScale = double(KEY_FONT_SCALE, d.fontScale),
            lineHeight = double(KEY_LINE_HEIGHT, d.lineHeight),
            margins = double(KEY_MARGINS, d.margins),
            align = enumOr(string(KEY_ALIGN), d.align),
            theme = enumOr(string(KEY_THEME), d.theme),
            pdfScroll = boolean(KEY_PDF_SCROLL, d.pdfScroll),
            pdfFitWidth = boolean(KEY_PDF_FIT_WIDTH, d.pdfFitWidth),
            wikiTextZoom = int(KEY_WIKI_TEXT_ZOOM, d.wikiTextZoom),
            referenceLinks = enumOr(string(KEY_REFERENCE_LINKS), d.referenceLinks),
            topicFirst = boolean(KEY_TOPIC_FIRST, d.topicFirst),
        ).clamped()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    companion object {
        const val KEY_TYPEFACE = "typeface"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_LINE_HEIGHT = "line_height"
        const val KEY_MARGINS = "margins"
        const val KEY_ALIGN = "align"
        const val KEY_THEME = "theme"
        const val KEY_PDF_SCROLL = "pdf_scroll"
        const val KEY_PDF_FIT_WIDTH = "pdf_fit_width"
        const val KEY_WIKI_TEXT_ZOOM = "wiki_text_zoom"
        const val KEY_REFERENCE_LINKS = "reference_links"
        const val KEY_TOPIC_FIRST = "topic_first"
    }
}
