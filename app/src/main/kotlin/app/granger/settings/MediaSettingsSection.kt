package app.granger.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.granger.core.player.ReplayGainMode
import app.granger.media.MediaPermissionStatus

const val SHOW_IN_OTHER_APPS_TAG = "media-other-apps-switch"

/**
 * Settings → Music (& audiobooks): phone access, picked folders, "Rescan now", and the player's settings. Stateless:
 * the screen ([MediaSettingsScreen], which has the title) owns the permission prompt and the folder picker.
 */
fun LazyListScope.mediaSettingsSection(
    ui: MediaSettingsUi,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    /** true: an Audiobooks folder; false: a Music folder. */
    onAddFolder: (audiobooks: Boolean) -> Unit,
    onRemoveFolder: (uri: String) -> Unit,
    onRescan: () -> Unit,
    onShowInOtherApps: (Boolean) -> Unit,
    onReplayGain: (ReplayGainMode) -> Unit,
    onSkipBack: (Int) -> Unit,
    onSkipForward: (Int) -> Unit,
    onHeadsetAutoplay: (Boolean) -> Unit,
) {
    item(key = "media-permission") {
        ListItem(
            headlineContent = { Text("Find music and audiobooks on this phone") },
            supportingContent = {
                Text(
                    when (ui.permission) {
                        MediaPermissionStatus.GRANTED -> "Granger reads them where they are and never changes them."
                        MediaPermissionStatus.DENIED -> "Not allowed. Granger works without it; folders you add below still need it."
                        MediaPermissionStatus.PERMANENTLY_DENIED -> "Not allowed. Android won't ask again: allow Music and audio in Granger's app settings."
                    },
                )
            },
            trailingContent = {
                when (ui.permission) {
                    MediaPermissionStatus.GRANTED -> Text("Allowed", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MediaPermissionStatus.DENIED -> Button(onClick = onRequestPermission) { Text("Allow access") }
                    MediaPermissionStatus.PERMANENTLY_DENIED -> TextButton(onClick = onOpenAppSettings) { Text("Open app settings") }
                }
            },
        )
    }
    item(key = "media-add-folder") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Add a folder", style = MaterialTheme.typography.bodyLarge)
            Text(
                "For files the phone's media list misses. Granger only reads them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAddFolder(false) }) { Text("Music folder") }
                OutlinedButton(onClick = { onAddFolder(true) }) { Text("Audiobooks folder") }
            }
        }
    }
    items(ui.folders, key = { "media-folder:${it.uri}" }) { folder ->
        ListItem(
            headlineContent = { Text(folder.name) },
            supportingContent = { Text(if (folder.audiobooks) "Audiobooks folder" else "Music folder") },
            trailingContent = {
                IconButton(onClick = { onRemoveFolder(folder.uri) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove ${folder.name}")
                }
            },
            modifier = Modifier.padding(start = 16.dp),
        )
    }
    item(key = "media-rescan") {
        val running = ui.scan == RescanState.Running
        ListItem(
            headlineContent = { Text("Rescan now") },
            supportingContent = {
                Text(
                    when (val s = ui.scan) {
                        RescanState.Idle -> "Granger rescans on start and when the phone's media changes."
                        RescanState.Running -> "Scanning…"
                        is RescanState.Done -> "Found ${count(s.summary.songs, "song")} and ${count(s.summary.audiobooks, "audiobook")}."
                        RescanState.Failed -> "The scan didn't finish. Try again."
                    },
                )
            },
            trailingContent = { if (running) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) },
            modifier = Modifier.clickable(enabled = !running, onClick = onRescan),
        )
    }
    item(key = "media-other-apps") {
        ListItem(
            headlineContent = { Text("Show in other apps") },
            supportingContent = {
                Text(
                    "Granger's downloaded music and audiobooks appear in the Files app and in other apps' file pickers, " +
                        "read-only. Music players that only read the phone's media list won't list them.",
                )
            },
            trailingContent = {
                Switch(
                    checked = ui.showInOtherApps,
                    onCheckedChange = onShowInOtherApps,
                    modifier = Modifier.testTag(SHOW_IN_OTHER_APPS_TAG),
                )
            },
        )
    }
    item(key = "media-replaygain") {
        ChoiceRow(
            title = "ReplayGain",
            description = "Evens out loudness. Auto uses album gain when an album plays in order, else track gain.",
            choices = ReplayGainMode.entries,
            selected = ui.replayGain,
            label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
            onSelect = onReplayGain,
        )
    }
    // Audiobook-only: hidden until there's an audiobook (Plan U2 Decision 7).
    if (ui.audiobooks) {
        item(key = "media-skip-back") {
            ChoiceRow("Skip back", "Audiobooks", MediaSettings.SKIP_CHOICES, ui.skipBackS, { "$it s" }, onSkipBack)
        }
        item(key = "media-skip-forward") {
            ChoiceRow("Skip forward", "Audiobooks", MediaSettings.SKIP_CHOICES, ui.skipForwardS, { "$it s" }, onSkipForward)
        }
    }
    item(key = "media-headset") {
        ListItem(
            headlineContent = { Text("Play when headphones connect") },
            supportingContent = { Text("Resumes where you left off") },
            trailingContent = { Switch(checked = ui.headsetAutoplay, onCheckedChange = onHeadsetAutoplay) },
        )
        HorizontalDivider()
    }
}

private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

@Composable
private fun <T> ChoiceRow(title: String, description: String, choices: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in choices) {
                FilterChip(selected = choice == selected, onClick = { onSelect(choice) }, label = { Text(label(choice)) })
            }
        }
    }
}
