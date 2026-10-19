package app.granger.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.granger.theme.GrangerColors
import kotlin.math.roundToInt

/** [speed] snapped to the sheet's 0.1 steps within 0.5–3.0×. */
fun snapSpeed(speed: Float): Float = ((speed * 10).roundToInt().coerceIn(5, 30)) / 10f

private val PRESETS = listOf(0.8f, 1.0f, 1.2f, 1.5f, 2.0f)

/** This book's playback speed, 0.5× to 3.0× in 0.1 steps. The slider applies on release; the buttons at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(speed: Float, onSpeed: (Float) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        var value by remember(speed) { mutableFloatStateOf(snapSpeed(speed)) }
        Column(
            Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Playback speed", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onSpeed(snapSpeed(value - 0.1f)) }, enabled = value > PlayerViewModel.SPEEDS.first()) {
                    Text("−", modifier = Modifier.semantics { contentDescription = "Slower" })
                }
                Text(speedLabel(value), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.width(80.dp))
                OutlinedButton(onClick = { onSpeed(snapSpeed(value + 0.1f)) }, enabled = value < PlayerViewModel.SPEEDS.last()) {
                    Text("+", modifier = Modifier.semantics { contentDescription = "Faster" })
                }
            }
            Slider(
                value = value,
                onValueChange = { value = snapSpeed(it) },
                onValueChangeFinished = { onSpeed(value) },
                valueRange = PlayerViewModel.SPEEDS.first()..PlayerViewModel.SPEEDS.last(),
                steps = PlayerViewModel.SPEEDS.size - 2,
                colors = SliderDefaults.colors(thumbColor = GrangerColors.Flame, activeTrackColor = GrangerColors.Flame),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playback speed" },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESETS.forEach { preset ->
                    FilterChip(selected = value == preset, onClick = { onSpeed(preset) }, label = { Text(speedLabel(preset)) })
                }
            }
        }
    }
}
