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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.granger.R
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
            headlineContent = { Text(stringResource(R.string.settings_media_permission)) },
            supportingContent = {
                Text(
                    stringResource(
                        when (ui.permission) {
                            MediaPermissionStatus.GRANTED -> R.string.settings_media_permission_granted
                            MediaPermissionStatus.DENIED -> R.string.settings_media_permission_denied
                            MediaPermissionStatus.PERMANENTLY_DENIED -> R.string.settings_media_permission_permanently_denied
                        },
                    ),
                )
            },
            trailingContent = {
                when (ui.permission) {
                    MediaPermissionStatus.GRANTED -> Text(stringResource(R.string.settings_media_allowed), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MediaPermissionStatus.DENIED -> Button(onClick = onRequestPermission) { Text(stringResource(R.string.settings_media_allow_access)) }
                    MediaPermissionStatus.PERMANENTLY_DENIED -> TextButton(onClick = onOpenAppSettings) { Text(stringResource(R.string.settings_media_open_app_settings)) }
                }
            },
        )
    }
    item(key = "media-add-folder") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_media_add_folder), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.settings_media_add_folder_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAddFolder(false) }) { Text(stringResource(R.string.settings_media_music_folder)) }
                OutlinedButton(onClick = { onAddFolder(true) }) { Text(stringResource(R.string.settings_media_audiobooks_folder)) }
            }
        }
    }
    items(ui.folders, key = { "media-folder:${it.uri}" }) { folder ->
        ListItem(
            headlineContent = { Text(folder.name.resolve()) },
            supportingContent = { Text(stringResource(if (folder.audiobooks) R.string.settings_media_audiobooks_folder else R.string.settings_media_music_folder)) },
            trailingContent = {
                IconButton(onClick = { onRemoveFolder(folder.uri) }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_media_remove_folder, folder.name.resolve()))
                }
            },
            modifier = Modifier.padding(start = 16.dp),
        )
    }
    item(key = "media-rescan") {
        val running = ui.scan == RescanState.Running
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_media_rescan)) },
            supportingContent = {
                Text(
                    when (val s = ui.scan) {
                        RescanState.Idle -> stringResource(R.string.settings_media_rescan_idle)
                        RescanState.Running -> stringResource(R.string.settings_media_scanning)
                        is RescanState.Done -> stringResource(
                            R.string.settings_media_found,
                            pluralStringResource(R.plurals.settings_songs, s.summary.songs, s.summary.songs),
                            pluralStringResource(R.plurals.settings_audiobooks, s.summary.audiobooks, s.summary.audiobooks),
                        )
                        RescanState.Failed -> stringResource(R.string.settings_media_scan_failed)
                    },
                )
            },
            trailingContent = { if (running) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) },
            modifier = Modifier.clickable(enabled = !running, onClick = onRescan),
        )
    }
    item(key = "media-other-apps") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_media_other_apps)) },
            supportingContent = {
                Text(stringResource(R.string.settings_media_other_apps_summary))
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
            title = stringResource(R.string.settings_replaygain),
            description = stringResource(R.string.settings_replaygain_summary),
            choices = ReplayGainMode.entries,
            selected = ui.replayGain,
            label = { it.label },
            onSelect = onReplayGain,
        )
    }
    // Audiobook-only: hidden until there's an audiobook (Plan U2 Decision 7).
    if (ui.audiobooks) {
        item(key = "media-skip-back") {
            ChoiceRow(
                stringResource(R.string.settings_skip_back), stringResource(R.string.settings_skip_audiobooks), MediaSettings.SKIP_CHOICES,
                ui.skipBackS, { stringResource(R.string.settings_seconds_short, it) }, onSkipBack,
            )
        }
        item(key = "media-skip-forward") {
            ChoiceRow(
                stringResource(R.string.settings_skip_forward), stringResource(R.string.settings_skip_audiobooks), MediaSettings.SKIP_CHOICES,
                ui.skipForwardS, { stringResource(R.string.settings_seconds_short, it) }, onSkipForward,
            )
        }
    }
    item(key = "media-headset") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_headset_autoplay)) },
            supportingContent = { Text(stringResource(R.string.settings_headset_autoplay_summary)) },
            trailingContent = { Switch(checked = ui.headsetAutoplay, onCheckedChange = onHeadsetAutoplay) },
        )
        HorizontalDivider()
    }
}

private val ReplayGainMode.label @Composable get() = stringResource(
    when (this) {
        ReplayGainMode.OFF -> R.string.settings_replaygain_off
        ReplayGainMode.TRACK -> R.string.settings_replaygain_track
        ReplayGainMode.ALBUM -> R.string.settings_replaygain_album
        ReplayGainMode.AUTO -> R.string.settings_replaygain_auto
    },
)

@Composable
private fun <T> ChoiceRow(title: String, description: String, choices: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
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
