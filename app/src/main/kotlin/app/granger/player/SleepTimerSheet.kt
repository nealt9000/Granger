package app.granger.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.granger.core.player.SleepSetting
import app.granger.theme.GrangerColors

/**
 * The sleep timer: 5–60 minutes or the end of the chapter, with a fade over the last seconds (the engine's). While one
 * runs, [remainingMs] is shown with Cancel; picking another restarts it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(remainingMs: Long?, onStart: (SleepSetting) -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Sleep timer", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (remainingMs != null) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Stops in ${formatTime(remainingMs)}", style = MaterialTheme.typography.bodyLarge, color = GrangerColors.Flame,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text("Cancel timer") }
                }
            }
            PlayerViewModel.SLEEP_MINUTES.forEach { minutes ->
                SleepChoice("$minutes minutes") { onStart(SleepSetting.After(minutes * 60_000L)) }
            }
            SleepChoice("End of chapter") { onStart(SleepSetting.EndOfChapter) }
        }
    }
}

@Composable
private fun SleepChoice(label: String, onClick: () -> Unit) {
    Text(
        label, style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
    )
}
