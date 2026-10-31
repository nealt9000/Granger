package app.granger.ebook

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import app.granger.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Typeface, size, spacing, margins, alignment and theme. Every change goes to [onChange] at once and shows live. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextSettingsSheet(prefs: ReadingPrefs, onChange: (ReadingPrefs) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Setting(R.string.ebook_typeface) {
                Chips(listOf(Typeface.SERIF to R.string.ebook_typeface_serif, Typeface.SANS to R.string.ebook_typeface_sans), prefs.typeface) { onChange(prefs.copy(typeface = it)) }
            }
            Setting(R.string.ebook_size) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { onChange(prefs.copy(fontScale = prefs.fontScale - ReadingPrefs.FONT_SCALE_STEP)) },
                        enabled = prefs.fontScale > ReadingPrefs.MIN_FONT_SCALE,
                    ) { Text("−") }
                    Text(stringResource(R.string.ebook_percent, (prefs.fontScale * 100).roundToInt()), modifier = Modifier.width(56.dp), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(
                        onClick = { onChange(prefs.copy(fontScale = prefs.fontScale + ReadingPrefs.FONT_SCALE_STEP)) },
                        enabled = prefs.fontScale < ReadingPrefs.MAX_FONT_SCALE,
                    ) { Text("+") }
                }
            }
            Setting(R.string.ebook_line_spacing) {
                var value by remember(prefs.lineHeight) { mutableFloatStateOf(prefs.lineHeight.toFloat()) }
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    onValueChangeFinished = { onChange(prefs.copy(lineHeight = value.toDouble())) },
                    valueRange = ReadingPrefs.MIN_LINE_HEIGHT.toFloat()..ReadingPrefs.MAX_LINE_HEIGHT.toFloat(),
                )
            }
            Setting(R.string.ebook_margins) {
                var value by remember(prefs.margins) { mutableFloatStateOf(prefs.margins.toFloat()) }
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    onValueChangeFinished = { onChange(prefs.copy(margins = value.toDouble())) },
                    valueRange = ReadingPrefs.MIN_MARGINS.toFloat()..ReadingPrefs.MAX_MARGINS.toFloat(),
                )
            }
            Setting(R.string.ebook_alignment) {
                Chips(listOf(Align.START to R.string.ebook_align_left, Align.JUSTIFY to R.string.ebook_align_justified), prefs.align) { onChange(prefs.copy(align = it)) }
            }
            Setting(R.string.ebook_theme) {
                Chips(
                    listOf(ReadingTheme.APP to R.string.ebook_theme_match_app, ReadingTheme.DAY to R.string.ebook_theme_day, ReadingTheme.SEPIA to R.string.ebook_theme_sepia, ReadingTheme.NIGHT to R.string.ebook_theme_night),
                    prefs.theme,
                ) { onChange(prefs.copy(theme = it)) }
            }
        }
    }
}

/**
 * A PDF's settings (Plan P2): its pages are drawn as printed, so there is no type to set; only how pages follow
 * each other, how one fits the screen, and the theme: Match app, Day or Night (PDFium inverts the page for Night).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfSettingsSheet(prefs: ReadingPrefs, onChange: (ReadingPrefs) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Setting(R.string.ebook_pdf_pages) {
                Chips(listOf(false to R.string.ebook_pdf_page_by_page, true to R.string.ebook_pdf_scroll), prefs.pdfScroll) { onChange(prefs.copy(pdfScroll = it)) }
            }
            Setting(R.string.ebook_pdf_fit) {
                Chips(listOf(false to R.string.ebook_pdf_whole_page, true to R.string.ebook_pdf_width), prefs.pdfFitWidth) { onChange(prefs.copy(pdfFitWidth = it)) }
            }
            Setting(R.string.ebook_theme) {
                // Sepia reads as Day on a PDF, so it shows as Day here.
                val shown = if (prefs.theme == ReadingTheme.SEPIA) ReadingTheme.DAY else prefs.theme
                Chips(listOf(ReadingTheme.APP to R.string.ebook_theme_match_app, ReadingTheme.DAY to R.string.ebook_theme_day, ReadingTheme.NIGHT to R.string.ebook_theme_night), shown) {
                    onChange(prefs.copy(theme = it))
                }
            }
        }
    }
}

/**
 * The wiki reader's Aa (Plan U3c Decision 4): the text size (the WebView's text zoom, saved) and the reader theme,
 * Match app, Day or Night, which the e-books share. A wiki page has no sepia of its own, so Sepia shows as Day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WikiTextSheet(prefs: ReadingPrefs, onChange: (ReadingPrefs) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Setting(R.string.ebook_text_size) {
                val smaller = stringResource(R.string.ebook_smaller_text)
                val larger = stringResource(R.string.ebook_larger_text)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { onChange(prefs.copy(wikiTextZoom = prefs.wikiTextZoom - ReadingPrefs.WIKI_ZOOM_STEP)) },
                        enabled = prefs.wikiTextZoom > ReadingPrefs.MIN_WIKI_ZOOM,
                        modifier = Modifier.semantics { contentDescription = smaller },
                    ) { Text("−") }
                    Text(stringResource(R.string.ebook_percent, prefs.wikiTextZoom), modifier = Modifier.width(56.dp), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(
                        onClick = { onChange(prefs.copy(wikiTextZoom = prefs.wikiTextZoom + ReadingPrefs.WIKI_ZOOM_STEP)) },
                        enabled = prefs.wikiTextZoom < ReadingPrefs.MAX_WIKI_ZOOM,
                        modifier = Modifier.semantics { contentDescription = larger },
                    ) { Text("+") }
                }
            }
            Setting(R.string.ebook_theme) {
                val shown = if (prefs.theme == ReadingTheme.SEPIA) ReadingTheme.DAY else prefs.theme
                Chips(listOf(ReadingTheme.APP to R.string.ebook_theme_match_app, ReadingTheme.DAY to R.string.ebook_theme_day, ReadingTheme.NIGHT to R.string.ebook_theme_night), shown) {
                    onChange(prefs.copy(theme = it))
                }
            }
        }
    }
}

@Composable
private fun Setting(@StringRes label: Int, content: @Composable () -> Unit) {
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun <T> Chips(options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(stringResource(label)) })
        }
    }
}
