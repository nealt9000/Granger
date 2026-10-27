package app.granger.ebook

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
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Typeface, size, spacing, margins, alignment and theme. Every change goes to [onChange] at once and shows live. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextSettingsSheet(prefs: ReadingPrefs, onChange: (ReadingPrefs) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Setting("Typeface") {
                Chips(listOf(Typeface.SERIF to "Serif", Typeface.SANS to "Sans"), prefs.typeface) { onChange(prefs.copy(typeface = it)) }
            }
            Setting("Size") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { onChange(prefs.copy(fontScale = prefs.fontScale - ReadingPrefs.FONT_SCALE_STEP)) },
                        enabled = prefs.fontScale > ReadingPrefs.MIN_FONT_SCALE,
                    ) { Text("−") }
                    Text("${(prefs.fontScale * 100).roundToInt()}%", modifier = Modifier.width(56.dp), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(
                        onClick = { onChange(prefs.copy(fontScale = prefs.fontScale + ReadingPrefs.FONT_SCALE_STEP)) },
                        enabled = prefs.fontScale < ReadingPrefs.MAX_FONT_SCALE,
                    ) { Text("+") }
                }
            }
            Setting("Line spacing") {
                var value by remember(prefs.lineHeight) { mutableFloatStateOf(prefs.lineHeight.toFloat()) }
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    onValueChangeFinished = { onChange(prefs.copy(lineHeight = value.toDouble())) },
                    valueRange = ReadingPrefs.MIN_LINE_HEIGHT.toFloat()..ReadingPrefs.MAX_LINE_HEIGHT.toFloat(),
                )
            }
            Setting("Margins") {
                var value by remember(prefs.margins) { mutableFloatStateOf(prefs.margins.toFloat()) }
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    onValueChangeFinished = { onChange(prefs.copy(margins = value.toDouble())) },
                    valueRange = ReadingPrefs.MIN_MARGINS.toFloat()..ReadingPrefs.MAX_MARGINS.toFloat(),
                )
            }
            Setting("Alignment") {
                Chips(listOf(Align.START to "Left", Align.JUSTIFY to "Justified"), prefs.align) { onChange(prefs.copy(align = it)) }
            }
            Setting("Theme") {
                Chips(
                    listOf(ReadingTheme.APP to "Match app", ReadingTheme.DAY to "Day", ReadingTheme.SEPIA to "Sepia", ReadingTheme.NIGHT to "Night"),
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
            Setting("Pages") {
                Chips(listOf(false to "Page by page", true to "Scroll"), prefs.pdfScroll) { onChange(prefs.copy(pdfScroll = it)) }
            }
            Setting("Fit") {
                Chips(listOf(false to "Whole page", true to "Width"), prefs.pdfFitWidth) { onChange(prefs.copy(pdfFitWidth = it)) }
            }
            Setting("Theme") {
                // Sepia reads as Day on a PDF, so it shows as Day here.
                val shown = if (prefs.theme == ReadingTheme.SEPIA) ReadingTheme.DAY else prefs.theme
                Chips(listOf(ReadingTheme.APP to "Match app", ReadingTheme.DAY to "Day", ReadingTheme.NIGHT to "Night"), shown) {
                    onChange(prefs.copy(theme = it))
                }
            }
        }
    }
}

@Composable
private fun Setting(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun <T> Chips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}
