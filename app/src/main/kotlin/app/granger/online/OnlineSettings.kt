package app.granger.online

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What Settings → Online works on (Plan O2 Task 2): the prefs and Saved for now. */
class OnlineSettingsSource(val prefs: OnlinePrefs, val saved: SavedStore)

/**
 * Settings → Online, its first rows (Plan O2 Task 2, builder's call 5): Offline pictures, Saved for now's size against its
 * cap with the cap's choices, and Clear (asked once). O5 adds Use the internet, Never go online and Local only.
 */
@Composable
fun OnlineSettingsSection(source: OnlineSettingsSource, header: @Composable (String) -> Unit) {
    val pictures by source.prefs.offlinePictures.collectAsStateWithLifecycle()
    val cap by source.prefs.savedCapMb.collectAsStateWithLifecycle()
    var cleared by rememberSaveable { mutableIntStateOf(0) }
    val size by produceState(0L, cleared) { value = withContext(Dispatchers.IO) { source.saved.size() } }
    var asking by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    header(stringResource(R.string.settings_section_online))
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_offline_pictures)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.settings_offline_pictures_summary))
                for (choice in OnlinePrefs.OfflinePictures.entries) {
                    Row(
                        Modifier.selectable(selected = pictures == choice, role = Role.RadioButton) { source.prefs.setOfflinePictures(choice) }
                            .padding(vertical = 4.dp).testTag("${OnlineSettingsTags.PICTURES}_${choice.name}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = pictures == choice, onClick = null)
                        Text(
                            stringResource(if (choice == OnlinePrefs.OfflinePictures.SAVE) R.string.settings_offline_pictures_save else R.string.settings_offline_pictures_text),
                            Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_saved_for_now)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.settings_saved_for_now_summary, mb(size), cap))
                Row {
                    for (choice in OnlinePrefs.CAP_CHOICES) {
                        Row(
                            Modifier.selectable(selected = cap == choice, role = Role.RadioButton) {
                                source.prefs.setSavedCapMb(choice)
                                scope.launch(Dispatchers.IO) { source.saved.trim(choice * 1024L * 1024); cleared++ }
                            }.padding(end = 16.dp, top = 4.dp, bottom = 4.dp).testTag("${OnlineSettingsTags.CAP}_$choice"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = cap == choice, onClick = null)
                            Text(stringResource(R.string.settings_saved_cap, choice), Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_saved_clear)) },
        modifier = Modifier.clickable(enabled = size > 0, role = Role.Button) { asking = true }.testTag(OnlineSettingsTags.CLEAR),
    )
    HorizontalDivider()
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.settings_saved_clear_title)) },
            text = { Text(stringResource(R.string.settings_saved_clear_text)) },
            confirmButton = {
                TextButton(onClick = { scope.launch(Dispatchers.IO) { source.saved.clear(); cleared++ }; asking = false }) {
                    Text(stringResource(R.string.settings_saved_clear_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** Bytes as whole megabytes for the size line (at least 1 once anything is saved). */
internal fun mb(bytes: Long): Long = if (bytes <= 0) 0 else maxOf(1, bytes / (1024 * 1024))

object OnlineSettingsTags {
    const val PICTURES = "settings_offline_pictures"
    const val CAP = "settings_saved_cap"
    const val CLEAR = "settings_saved_clear"
}
